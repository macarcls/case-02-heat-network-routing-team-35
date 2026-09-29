"""Independent depth/cost validation of the two supplied output fixtures; no Java calls.

Run from any directory: python3 validation/verify_current_depth.py.
Requires pyproj and shapely. This checks fixed saved results, not search optimality.
"""
import json
import math
import re
import sys
from collections import defaultdict
from pathlib import Path

from pyproj import Transformer
from shapely.geometry import LineString, Point, shape
from shapely.ops import transform

ROOT = Path(__file__).resolve().parents[1]
PROJECT = Transformer.from_crs(4326, 32637, always_xy=True).transform
DN = [50,65,80,100,125,150,200,250,300,400,500,600,700,800,900,1000,1200,1400]
HEIGHT = [.125,.140,.160,.180,.225,.250,.315,.400,.450,.560,.710,.800,.900,1,1.1,1.2,1.425,1.6]
PRICE = [74023,78631,83530,89748,97275,105507,120275,135323,150022,190299,224137,264790,324298,325996,327693,418777,428074,683417]
RECON = [96180,109989,117582,133694,148030,152295,181766,202030,228707,271317,333884,372703,439571,489918,553607,606679,825692,978584]
# Extension, special factor, original top depth, obstacle height, appendix clearance.
RULES = {'gas_pipeline': (2,1.25,2.8,.4,.2), 'power_cable': (2,1.15,2.7,.2,.5),
         'heat_network': (2,1.05,3,None,.5), 'road': (3,1.6,0,0,1),
         'tram_tracks': (3,1.75,0,0,1.2)}
ERRORS = []
RESULTS = []


def require(ok, message):
    if not ok:
        ERRORS.append(message)


def kh(h):
    return 1 + .1 * max(0, h - 3)


def load(path):
    return json.loads(path.read_text())


