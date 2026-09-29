"""Independent arithmetic, topology, typed IDs, and nearest-wall checks for GeoJSON.

This does not independently certify obstacle clearances or vertical profiles.
"""
import json
import math
import sys
from collections import defaultdict
from decimal import Decimal, ROUND_HALF_UP
from pathlib import Path

DN = (50, 65, 80, 100, 125, 150, 200, 250, 300, 400, 500, 600, 700, 800, 900, 1000, 1200, 1400)
CAP = dict(zip(DN, (3.5, 8.3, 13.2, 22.3, 40.2, 65.1, 152.3, 274.9, 437.4, 943.1, 1663.4, 2627.7, 3735.1, 5296.8, 7165, 9391.8, 15012.8, 22501.9)))
LIMIT = dict(zip(DN, (181, 245, 327, 419, 554, 696, 1042, 1379, 1718, 2477, 3245, 4037, 4775, 5644, 6518, 7419, 9288, 11276)))
PRICE = dict(zip(DN, (74023, 78631, 83530, 89748, 97275, 105507, 120275, 135323, 150022, 190299, 224137, 264790, 324298, 325996, 327693, 418777, 428074, 683417)))


def close(a, b, tolerance=.03):
    assert math.isclose(a, b, abs_tol=tolerance), (a, b)


def near_wall(coords, terminal, buildings):
    latitude = terminal[1]
    def metric(p):
        return (p[0] * 111320 * math.cos(math.radians(latitude)), p[1] * 111320)
    point = metric(terminal)
    for feature in buildings:
        geometry = feature['geometry']
        polygons = [geometry['coordinates']] if geometry['type'] == 'Polygon' else geometry['coordinates']
        for polygon in polygons:
            ring = [metric(v) for v in polygon[0]]
            inside = False
            for a, b in zip(ring, ring[1:]):
                if (a[1] > point[1]) != (b[1] > point[1]):
                    if point[0] < a[0] + (point[1]-a[1]) * (b[0]-a[0]) / (b[1]-a[1]):
                        inside = not inside
            if not inside:
                continue
            def distance(a, b, p=point):
                dx, dy = b[0]-a[0], b[1]-a[1]
                t = max(0, min(1, ((p[0]-a[0])*dx+(p[1]-a[1])*dy) / (dx*dx+dy*dy)))
                return math.hypot(p[0]-a[0]-t*dx, p[1]-a[1]-t*dy)
            minimum = min(distance(a, b) for a, b in zip(ring, ring[1:]))
            nearest_sides = [(a, b) for a, b in zip(ring, ring[1:])
                             if distance(a, b) <= minimum + .06]
            start, end = map(metric, coords[-2:])
            ux, uy = end[0]-start[0], end[1]-start[1]
            hits = []
            for a, b in zip(ring, ring[1:]):
                vx, vy = b[0]-a[0], b[1]-a[1]
                den = ux*vy - uy*vx
                if abs(den) < 1e-9:
                    continue
                cx, cy = a[0]-start[0], a[1]-start[1]
                t, u = (cx*vy-cy*vx)/den, (cx*uy-cy*ux)/den
                if -1e-7 <= t <= 1+1e-7 and -1e-7 <= u <= 1+1e-7:
                    hits.append((start[0]+t*ux, start[1]+t*uy))
            assert hits, 'terminal segment does not cross exterior wall'
            assert any(distance(a, b, hit) < .06
                       for hit in hits for a, b in nearest_sides), 'farther wall'
            return
    assert not buildings, 'terminal point is outside all building footprints'


