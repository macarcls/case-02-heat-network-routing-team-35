"""Independent standard-library checks on the reported and exported networks."""
import json
import math
from pathlib import Path

ROOT = Path(__file__).resolve().parent
report = json.loads((ROOT / 'supplied-objectives-report.json').read_text())
features = json.loads((ROOT / 'supplied-objectives.geojson').read_text())['features']
variants, checks = report['variants'], report['checks']
assert 1 <= len(variants) <= 3
assert variants[0]['variant_role'] == 'balanced'


def xy(point):
    lon, lat = map(math.radians, point[:2])
    a, e2, k = 6378137., .0066943799901413165, .9996
    ep2 = e2 / (1 - e2)
    sin, cos = math.sin(lat), math.cos(lat)
    n = a / math.sqrt(1 - e2 * sin * sin)
    t, c, aa = math.tan(lat)**2, ep2 * cos * cos, cos * (lon-math.radians(39))
    m = a * ((1-e2/4-3*e2**2/64-5*e2**3/256)*lat
             -(3*e2/8+3*e2**2/32+45*e2**3/1024)*math.sin(2*lat)
             +(15*e2**2/256+45*e2**3/1024)*math.sin(4*lat)
             -35*e2**3/3072*math.sin(6*lat))
    return (500000 + k*n*(aa+(1-t+c)*aa**3/6+(5-18*t+t*t+72*c-58*ep2)*aa**5/120),
            k*(m+n*math.tan(lat)*(aa**2/2+(5-t+9*c+4*c*c)*aa**4/24
                                  +(61-58*t+t*t+600*c-330*ep2)*aa**6/720)))


def dist(a, b):
    return math.hypot(a[0]-b[0], a[1]-b[1])


def route(i):
    lines, length = [], 0.
    for f in features:
        p = f['properties']
        if p.get('variant_id') != f'v{i+1}' or p.get('object_type') != 'heat_network':
            continue
        length += p['length']
        g = f['geometry']
        for line in ([g['coordinates']] if g['type'] == 'LineString' else g['coordinates']):
            lines.append(list(map(xy, line)))
    assert lines
    return lines, length


def point_segment(p, a, b):
    dx, dy = b[0]-a[0], b[1]-a[1]
    den = dx*dx+dy*dy
    t = max(0, min(1, ((p[0]-a[0])*dx+(p[1]-a[1])*dy)/den)) if den else 0
    return dist(p, (a[0]+t*dx, a[1]+t*dy))


def unmatched(lines, other):
    segments = [(a, b) for line in other for a, b in zip(line, line[1:])]
    outside = total = 0.
    for line in lines:
        for a, b in zip(line, line[1:]):
            length = dist(a, b)
            steps = max(1, math.ceil(length/12))
            for k in range(steps):
                p = (a[0]+(b[0]-a[0])*(k+.5)/steps,
                     a[1]+(b[1]-a[1])*(k+.5)/steps)
                if min(point_segment(p, x, y) for x, y in segments) > 6:
                    outside += length/steps
            total += length
    return outside/total


routes = [route(i) for i in range(len(variants))]
for i, (v, c) in enumerate(zip(variants, checks)):
    assert v['connected_connection_count'] == v['required_connection_count'] == 17
    assert v['tie_in_count'] == 1
    assert c['allConnectionsConnected'] and c['spatialRulesValid']
    assert c['buildingEntriesValid'] and c['topologyValid']
    assert c['depth']['nodeContinuityValid'] and c['depth']['plateausValid']
    assert v['soil_properties_available'] is False
    assert abs(v['score']-v['balanced_score']) < 1e-7
    assert abs(routes[i][1]-v['new_network_length']) < .02
    if i:
        differences = [max(unmatched(routes[i][0], routes[j][0]),
                           unmatched(routes[j][0], routes[i][0])) for j in range(i)]
        assert all(x >= .06 for x in differences), differences
        print('Route differences:', [round(x, 3) for x in differences])
    if v['variant_role'] == 'earthworks':
        assert v['earthwork_index'] < variants[0]['earthwork_index']*.995
    elif v['variant_role'] == 'installation':
        assert all(v['installation_index'] < earlier['installation_index']*.995
                   for earlier in variants[:i])
    print(i+1, v['variant_role'], round(v['new_network_length'], 2),
          round(v['earthwork_index'], 2), round(v['installation_index'], 2))
assert len({v['variant_role'] for v in variants}) == len(variants)
if len(variants) == 3 and report['search']['variantSelection'].get('earthworkTradeoffFound'):
    assert variants[1]['earthwork_index'] < variants[2]['earthwork_index']*.995
