"""Train an actual MLP on classical planner labels; split by whole territory.

Run from the project root: python3 tools/neural/train.py
The supplied customer quarter is never opened by this program.
"""
import hashlib
import json
import re
import warnings
from collections import defaultdict
from pathlib import Path

import numpy as np
import sklearn
from sklearn.exceptions import ConvergenceWarning
from sklearn.neural_network import MLPRegressor
from sklearn.preprocessing import StandardScaler

ROOT = Path(__file__).resolve().parents[2]
DATA = ROOT / 'tools/neural/data'
OUT = ROOT / 'server/src/main/resources/models/connection-ranker.json'
FEATURES_JAVA = ROOT / 'server/src/main/java/ru/teplotrassa/engine/CandidateFeatures.java'
feature_block = re.search(r'NAMES\s*=\s*List.of\((.*?)\);', FEATURES_JAVA.read_text(), re.S).group(1)
names = re.findall(r'"([^"]+)"', feature_block)
rows = []
hashes = {}
for territory in range(80):
    file = DATA / f'territory-{territory:03d}.jsonl'
    raw = file.read_bytes()
    report = json.loads((DATA / f'territory-{territory:03d}-report.json').read_text())
    assert len(raw.decode().splitlines()) == report['search']['neuralGuidance']['candidates'], 'Incomplete supervision file: ' + str(file)
    hashes[str(territory)] = hashlib.sha256(raw).hexdigest()
    rows.extend(json.loads(line) for line in raw.decode().splitlines())
groups = defaultdict(list)
for row in rows:
    assert len(row['features']) == len(names)
    assert not row['feasible'] or row['lowerBound'] <= row['score'] + 1e-6
    groups[row['territory'], row['group']].append(row)
eligible = []
for group in groups.values():
    feasible = [r['score'] for r in group if r['feasible']]
    if not feasible:
        continue
    # Infeasibility is a training preference, never a hard rejection at runtime.
    invalid_label = max(feasible) + 2.0
    for row in group:
        label = row['score'] if row['feasible'] else max(invalid_label, row['lowerBound'])
        row['target'] = np.log1p(max(0.0, label - row['lowerBound']))
        eligible.append(row)

splits = {'train': list(range(48)), 'validation': list(range(48, 64)), 'test': list(range(64, 80))}
training = [r for r in eligible if r['territory'] in splits['train']]
X = np.asarray([r['features'] for r in training])
y = np.asarray([r['target'] for r in training])
scaler = StandardScaler().fit(X)
# JTS distances to a wall can differ by ~1e-10 m after rotation. Never amplify
# near-constant geometric noise into a learned signal or an enormous unseen input.
near_constant = scaler.scale_ < 1e-6
scaler.scale_[near_constant] = 1.0
Xn = scaler.transform(X)


def priorities(model, records):
    lower = np.asarray([r['lowerBound'] for r in records])
    if model is None:
        return lower
    prediction = model.predict(scaler.transform([r['features'] for r in records]))
    return lower + np.expm1(np.clip(prediction, 0, 20))


def metrics(model, territory_ids):
    visited, original, first_regret, absolute_error = [], [], [], []
    for (territory, _), records in groups.items():
        if territory not in territory_ids:
            continue
        order = sorted(range(len(records)), key=lambda i: (priorities(model, records)[i], records[i]['candidate']))
        incumbent = float('inf')
        count = 0
        feasible = [r['score'] for r in records if r['feasible']]
        for i in order:
            row = records[i]
            if row['lowerBound'] > incumbent + 1e-8:
                continue
            count += 1
            if row['feasible']:
                incumbent = min(incumbent, row['score'])
        visited.append(count)
        original.append(len(records))
        if feasible:
            first = next((records[i]['score'] for i in order if records[i]['feasible']), max(feasible))
            first_regret.append(first - min(feasible))
        if model is not None:
            pred = model.predict(scaler.transform([r['features'] for r in records]))
            absolute_error.extend(abs(float(p) - r['target']) for p, r in zip(pred, records) if 'target' in r)
    return {'groups': len(visited), 'candidates': sum(original), 'estimated_targets_searched': sum(visited),
            'mean_first_feasible_regret': float(np.mean(first_regret)),
            'log_residual_mae': float(np.mean(absolute_error)) if absolute_error else None}


