"""Plot exact GeoJSON results; requires matplotlib, Shapely, pyproj. No generated geometry."""
from pathlib import Path
import hashlib
import io
import json

ROOT = Path(__file__).resolve().parents[2]
OUT = ROOT / 'validation/rl'
baseline = json.loads((ROOT / 'validation/supplied-nearest-turns-report.json').read_text())
current = json.loads((OUT / 'supplied-rl-report.json').read_text())
model_sha = hashlib.sha256((ROOT / 'server/src/main/resources/models/reinforcement-policy.json').read_bytes()).hexdigest()
assert current['search']['reinforcement']['modelSha256'] == model_sha
input_sha = hashlib.sha256((ROOT / 'examples/supplied.geojson').read_bytes()).hexdigest()
assert input_sha == baseline['inputSha256']
for key in ['mode','dataMode','existingLoadPercent','gridM','candidateLimit','minDepthM','maxDepthM','rankingProfile']:
    assert current['options'][key] == baseline['options'][key], key
assert current['variants']
assert all(v['connected_connection_count'] == v['required_connection_count'] == 17 for v in current['variants'])
keys = ['score','base_score','new_network_length','calculated_cost','bend_count',
        'turn_90_count','turn_45_count','turn_135_count','turn_penalty_m','reconstruction_length']
comparison = {
    'inputSha256': input_sha, 'modelSha256': model_sha,
    'sameInputAndEngineeringOptions': True,
    'baseline': {k: baseline['variants'][0][k] for k in keys},
    'reinforcement': {k: current['variants'][0][k] for k in keys},
    'differenceRlMinusBaseline': {k: current['variants'][0][k]-baseline['variants'][0][k] for k in keys},
    'rlEpisodes': current['search']['reinforcement']['completedEpisodes'],
    'rlElapsedMs': current['elapsedMs'],
    'globalOptimalityProven': False,
    'limitations': ['Historical baseline has different geometry-query history and greedy search.',
                    'This is not an isolated comparison of learned weights.',
                    'Historical timing used HTTP/H2/Chromium; RL used in-process LinkedHashMap store.',
                    'No reliable speed claim from these runs.']
}
(OUT / 'supplied-comparison.json').write_text(json.dumps(comparison, ensure_ascii=False, indent=2))
print(json.dumps(comparison, ensure_ascii=False, indent=2))

import matplotlib
matplotlib.use('Agg')
import matplotlib.pyplot as plt
from matplotlib.patches import PathPatch
from matplotlib.path import Path as MplPath
from pyproj import Transformer
from shapely.geometry import shape
from shapely.geometry.polygon import orient
from shapely.ops import transform

project = Transformer.from_crs(4326, 32637, always_xy=True).transform
raw = json.loads((ROOT / 'examples/supplied.geojson').read_text())['features']
projected = [(f['properties'], transform(project, shape(f['geometry']))) for f in raw]
entries = [(p, g) for p, g in projected if p['object_type'] == 'oks_connection_point']
xs = [g.x for p, g in entries]; ys = [g.y for p, g in entries]
extent = (min(xs)-100, max(xs)+100, min(ys)-100, max(ys)+100)

def polygon(ax, geom, face, edge):
    pieces = geom.geoms if geom.geom_type == 'MultiPolygon' else [geom]
    for piece in pieces:
        piece = orient(piece, 1)
        vertices, codes = [], []
        for ring in [piece.exterior, *piece.interiors]:
            points = [c[:2] for c in ring.coords]
            vertices.extend(points)
            codes.extend([MplPath.MOVETO] + [MplPath.LINETO]*(len(points)-2) + [MplPath.CLOSEPOLY])
        ax.add_patch(PathPatch(MplPath(vertices, codes), facecolor=face, edgecolor=edge, linewidth=.55))

fig, axes = plt.subplots(1, 2, figsize=(14, 9.2), dpi=160)
fig.patch.set_facecolor('#f8fafb')
for ax, report, path, title in zip(axes, [baseline, current],
        [ROOT / 'validation/supplied-nearest-turns.geojson', OUT / 'supplied-rl.geojson'],
        ['Предыдущая версия · классический поиск', 'Новая версия · обучение с подкреплением']):
    ax.set_facecolor('#f8fafb')
    for prop, geom in projected:
        kind = prop['object_type']
        if geom.geom_type in ('Polygon', 'MultiPolygon'):
            building = kind in ('oks_existing', 'oks_future') or prop.get('restriction_type') == 'oks'
            polygon(ax, geom, '#dce1e4' if building else '#edf0ed', '#a1a9af' if building else '#c7ceca')
        elif kind == 'heat_network':
            lines = geom.geoms if geom.geom_type == 'MultiLineString' else [geom]
            for line in lines:
                ax.plot(*line.xy, color='#8864ba', linewidth=2.8, zorder=3)
    generated = json.loads(path.read_text())['features']
    for feature in generated:
        prop = feature['properties']
        if prop.get('variant_id') == 'v1' and prop['object_type'] == 'heat_network':
            geom = transform(project, shape(feature['geometry']))
            ax.plot(*geom.xy, color='#dc692d', linewidth=1.65, zorder=5)
    for prop, geom in entries:
        ax.scatter([geom.x], [geom.y], color='#183f50', s=20, zorder=7)
        ax.annotate(str(prop['id']), (geom.x, geom.y), xytext=(4, 4), textcoords='offset points', fontsize=7, color='#183f50', zorder=8)
    v = report['variants'][0]
    ax.set_title(title+'\n'+f"17/17 вводов · {v['new_network_length']:,.1f} м · {v['calculated_cost']/1e6:.2f} млн ₽\n"
                 +f"Индекс {v['score']:.4f} · повороты 90°: {v['turn_90_count']}; 45°: {v['turn_45_count']}", fontsize=11, pad=12)
    ax.set_xlim(*extent[:2]); ax.set_ylim(*extent[2:]); ax.set_aspect('equal'); ax.set_axis_off()
fig.suptitle('Исходный квартал · лучшие найденные варианты', fontsize=17, y=.99)
fig.text(.5, .027, 'Оранжевый — новая трасса; фиолетовый — существующая сеть. Повороты включают присоединения.\n'
         'Разные поисковые стратегии и история геометрических запросов; это не изолированное сравнение весов модели.',
         ha='center', va='center', fontsize=9, color='#485766')
fig.subplots_adjust(left=.01, right=.99, bottom=.07, top=.88, wspace=.015)
buffer = io.BytesIO()
fig.savefig(buffer, format='png', facecolor=fig.get_facecolor())
encoded = buffer.getvalue()
temporary = OUT / 'comparison.tmp'
temporary.write_bytes(encoded)
assert temporary.read_bytes() == encoded
temporary.replace(OUT / 'comparison.png')