def check(directory):
    report = json.loads((directory/'benchmark-report.json').read_text())
    result = json.loads((directory/'benchmark-result.geojson').read_text())
    source = Path(report['input'])
    if not source.exists():
        source = next((base/source for base in directory.parents if (base/source).exists()), source)
    original = json.loads(source.read_text())['features']
    by_id = {f['properties']['id']: f for f in original}
    demands = {k:f for k,f in by_id.items() if f['properties']['object_type'] == 'oks_connection_point'}
    chambers = {k:f for k,f in by_id.items() if f['properties']['object_type'] == 'heat_chamber'}
    old_pipes = [f for f in original if f['properties']['object_type'] == 'heat_network']
    buildings = [f for f in original if f['properties'].get('restriction_type') == 'oks']
    assert result['type'] == 'FeatureCollection' and report['options']['rankingProfile'] == 'contest'
    assert 1 <= len(report['variants']) <= 3
    all_ids = set()
    for item in result['features']:
        p = item['properties']
        assert p['object_type'] in ('heat_network', 'heat_chamber', 'technical_node', 'variant_summary')
        assert p['id'] not in all_ids
        all_ids.add(p['id'])
    for index, summary in enumerate(report['variants'], 1):
        items = [f for f in result['features'] if f['properties']['variant_id'] == f'v{index}']
        pipes = [f for f in items if f['properties']['object_type'] == 'heat_network']
        new_nodes = {f['properties']['id']:f for f in items if f['properties']['object_type'] in ('heat_chamber', 'technical_node')}
        new_chambers = [f for f in items if f['properties']['object_type'] == 'heat_chamber']
        successors = defaultdict(list)
        indegree = defaultdict(int)
        for f in pipes:
            p, coordinates = f['properties'], f['geometry']['coordinates']
            a, b = p['start_node_id'], p['end_node_id']
            assert a in new_nodes or a in chambers, ('unknown root', a)
            assert b in new_nodes or b in demands, ('unknown terminal', b)
            beginning = by_id[a] if a in chambers else new_nodes[a]
            ending = by_id[b] if b in demands else new_nodes[b]
            assert math.dist(coordinates[0][:2], beginning['geometry']['coordinates'][:2]) < 1e-7
            assert math.dist(coordinates[-1][:2], ending['geometry']['coordinates'][:2]) < 1e-7
            assert p['diameter'] in CAP and p['flow_tph'] <= CAP[p['diameter']] + 1e-7
            assert p['cost'] >= p['length'] * PRICE[p['diameter']] - .03
            assert p['laying_method'] in ('base', 'special')
            if b in demands:
                near_wall(coordinates, ending['geometry']['coordinates'], buildings)
            successors[a].append(p)
            indegree[b] += 1
        assert all(v == 1 for v in indegree.values())
        roots = set(successors) - set(indegree)
        assert all(r in chambers or new_nodes[r]['properties']['object_type'] == 'heat_chamber' for r in roots)
        for root in roots - set(chambers):
            lon, lat = new_nodes[root]['geometry']['coordinates'][:2]
            scale = 111320*math.cos(math.radians(lat))
            def meters(p):
                return (p[0]*scale, p[1]*111320)
            point = meters((lon, lat))
            def near_pipe(f):
                coords = [meters(p) for p in f['geometry']['coordinates']]
                for a, b in zip(coords, coords[1:]):
                    dx, dy = b[0]-a[0], b[1]-a[1]
                    t = max(0, min(1, ((point[0]-a[0])*dx+(point[1]-a[1])*dy)/(dx*dx+dy*dy)))
                    if math.hypot(point[0]-a[0]-t*dx, point[1]-a[1]-t*dy) < .3:
                        return True
                return False
            assert any(near_pipe(f) for f in old_pipes), 'new root chamber is not on existing pipe'
        for chamber in new_chambers:
            p = chamber['properties']
            required = max((edge['properties']['diameter'] for edge in pipes
                            if edge['properties']['start_node_id'] == p['id']
                            or edge['properties']['end_node_id'] == p['id']), default=50)
            assert p['diameter'] in DN and p['diameter'] >= required, ('chamber DN below its branches', p['id'])
            expected = 3e6 if p['diameter'] <= 200 else 5e6 if p['diameter'] <= 500 \
                else 8e6 if p['diameter'] <= 1000 else 12e6
            close(p['cost'], expected)
        visited = set()
        def walk(node, previous=None, length=0):
            assert node not in visited, ('cycle', node)
            visited.add(node)
            branches = successors[node]
            for edge in branches:
                diameter = edge['diameter']
                assert previous is None or previous >= diameter
                continuous = length + edge['length'] if previous == diameter else edge['length']
                assert continuous <= LIMIT[diameter] + .02
                walk(edge['end_node_id'], diameter, continuous)
            if branches and node in indegree:
                parent = next(f['properties'] for f in pipes if f['properties']['end_node_id'] == node)
                close(parent['flow_tph'], sum(f['flow_tph'] for f in branches), 1e-5)
        for root in roots:
            walk(root)
        assert visited == roots | set(indegree)
        missing = set(demands) - visited
        assert set(summary['unconnected_oks_ids']) == missing
        assert summary['connected_connection_count'] == len(demands)-len(missing)
        ties = sum(len(successors[c]) for c in chambers)
        close(summary['existing_chamber_tie_in_count'], ties)
        close(summary['existing_chamber_tie_in_cost'], 5e6*ties)
        chamber_cost = sum(c['properties']['cost'] for c in new_chambers)
        length = sum(p['properties']['length'] for p in pipes)
        construction = sum(p['properties']['cost'] for p in pipes) + chamber_cost + 5e6*ties
        penalty = sum(100e6 + 500000*demands[i]['properties']['flow_tph'] for i in missing)
        close(summary['chamber_construction_cost'], chamber_cost)
        close(summary['new_network_length'], length)
        close(summary['construction_cost'], construction)
        close(summary['unconnected_penalty'], penalty)
        close(summary['calculated_cost'], construction + penalty)
        exact = .7*(construction+penalty)/25e6 + .3*length/100
        rounded = float(Decimal(str(exact)).quantize(Decimal('.0001'), ROUND_HALF_UP))
        close(summary['score'], rounded, 1e-4)
        print(f'v{index}: {len(demands)-len(missing)}/{len(demands)}, score={rounded}, construction={round(construction)}')
    ranked = sorted(report['variants'], key=lambda v: v['score_raw'])
    assert [v['rank'] for v in ranked] == list(range(1, len(ranked)+1))


if __name__ == '__main__':
    check(Path(sys.argv[1]))