trials = []
for hidden in [(12,), (24, 12), (32, 16)]:
    for seed in [17, 42, 71]:
        model = MLPRegressor(hidden_layer_sizes=hidden, activation='relu', solver='lbfgs',
                             alpha=0.05, max_iter=700, max_fun=40000, random_state=seed,
                             tol=1e-7)
        with warnings.catch_warnings(record=True) as caught:
            warnings.simplefilter('always', ConvergenceWarning)
            model.fit(Xn, y)
        validation = metrics(model, splits['validation'])
        trials.append((validation['estimated_targets_searched'], validation['mean_first_feasible_regret'],
                       validation['log_residual_mae'], model, validation, bool(caught)))
        print('candidate', hidden, seed, validation, flush=True)
trials.sort(key=lambda t: t[:3])
_, _, _, model, validation, convergence_warning = trials[0]

provenance = {
    'generator': 'tools/neural/NeuralExperiment.java:territory',
    'seed_formula': '2026092000 + 1000003 * territory_id',
    'supervision': 'full classical candidate evaluation; off policy; no bound pruning',
    'teacher_query_state': 'fixed entry search envelope per query; full route checks independent of search history',
    'label': 'log1p(max(0, candidate_score - analytical_lower_bound))',
    'infeasible_label': 'max(maximum feasible score in group + 2, lower_bound); wholly infeasible groups excluded',
    'split_unit': 'whole territory; scaler fit on training only; test unused for model selection',
    'normalisation': 'standard scale below 1e-6 replaced by 1, preventing geometric round-off amplification',
    'near_constant_features': [name for name, flag in zip(names, near_constant) if flag],
    'splits': splits, 'dataset_sha256_by_territory': hashes,
    'scikit_learn_version': sklearn.__version__, 'numpy_version': np.__version__,
    'rows_total': len(rows), 'rows_training': len(training), 'synthetic_only': True,
    'customer_quarter_in_training': False, 'engineer_preferences_in_training': False,
    'selected_hidden_layers': list(model.hidden_layer_sizes), 'random_seed': model.random_state,
    'training_iterations': model.n_iter_, 'training_loss': float(model.loss_),
    'convergence_warning': convergence_warning,
    'selection': 'fewest simulated target evaluations on validation; then first feasible regret; then MAE',
}
export = {'format': 'teplotrassa-mlp-ranker-v1', 'model_id': 'synthetic-quarter-mlp-2026-09-20-r4',
          'activation': 'relu', 'target': 'log1p_score_residual', 'feature_names': names,
          'mean': scaler.mean_.tolist(), 'scale': scaler.scale_.tolist(),
          'weights': [v.tolist() for v in model.coefs_], 'biases': [v.tolist() for v in model.intercepts_],
          'provenance': provenance}
OUT.parent.mkdir(parents=True, exist_ok=True)
OUT.write_text(json.dumps(export, indent=2, allow_nan=False) + '\n')
result = {'provenance': provenance, 'model_sha256': hashlib.sha256(OUT.read_bytes()).hexdigest(),
          'metrics': {split: {'bounds': metrics(None, ids), 'neural': metrics(model, ids)}
                      for split, ids in splits.items()},
          'validation_trials': [{'hidden': list(t[3].hidden_layer_sizes), 'seed': t[3].random_state,
                                 'metrics': t[4], 'convergence_warning': t[5]} for t in trials]}
(ROOT / 'validation/neural-training-report.json').write_text(json.dumps(result, indent=2) + '\n')
# Cross-language fixture: inference in Java must reproduce sklearn on unseen territories.
fixtures = [r for r in eligible if r['territory'] in splits['test']][::13][:24]
checks = [{'features': r['features'], 'prediction': float(model.predict(scaler.transform([r['features']]))[0])}
          for r in fixtures]
(ROOT / 'tools/neural/inference-fixtures.json').write_text(json.dumps(checks, indent=2) + '\n')
print(json.dumps(result['metrics'], indent=2))
print('saved', OUT, 'sha256', result['model_sha256'])
