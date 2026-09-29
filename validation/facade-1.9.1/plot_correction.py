"""Render the user's marked corridor before and after; matplotlib + pyproj + shapely."""
import json
import math
from pathlib import Path

import matplotlib
matplotlib.use('Agg')
import matplotlib.pyplot as plt
from matplotlib.patches import Polygon as PolygonPatch
from pyproj import Transformer
from shapely.geometry import shape
from shapely.ops import transform

HERE = Path(__file__).resolve().parent
ROOT = HERE.parents[1]
project = Transformer.from_crs(4326, 32637, always_xy=True).transform
source = json.loads((ROOT / 'examples/supplied.geojson').read_text())['features']
old = json.loads((ROOT / 'validation/geometry-final/supplied-trunk.geojson').read_text())['features']
new = json.loads((HERE / 'supplied-facades.geojson').read_text())['features']

def main_run(features):
    return next(transform(project,shape(f['geometry'])) for f in features
                if f['properties'].get('variant_id') == 'v1'
                and f['properties']['id'] == 'tt:v1:corridor_19:0')

old_run, new_run = main_run(old), main_run(new)
limits = old_run.bounds
xlim = (limits[0]-45, limits[2]+45)
ylim = (limits[1]-60, limits[3]+60)
plt.rcParams.update({'font.family':'DejaVu Sans', 'font.size':10})
fig,axes=plt.subplots(1,2,figsize=(13.5,6.5),sharex=True,sharey=True)
for ax,(title,features,run,color) in zip(axes,[
    ('1.9.0 · направление старой сети',old,old_run,'#df783a'),
    ('1.9.1 · вдоль фасадов',new,new_run,'#0d8d71')]):
    ax.set_facecolor('#fafbf8')
    for f in source:
        p=f['properties']
        geom=transform(project,shape(f['geometry']))
        if p['object_type'] in ('oks_existing','oks_future') or p.get('restriction_type')=='oks':
            for poly in (geom.geoms if geom.geom_type=='MultiPolygon' else [geom]):
                if poly.bounds[2]<xlim[0] or poly.bounds[0]>xlim[1] or poly.bounds[3]<ylim[0] or poly.bounds[1]>ylim[1]:continue
                ax.add_patch(PolygonPatch(list(poly.exterior.coords),facecolor='#e3e8e7',edgecolor='#a5b4b3',lw=.7,zorder=1))
        elif p['object_type']=='oks_connection_point' and str(p['id']) in {'5','7'}:
            ax.scatter(geom.x,geom.y,s=65,color='#243b4e',edgecolor='white',zorder=8)
            ax.annotate('Ввод '+str(p['id']),(geom.x,geom.y),xytext=(5,8),textcoords='offset points',fontweight='bold',color='#243b4e',zorder=9)
    for f in features:
        p=f['properties']
        if p.get('variant_id')!='v1' or p['object_type']!='heat_network':continue
        line=transform(project,shape(f['geometry']))
        if not line.intersects(__import__('shapely').geometry.box(*[xlim[0],ylim[0],xlim[1],ylim[1]])):continue
        ax.plot(*line.xy,color='#b7c5c2',lw=1.8,zorder=3)
    for a,b in zip(run.coords,run.coords[1:]):
        ax.plot([a[0],b[0]],[a[1],b[1]],color=color,lw=4,zorder=6,solid_capstyle='round')
    ax.scatter([p[0] for p in run.coords],[p[1] for p in run.coords],s=30,color=color,edgecolor='white',zorder=7)
    ax.set_title(title,loc='left',fontsize=13,fontweight='bold',pad=14)
    ax.set_xlim(*xlim);ax.set_ylim(*ylim);ax.set_aspect('equal')
    ax.grid(color='#d3ddd9',alpha=.35)
    ax.set_xlabel('Восток, м')
    ax.set_ylabel('Север, м')
    ax.tick_params(labelsize=8)
old_d=old_run.coords[-1][0]-old_run.coords[0][0],old_run.coords[-1][1]-old_run.coords[0][1]
new_a,new_b=new_run.coords[-2:]
new_d=new_b[0]-new_a[0],new_b[1]-new_a[1]
deg=lambda d: math.degrees(math.atan2(abs(d[1]),abs(d[0])))
axes[0].text(.04,.03,f'Длинное плечо: {deg(old_d):.1f}° к горизонтали',transform=axes[0].transAxes,color='#9a4928',fontweight='bold')
axes[1].text(.04,.03,f'Длинное плечо: {deg(new_d):.1f}° · 3 узла для проверки',transform=axes[1].transAxes,color='#087159',fontweight='bold')
fig.suptitle('Отмеченный участок между вводами 5 и 7',fontsize=17,ha='left',x=.055,y=.98)
fig.tight_layout(rect=(0,0,1,.95),w_pad=2.5)
fig.savefig(HERE/'marked-corridor-before-after.png',dpi=180,facecolor='white')
