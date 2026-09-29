"""Draw exact GeoJSON geometry. Requires matplotlib, pyproj and shapely."""
import json
from pathlib import Path
import matplotlib
matplotlib.use('Agg')
import matplotlib.pyplot as plt
from matplotlib.lines import Line2D
from matplotlib.patches import Polygon as Patch
from shapely.geometry import shape
from shapely.ops import transform
from pyproj import Transformer

HERE = Path(__file__).resolve().parent
ROOT = HERE.parents[1]
PROJECT = Transformer.from_crs(4326, 32637, always_xy=True).transform
source = json.loads((ROOT / 'examples/supplied.geojson').read_text())['features']
versions = [('1.8.0 · исходный алгоритм', 'baseline-1.8.0', '#527c9f'),
            ('1.9.0 · геометрический поиск', 'supplied-trunk', '#087e75')]
all_new = []
for _, name, _ in versions:
    data = json.loads((HERE / (name + '.geojson')).read_text())['features']
    all_new.extend(transform(PROJECT, shape(f['geometry'])) for f in data
                   if f['properties'].get('variant_id') == 'v1'
                   and f['properties']['object_type'] == 'heat_network')
x0 = min(g.bounds[0] for g in all_new) - 40
y0 = min(g.bounds[1] for g in all_new) - 40
x1 = max(g.bounds[2] for g in all_new) + 40
y1 = max(g.bounds[3] for g in all_new) + 40

plt.rcParams.update({'font.family': 'DejaVu Sans', 'font.size': 10})
fig, axes = plt.subplots(1, 2, figsize=(13, 7))
for ax, (title, name, color) in zip(axes, versions):
    ax.set_aspect('equal')
    ax.set_facecolor('#f7f9fb')
    entries = []
    for f in source:
        g = transform(PROJECT, shape(f['geometry']))
        p = f['properties']
        if p['object_type'] in ('oks_existing', 'oks_future') or p.get('restriction_type') == 'oks':
            polys = list(g.geoms) if g.geom_type == 'MultiPolygon' else [g]
            for poly in polys:
                ax.add_patch(Patch([(x-x0,y-y0) for x,y,*_ in poly.exterior.coords],
                                   facecolor='#e1e6eb',edgecolor='#b8c1ca',linewidth=.5,zorder=1))
        elif p['object_type'] == 'heat_network':
            ax.plot([c[0]-x0 for c in g.coords], [c[1]-y0 for c in g.coords],
                    color='#b37e39',ls='--',lw=1.1,zorder=2)
        elif p['object_type'] == 'oks_connection_point':
            entries.append((g.x-x0,g.y-y0))
    out = json.loads((HERE / (name + '.geojson')).read_text())['features']
    for f in out:
        p = f['properties']
        if p.get('variant_id') != 'v1' or p['object_type'] not in ('heat_network', 'tie_in'):
            continue
        g = transform(PROJECT, shape(f['geometry']))
        if p['object_type'] == 'heat_network':
            ax.plot([c[0]-x0 for c in g.coords],[c[1]-y0 for c in g.coords],
                    color=color,lw=1.2+p['diameter']/220,zorder=3,solid_capstyle='round')
        elif p['object_type'] == 'tie_in':
            ax.scatter(g.x-x0,g.y-y0,s=115,marker='*',color='#ba482f',edgecolors='white',zorder=6)
    ax.scatter([p[0] for p in entries],[p[1] for p in entries],s=21,color='#28334a',
               edgecolors='white',linewidths=.7,zorder=5)
    for i,(x,y) in enumerate(entries,1):
        ax.annotate(str(i),(x,y),xytext=(4,4),textcoords='offset points',fontsize=8,zorder=7)
    report = json.loads((HERE / (name + '-report.json')).read_text())
    v = report['variants'][0]
    ax.set_title(title + '\n' + f"{v['new_network_length']:.1f} м · {v['calculated_cost']/1e6:.2f} млн ₽ · score {v['score']:.4f}",
                 loc='left',fontsize=12,pad=12)
    ax.set_xlim(0,x1-x0);ax.set_ylim(0,y1-y0)
    ax.set_xlabel('Расстояние по востоку, м');ax.set_ylabel('Расстояние по северу, м')
    ax.grid(alpha=.15,linewidth=.5)
    for spine in ax.spines.values(): spine.set_color('#cbd3dc')
fig.suptitle('Один датасет · 17 исходных вводов · одна общая врезка',fontsize=17,x=.06,ha='left',y=.99)
handles = [Line2D([],[],color='#087e75',lw=3,label='Новая трасса; толщина отражает DN'),
           Line2D([],[],color='#b37e39',lw=1.2,ls='--',label='Существующая сеть'),
           Line2D([],[],color='#28334a',marker='o',ls='',label='Исходные вводы'),
           Line2D([],[],color='#ba482f',marker='*',ms=12,ls='',label='Врезка')]
fig.legend(handles=handles,loc='lower center',ncol=2,frameon=False,bbox_to_anchor=(.5,.015))
fig.text(.06,.006,'EPSG:32637 · одинаковый масштаб · нумерация вводов только для иллюстрации',fontsize=8,color='#526073')
fig.tight_layout(rect=(0,.07,1,.96),w_pad=3)
fig.savefig(HERE / 'network-comparison.png',dpi=180,facecolor='white')