def validate(source_name, output_name):
    data = load(ROOT / 'examples' / source_name)
    output = load(ROOT / 'validation' / (output_name + '.geojson'))
    report = load(ROOT / 'validation' / (output_name + '-report.json'))
    raw = {str(f['properties']['id']): (f['properties'], transform(PROJECT, shape(f['geometry']))) for f in data['features']}
    groups = defaultdict(list)
    for f in output['features']:
        groups[f['properties']['variant_id']].append(f)
    for variant, features in groups.items():
        prefix = output_name + '/' + variant
        checks = report['checks'][int(variant[1:]) - 1]['depth']
        props = {f['properties']['id']: f['properties'] for f in features}
        geometries = {f['properties']['id']: transform(PROJECT, shape(f['geometry'])) for f in features if f['geometry']}
        summary = next(p for p in props.values() if p['object_type'] == 'variant_summary')
        root_ids = {p['id'] for p in props.values() if p['object_type'] == 'tie_in'}
        expected_cost = base_cost = crossing_count = total_length = 0
        actual_low = actual_high = 3
        actual_slope = 0
        for profile in checks['profiles']:
            eid = profile['edgeId']
            pattern = re.compile(':' + re.escape(eid) + r':(\d+)$')
            segments = sorted((p for p in props.values() if p['object_type'] == 'heat_network' and pattern.search(p['id'])), key=lambda p: int(pattern.search(p['id'])[1]))
            require(bool(segments), prefix + '/' + eid + ': missing geometry')
            if not segments:
                continue
            coordinates = []
            intervals = []
            distance = 0
            for p in segments:
                g = geometries[p['id']]
                coordinates.extend(list(g.coords)[bool(coordinates):])
                intervals.append((distance, distance + g.length, p))
                distance += g.length
            axis = LineString(coordinates)
            total_length += axis.length
            require(abs(axis.length-profile['lengthM']) < .002, prefix + '/' + eid + ': profile length')

            def depth(x):
                for a, b, p in intervals:
                    if a - .002 <= x <= b + .002:
                        return p['depth_start'] + (p['depth_end']-p['depth_start']) * max(0,min(1,(x-a)/(b-a)))
                raise ValueError('Missing station ' + str(x))

            passages = []
            for rid, (p, g) in raw.items():
                typ = p.get('restriction_type') if p['object_type'] == 'restriction' else p['object_type']
                if typ not in RULES:
                    continue
                ext, factor, top, old_height, gap = RULES[typ]
                if old_height is None:
                    old_height = HEIGHT[DN.index(p['diameter'])]
                if checks['ruleProfile'] == 'protocol':
                    gap = max(.7, gap)
                intersection = axis.intersection(g)
                pieces = list(intersection.geoms) if hasattr(intersection, 'geoms') else [intersection]
                for piece in pieces:
                    if piece.is_empty:
                        continue
                    if typ in ('road','tram_tracks') and piece.length < 1e-7:
                        continue
                    if typ not in ('road','tram_tracks'):
                        require(piece.geom_type == 'Point', prefix + ': overlapping communication axes')
                    at = [axis.project(Point(c)) for c in piece.coords]
                    core_from, core_to = min(at), max(at)
                    if segments[0]['start_node_id'] in root_ids and core_to < .26 and typ == 'heat_network':
                        continue
                    a, b = core_from-ext, core_to+ext
                    require(a >= -.002 and b <= axis.length+.002, prefix + '/' + eid + ': truncated crossing plateau')
                    a, b = max(0,a), min(axis.length,b)
                    h = depth((a+b)/2)
                    if typ in ('road','tram_tracks'):
                        actual = h
                    else:
                        new_height = HEIGHT[DN.index(profile['diameter'])]
                        actual = max(top-h-new_height, h-top-old_height)
                    require(actual >= gap-1e-5, prefix + '/' + eid + '/' + rid + ': vertical clearance')
                    for x in [a,b] + [i[0] for i in intervals if a < i[0] < b]:
                        require(abs(depth(x)-h) < .001, prefix + '/' + eid + '/' + rid + ': non-flat plateau')
                    found = [r for r in checks['crossings'] if r['edgeId']==eid and str(r['existingObjectId'])==rid and r['fromM']<=a+.002 and r['toM']>=b-.002]
                    require(bool(found), prefix + ': crossing missing from report ' + rid)
                    passages.append((a,b,factor))
                    crossing_count += 1

            for a,b,p in intervals:
                label=prefix+'/'+p['id']; h0,h1=p['depth_start'],p['depth_end']; g=geometries[p['id']]
                actual_low=min(actual_low,h0,h1);actual_high=max(actual_high,h0,h1)
                actual_slope=max(actual_slope,abs(h1-h0)/(b-a))
                require(min(h0,h1)>=checks['allowedMinimumM']-1e-7 and max(h0,h1)<=checks['allowedMaximumM']+1e-7,label+': depth bounds')
                require(abs(h1-h0)/(b-a)<=.10001,label+': slope')
                require((h0-3)*(h1-3)>=-1e-7,label+': section not split at 3 m')
                require(abs(g.length-p['length'])<.002,label+': metric length')
                for node, coord, h in [(p['start_node_id'],g.coords[0],h0),(p['end_node_id'],g.coords[-1],h1)]:
                    expected=geometries.get(node,raw.get(node,(None,None))[1])
                    require(expected is not None and expected.distance(Point(coord))<.002,label+': endpoint reference')
                    if expected is not None:
                        z=expected.z if expected.has_z else -3
                        require(abs(z+h)<1e-7,label+': shared node depth')
                run=0;previous=None
                for coordinate in g.coords:
                    if previous is not None:run+=math.dist(previous[:2],coordinate[:2])
                    require(len(coordinate)==3 and abs(coordinate[2]+h0+(h1-h0)*run/g.length)<1e-5,label+': Z interpolation')
                    previous=coordinate
                cuts=sorted({a,b}|{x for passage in passages for x in passage[:2] if a<x<b})
                unit=PRICE[DN.index(p['diameter'])];cost=base=0
                for l,r in zip(cuts,cuts[1:]):
                    k=max([1]+[f for start,end,f in passages if start-.001<=(l+r)/2<=end+.001])
                    hl=h0+(h1-h0)*(l-a)/(b-a);hr=h0+(h1-h0)*(r-a)/(b-a)
                    base+=(r-l)*unit*k;cost+=(r-l)*unit*k*(kh(hl)+kh(hr))/2
                require(abs(cost-p['cost'])<3,label+': independent price')
                expected_cost+=cost;base_cost+=base
            for station in profile['stations']:
                require(abs(depth(station['distanceM'])-station['depthM'])<.001,prefix+': report profile differs from geometry')
        require(abs(actual_low-checks['minimumDepthM'])<.001,prefix+': minimum depth')
        require(abs(actual_high-checks['maximumDepthM'])<.001,prefix+': maximum depth')
        require(abs(actual_slope-checks['maximumSlope'])<.00001,prefix+': maximum slope')
        require(abs(expected_cost-summary['construction_cost'])<10,prefix+': construction total')
        require(abs(expected_cost-base_cost-checks['additionalDepthCost'])<10,prefix+': depth surcharge')
        require(abs(total_length-summary['new_network_length'])<.01,prefix+': total length')
        categories={'heat_network':'construction_cost','heat_chamber':'chamber_construction_cost','tie_in':'tie_in_cost','heat_network_reconstruction':'reconstruction_cost','heat_chamber_reconstruction':'chamber_reconstruction_cost'}
        for typ,key in categories.items():
            require(abs(sum(p['cost'] for p in props.values() if p['object_type']==typ)-summary[key])<.01,prefix+': subtotal '+key)
        for p in props.values():
            if p['object_type']=='heat_network_reconstruction':
                require(abs(geometries[p['id']].length*RECON[DN.index(p['required_diameter'])]-p['cost'])<3,prefix+': reconstruction incorrectly multiplied')
        assert 'unconnected_penalty' not in summary
        total=sum(summary[key] for key in categories.values())
        cw,lw=(.3,.7) if report['options']['rankingProfile']=='protocol' else (.7,.3)
        require(abs(total-summary['calculated_cost'])<.01,prefix+': grand total')
        require(abs(cw*total/25e6+lw*(summary['length']+summary['turn_penalty_m']+summary.get('facade_alignment_penalty_m',0))/100-summary['score'])<1e-7,prefix+': ranking')
        RESULTS.append({'fixture':output_name,'variant':variant,'crossings_checked':crossing_count,'new_length_m':total_length,'minimum_depth_m':actual_low,'maximum_depth_m':actual_high,'depth_surcharge_rub':expected_cost-base_cost})


if __name__ == '__main__':
    validate('depth-demo.geojson','nearest-turns-depth-demo')
    validate('supplied.geojson','supplied-nearest-turns')
    result={'passed':not ERRORS,'results':RESULTS,'errors':ERRORS}
    (ROOT/'validation/independent-current-depth-check.json').write_text(json.dumps(result,indent=2)+'\n')
    print(json.dumps(result))
    sys.exit(bool(ERRORS))
