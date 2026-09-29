"""REINFORCE on complete networks, interacting with the exact Java engineering environment.

No teacher routes or supervised labels. NumPy backprop, Adam, leave-one-out per-map baseline.
Run from the project: python tools/rl/train.py --updates 320 --batch 12
"""
import argparse
import copy
import json
import os
from pathlib import Path
import subprocess
import time

import numpy as np
from runtime import ROOT, command


def write_json(path, value):
    path = Path(path)
    path.parent.mkdir(parents=True, exist_ok=True)
    data = json.dumps(value, ensure_ascii=False, indent=2, allow_nan=False).encode()
    temporary = path.with_suffix(path.suffix + '.tmp')
    temporary.write_bytes(data)
    os.replace(temporary, path)
    if path.read_bytes() != data:
        raise IOError('Incomplete file write: ' + str(path))


class Policy:
    def __init__(self, width, rng, hidden=32):
        self.p = [rng.normal(0, 1 / np.sqrt(width), (width, hidden)),
                  np.zeros(hidden), rng.normal(0, .04, hidden), np.zeros(1)]
        self.m = [np.zeros_like(p) for p in self.p]
        self.v = [np.zeros_like(p) for p in self.p]
        self.steps = 0

    def forward(self, features):
        x = np.clip(np.asarray(features, dtype=np.float64), -8, 8)
        h = np.tanh(x @ self.p[0] + self.p[1])
        logits = h @ self.p[2] + self.p[3][0]
        p = np.exp(logits - logits.max()); p /= p.sum()
        return x, h, logits, p

    def gradient(self, x, h, dlogits):
        dh = dlogits[:, None] * self.p[2][None, :] * (1 - h * h)
        return [x.T @ dh, dh.sum(axis=0), h.T @ dlogits, np.array([dlogits.sum()])]

    def update(self, gradients, rate):
        self.steps += 1
        norm = np.sqrt(sum(float(np.sum(g * g)) for g in gradients))
        for i, g in enumerate(gradients):
            g = g / max(1, norm)
            self.m[i] = .9 * self.m[i] + .1 * g
            self.v[i] = .999 * self.v[i] + .001 * g * g
            self.p[i] += rate * (self.m[i] / (1 - .9 ** self.steps)) / (
                np.sqrt(self.v[i] / (1 - .999 ** self.steps)) + 1e-8)
            if not np.isfinite(self.p[i]).all():
                raise FloatingPointError('Nonfinite policy weight')

    def export(self, names, trained_updates):
        return {'format': 'teplotrassa-reinforce-v1', 'model_id': 'reinforce-exact-engine-2026-09-20-u' + str(trained_updates),
                'activation': 'tanh', 'reward': 'complete-network-v1', 'feature_names': names,
                'input_clip': [-8, 8], 'w1': self.p[0].tolist(), 'b1': self.p[1].tolist(),
                'w2': self.p[2].tolist(), 'b2': float(self.p[3][0]), 'trained_updates': trained_updates}


class Bridge:
    def __init__(self, args):
        self.process = subprocess.Popen(args + ['serve'], stdin=subprocess.PIPE, stdout=subprocess.PIPE,
                                        text=True, encoding='utf-8', bufsize=1, cwd=ROOT)

    def call(self, **request):
        self.process.stdin.write(json.dumps(request) + '\n'); self.process.stdin.flush()
        line = self.process.stdout.readline()
        if not line:
            raise RuntimeError('Java environment stopped: ' + str(self.process.poll()))
        result = json.loads(line)
        if 'error' in result:
            raise RuntimeError(result['error'])
        return result

    def close(self):
        self.process.stdin.close()
        try:
            self.process.wait(timeout=10)
        except subprocess.TimeoutExpired:
            self.process.terminate(); self.process.wait(timeout=10)


def episode(bridge, policy, territory, rng, greedy=False, keep_trace=True, fixtures=None):
    state = bridge.call(command='reset', territory=territory)
    traces = []
    decisions = accepted = 0
    while not state['done']:
        features = [a['features'] for a in state['actions']]
        x, h, logits, probabilities = policy.forward(features)
        action = int(np.argmax(logits)) if greedy else int(rng.choice(len(probabilities), p=probabilities))
        if keep_trace:
            traces.append((x, h, probabilities, action))
        if fixtures is not None and len(fixtures) < 40:
            fixtures.append({'features': features[action], 'logit': float(logits[action])})
        state = bridge.call(command='step', action=action)
        accepted += int(state['accepted']); decisions += 1
    return {'territory': territory, 'reward': state['reward'], 'summary': state['summary'],
            'decisions': decisions, 'accepted': accepted}, traces


def validate(bridge, policy, ids, rng, fixtures=None):
    rows = [episode(bridge, policy, i, rng, greedy=True, keep_trace=False, fixtures=fixtures)[0] for i in ids]
    return {'meanReward': float(np.mean([r['reward'] for r in rows])),
            'complete': sum(r['summary']['connection_complete'] for r in rows),
            'territories': rows}


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('--updates', type=int, default=320)
    parser.add_argument('--batch', type=int, default=12)
    parser.add_argument('--seed', type=int, default=71)
    parser.add_argument('--learning-rate', type=float, default=.003)
    parser.add_argument('--output', type=Path, default=ROOT / 'validation/rl/training')
    args = parser.parse_args()
    if args.updates < 1 or args.batch < 2:
        parser.error('updates >= 1 and batch >= 2 required')
    rng = np.random.default_rng(args.seed)
    start = time.monotonic()
    report = {'algorithm': 'REINFORCE + leave-one-out baseline + entropy + Adam',
              'environment': 'exact-java-engine-v1', 'seed': args.seed, 'batch': args.batch,
              'learningRate': args.learning_rate, 'trainTerritories': list(range(24)),
              'validationTerritories': list(range(24, 32)), 'testTerritories': list(range(32, 48)),
              'sourceTerritoryOffset': 1000, 'trainingCandidateLimit': 2, 'trainingGridM': 20,
              'trainingMode': 'depth every third territory; plan otherwise',
              'rankingProfile': 'appendix (cost .7 / length .3 + turn equivalents)',
              'usesSuppliedMap': False, 'supervisedLabels': False,
              'episodes': 0, 'decisions': 0, 'acceptedActions': 0, 'history': []}
    with command() as cmd:
        bridge = Bridge(cmd)
        try:
            names = bridge.call(command='schema')['features']
            policy = Policy(len(names), rng)
            write_json(args.output / 'initial-policy.json', policy.export(names, 0))
            report['initialValidation'] = validate(bridge, policy, range(24, 32), rng)
            print('initial validation', report['initialValidation']['meanReward'], flush=True)
            best_reward = -np.inf
            best_weights = None
            for update in range(1, args.updates + 1):
                territory = int(rng.integers(24))
                rollouts = [episode(bridge, policy, territory, rng) for _ in range(args.batch)]
                rewards = np.array([row['reward'] for row, _ in rollouts])
                advantages = rewards - (rewards.sum() - rewards) / (args.batch - 1)
                advantages /= max(.025, float(rewards.std()))
                gradients = [np.zeros_like(p) for p in policy.p]
                for advantage, (row, traces) in zip(advantages, rollouts):
                    report['episodes'] += 1; report['decisions'] += row['decisions']
                    report['acceptedActions'] += row['accepted']
                    for x, h, probabilities, action in traces:
                        delta = -probabilities.copy(); delta[action] += 1
                        logp = np.log(np.maximum(probabilities, 1e-300))
                        entropy_grad = -probabilities * (logp - np.dot(probabilities, logp))
                        gs = policy.gradient(x, h, advantage * delta + .01 * entropy_grad)
                        for g, add in zip(gradients, gs):
                            g += add / args.batch
                policy.update(gradients, args.learning_rate)
                if update == 1 or update % 20 == 0 or update == args.updates:
                    validation = validate(bridge, policy, range(24, 32), rng)
                    record = {'update': update, 'episodes': report['episodes'], 'territory': territory,
                              'batchReward': float(rewards.mean()), 'validation': validation,
                              'elapsedSeconds': time.monotonic() - start}
                    report['history'].append(record)
                    if validation['meanReward'] > best_reward:
                        best_reward = validation['meanReward']; best_weights = copy.deepcopy(policy.p)
                        report['selectedUpdate'] = update
                        write_json(args.output / 'best-policy.json', policy.export(names, update))
                    write_json(args.output / 'latest-policy.json', policy.export(names, update))
                    write_json(args.output / 'training-report.json', report)
                    print('update', update, 'episodes', report['episodes'], 'validation', round(validation['meanReward'], 6),
                          'complete', validation['complete'], '/8', 'seconds', round(time.monotonic() - start), flush=True)
            policy.p = best_weights
            fixtures = []
            report['test'] = validate(bridge, policy, range(32, 48), rng, fixtures)
            report['elapsedSeconds'] = time.monotonic() - start
            report['completedUpdates'] = args.updates
            report['trainableParameters'] = sum(p.size for p in policy.p)
            report['featureCount'] = len(names)
            model = policy.export(names, report['selectedUpdate'])
            write_json(ROOT / 'server/src/main/resources/models/reinforcement-policy.json', model)
            write_json(ROOT / 'tools/rl/inference-fixtures.json', fixtures)
            write_json(args.output / 'training-report.json', report)
            print('saved trained model; held-out complete', report['test']['complete'], '/16', flush=True)
        finally:
            bridge.close()


if __name__ == '__main__':
    main()
