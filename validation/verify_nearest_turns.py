"""Historical check for the fixed point-nearest wall rule through 1.9.1.

Version 1.9.2 uses a network-facing wall and this script must not be used to
validate its reports. See validation/walls-1.9.2/RESULTS.md instead.
Run old cases from any directory: python3 validation/verify_nearest_turns.py
Dependencies: requirements-check.txt in this directory. No Java code is used.
"""
import json
import math
from collections import defaultdict, deque
from pathlib import Path

from pyproj import Transformer
from shapely.geometry import Point, LineString, shape
from shapely.ops import transform, substring, unary_union

ROOT = Path(__file__).resolve().parents[1]
EPS = 0.0001
PROJECT = Transformer.from_crs(4326, 32637, always_xy=True).transform
HEIGHT = dict(zip(
    [50,65,80,100,125,150,200,250,300,400,500,600,700,800,900,1000,1200,1400],
    [.125,.140,.160,.180,.225,.250,.315,.400,.450,.560,.710,.800,.900,1,1.1,1.2,1.425,1.6]))


def check(output_name='supplied-nearest-turns'):
    source = json.loads((ROOT / 'examples/supplied.geojson').read_text())
    output = json.loads((ROOT / 'validation' / (output_name+'.geojson')).read_text())
    report = json.loads((ROOT / 'validation' / (output_name+'-report.json')).read_text())
    originals = {str(f['properties']['id']): f for f in source['features']}
    for change in report['scenarioChanges']:
        originals[str(change['id'])]['properties'].update(change)
    assignments = {x['sourceEntryId']: x for x in report['search']['entryAssignments']}
    assert report['search']['buildingEntryPolicy']=='nearest_exterior_wall_fixed'
    expected = {key for key,f in originals.items()
                if f['properties']['object_type'] == 'oks_connection_point'}
    buildings = {key: transform(PROJECT, shape(f['geometry'])) for key,f in originals.items()
                 if f['properties']['object_type'] in ('oks_existing','oks_future')
                 or f['properties'].get('restriction_type') == 'oks'}
    facade_walls=[]
    for building in buildings.values():
        polygons=list(building.geoms) if hasattr(building,'geoms') else [building]
        for polygon in polygons:
            ring=list(polygon.exterior.coords)
            for a,b in zip(ring,ring[1:]):
                side=LineString([a,b])
                if side.length>=30:
                    facade_walls.append((side,math.atan2(b[1]-a[1],b[0]-a[0])))
    def bearing(a,b):
        return math.atan2(b[1]-a[1],b[0]-a[0])
    def frame_gap(a,b):
        delta=abs(math.remainder(a-b,math.pi/2))
        return min(delta,math.pi/2-delta)
    def nearby_facade(run):
        if run.length<60:return None
        theta=bearing(run.coords[0],run.coords[-1])
        choices=[(side.distance(run),frame_gap(theta,axis)) for side,axis in facade_walls
                 if side.distance(run)<=40 and frame_gap(theta,axis)<=math.radians(6)]
        return min(choices) if choices else None
    def facade_adapter(a,b,point):
        p=Point(point)
        return any(side.distance(p)<=40 and
                   (frame_gap(a,axis)<=math.radians(.2) or frame_gap(b,axis)<=math.radians(.2))
                   for side,axis in facade_walls)
    by_variant = defaultdict(list)
    for feature in output['features']:
        by_variant[feature['properties']['variant_id']].append(feature)
    assert len(by_variant) == len(report['variants']) and 1 <= len(by_variant) <= 3
    assert len(expected) == 17
    summaries = []
    shapes = {}
    for i, (variant, features) in enumerate(sorted(by_variant.items())):
        refs = dict(originals)
        refs.update({f['properties']['id']: f for f in features})
        assert not any(f['properties']['object_type']=='oks_connection_point' for f in features), 'derived consumer point'
        pipes = [f for f in features if f['properties']['object_type']=='heat_network']
        ties = {f['properties']['id']: f for f in features if f['properties']['object_type']=='tie_in'}
        parent = {}
        children = defaultdict(list)
        geometry = {}
        edges = defaultdict(list)
        for feature in pipes:
            p = feature['properties']
            line = transform(PROJECT, shape(feature['geometry']))
            geometry[p['id']] = line
            assert line.is_simple and line.length > 0
            assert abs(line.length-p['length']) < .001
            for field, coord in (('start_node_id',line.coords[0]),('end_node_id',line.coords[-1])):
                point = transform(PROJECT, shape(refs[p[field]]['geometry']))
                assert point.distance(Point(coord[:2])) <= EPS, ('endpoint', p['id'])
            assert p['end_node_id'] not in parent, ('multiple parents', p['end_node_id'])
            parent[p['end_node_id']] = p['start_node_id']
            children[p['start_node_id']].append(p['end_node_id'])
            edges[p['id'].rsplit(':',1)[0]].append(feature)
        for index, first in enumerate(pipes):
            pa=first['properties']
            for second in pipes[index+1:]:
                pb=second['properties']
                hit=geometry[pa['id']].intersection(geometry[pb['id']])
                if hit.is_empty: continue
                assert hit.length<=EPS, ('overlapping pipes',pa['id'],pb['id'])
                shared={pa['start_node_id'],pa['end_node_id']} & {pb['start_node_id'],pb['end_node_id']}
                assert shared, ('crossing outside a node',pa['id'],pb['id'])
                common=[transform(PROJECT,shape(refs[node]['geometry'])) for node in shared]
                hits=list(hit.geoms) if hasattr(hit,'geoms') else [hit]
                for point in hits:
                    assert min(point.distance(node) for node in common)<=.01
        for tid,tie in ties.items():
            assert tid not in parent
            p = tie['properties']
            point = transform(PROJECT,shape(tie['geometry']))
            existing = transform(PROJECT,shape(originals[p['existing_object_id']]['geometry']))
            assert point.distance(existing) <= .2601
            assert p['connection_count'] == len(children[tid])
            assert p['cost'] == 5_000_000*len(children[tid])
        visited=set()
        queue=deque(ties)
        while queue:
            node=queue.popleft()
            assert node not in visited, ('cycle',node)
            visited.add(node)
            queue.extend(children[node])
        assert set(parent) <= visited, 'unrooted component'
        connected = set()
        for node in parent:
            p=refs[node]['properties']
            if p['object_type']!='oks_connection_point': continue
            original=str(p.get('source_entry_id',node))
            assert original in expected
            connected.add(original)
            assert node == original and node in expected
        assert connected == expected, (variant, 'missing', sorted(expected-connected))
        # Independently reconstruct each whole edge and permit only its terminal wall entry.
        indoor_total=0
        permitted=[]
        whole_edges={}
        for eid,parts in edges.items():
            parts.sort(key=lambda f:int(f['properties']['id'].rsplit(':',1)[1]))
            coords=[]
            for part in parts:
                segment=list(geometry[part['properties']['id']].coords)
                coords.extend(segment if not coords else segment[1:])
            line=LineString([c[:2] for c in coords])
            whole_edges[eid]=(line, parts[0]['properties']['start_node_id'], parts[-1]['properties']['end_node_id'])
            dn=parts[0]['properties']['diameter']
            width=2*HEIGHT[dn]+(.15 if dn<=150 else .25)
            clearance=5 if dn<500 else 7 if dn<=800 else 9
            terminal_id=parts[-1]['properties']['end_node_id']
            endpoint=transform(PROJECT,shape(originals[terminal_id]['geometry'])) if terminal_id in expected else None
            own=[(bid,body) for bid,body in buildings.items() if endpoint is not None and body.covers(endpoint)]
            assert len(own)<=1
            owner=own[0][0] if own else None
            inside_length=0
            for bid,body in buildings.items():
                checked=line
                solid=body.buffer(-EPS)
                if bid==owner and solid.covers(endpoint):
                    polygons=list(body.geoms) if hasattr(body,'geoms') else [body]
                    polygon=next(poly for poly in polygons if poly.covers(endpoint))
                    boundary=line.intersection(polygon.boundary)
                    hits=list(boundary.geoms) if hasattr(boundary,'geoms') else [boundary]
                    assert hits and all(g.geom_type=='Point' for g in hits), ('boundary overlaps',eid)
                    crossing=max(hits,key=lambda point:line.project(point))
                    last_leg=LineString([crossing.coords[0],endpoint.coords[0]])
                    assert body.buffer(EPS).covers(last_leg), ('entry through courtyard',eid)
                    assert line.intersection(solid).difference(last_leg.buffer(EPS*2)).is_empty, ('transit or interior bends',eid)
                    terminal_part=substring(line,line.project(crossing),line.length)
                    assert abs(terminal_part.length-last_leg.length)<EPS, ('non-straight entry',eid)
                    ring=list(polygon.exterior.coords)
                    walls=[LineString([a,b]) for a,b in zip(ring,ring[1:]) if Point(a).distance(Point(b))>EPS]
                    assigned=next(row for row in report['checks'][i]['permittedBuildingEntries'] if row['sourceEntryId']==terminal_id)
                    # A restored network may itself have namespaced edge IDs.
                    assert eid == f"tt:{variant}:" + assigned['edgeId']
                    assert len(assigned['allowedWalls'])==1
                    assert assigned['entryRule']=='nearest_exterior_wall_fixed'
                    nearest_distance=min(w.distance(endpoint) for w in walls)
                    nearest=[w for w in walls if abs(w.distance(endpoint)-nearest_distance)<1e-6]
                    nearest.sort(key=lambda w:(-w.length,tuple(sorted(w.coords))))
                    fixed=transform(PROJECT,LineString(assignments[terminal_id]['fixedWall']))
                    assert fixed.hausdorff_distance(nearest[0])<EPS, ('not nearest',terminal_id)
                    assert abs(assignments[terminal_id]['nearestWallDistanceM']-nearest_distance)<EPS
                    assert abs(assigned['geometricNearestWallDistanceM']-nearest_distance)<EPS
                    window=transform(PROJECT,LineString(assigned['allowedWalls'][0]))
                    assert window.distance(crossing)<=EPS, ('wrong assigned wall',eid)
                    assert window.difference(fixed.buffer(EPS)).is_empty, ('wall moved',eid)
                    candidates=[wall for wall in walls if window.difference(wall.buffer(EPS)).is_empty]
                    assert candidates, ('window not on original facade',eid)
                    original_wall=min(candidates,key=lambda wall:wall.distance(crossing))
                    assert min(Point(c).distance(crossing) for c in original_wall.coords)>=width/2-EPS, ('corner clearance',eid)
                    assert abs(window.distance(endpoint)-assigned['selectedWallDistanceM'])<EPS
                    inside_length=last_leg.length
                    indoor_total+=inside_length
                    permitted.append(dict(sourceEntryId=terminal_id,buildingId=bid,indoorLengthM=inside_length))
                    assert abs(geometry[parts[-1]['properties']['id']].coords[-1][2]+3)<EPS
                else:
                    assert not line.intersects(solid), ('unauthorized building intersection',variant,eid,bid)
                if bid==owner:
                    checked=substring(line,0,max(0,line.length-(inside_length+2*(clearance+width/2)+.05)))
                if checked.length>EPS:
                    assert checked.distance(body)>=clearance+width/2-EPS, ('clearance',variant,eid,bid)
        assert len(permitted)==17
        reported={item['sourceEntryId']:item for item in report['checks'][i]['permittedBuildingEntries']}
        assert set(reported)==expected
        for item in permitted:
            assert item['buildingId']==reported[item['sourceEntryId']]['buildingId']
            assert abs(item['indoorLengthM']-reported[item['sourceEntryId']]['indoorLengthM'])<EPS
        assert abs(indoor_total-report['variants'][i]['indoor_connection_length'])<EPS
        # Recompute bends from exported coordinates, including turns at chambers.
        counts={45:0,90:0,135:0}
        adapters=[]
        connection_count=0
        incoming={end:line for line,start,end in whole_edges.values()}
        def add_angle(angle,a,b,point,eid,location):
            if angle<2e-4: return False
            degrees=min(counts,key=lambda x:abs(angle-math.radians(x)))
            if abs(angle-math.radians(degrees))<2e-4:
                counts[degrees]+=1
            else:
                target=min((0,45,90,135),key=lambda x:abs(angle-math.radians(x)))
                assert abs(angle-math.radians(target))<math.radians(4), ('nonstandard turn',eid,angle)
                assert facade_adapter(a,b,point), ('adapter far from facade',eid,point)
                adapters.append((eid.split(':')[-1],location,round(min(math.degrees(angle),180-math.degrees(angle)),2),target))
            return True
        for eid,(line,start,end) in whole_edges.items():
            coords=list(line.coords)
            headings=[bearing(a,b) for a,b in zip(coords,coords[1:]) if Point(a).distance(Point(b))>1e-7]
            for j,(a,b) in enumerate(zip(headings,headings[1:])):
                add_angle(math.acos(max(-1,min(1,math.cos(a-b)))),a,b,coords[j+1],eid,'pipe_bend')
            axis=None
            if start in ties:
                original=originals[str(ties[start]['properties']['existing_object_id'])]
                seen=set()
                while original['properties']['object_type']=='heat_chamber':
                    oid=str(original['properties']['id'])
                    assert oid not in seen
                    seen.add(oid)
                    original=originals[str(original['properties']['upstream_object_id'])]
                existing=transform(PROJECT,shape(original['geometry']))
                at=existing.project(Point(coords[0]))
                a=existing.interpolate(max(0,at-.1));b=existing.interpolate(min(existing.length,at+.1))
                if a.distance(b)>1e-8: axis=bearing(a.coords[0],b.coords[0])
            elif start in incoming:
                before=list(incoming[start].coords)
                axis=bearing(before[-2],before[-1])
            if axis is not None:
                angle=math.acos(min(1,abs(math.cos(headings[0]-axis))))
                if add_angle(angle,headings[0],axis,coords[0],eid,'junction'): connection_count+=1
        v=report['variants'][i]
        penalty=25*counts[90]+50*counts[45]+75*counts[135]+sum(
            {0:35,45:60,90:35,135:85}[target] for _,_,_,target in adapters)
        for angle,count in counts.items():
            assert count==v[f'turn_{angle}_count'], ('turn count',variant,angle,count,v)
        assert v['other_turn_count']==len(adapters)
        assert v.get('facade_adapter_count',0)==len(adapters)
        reported=report['checks'][i].get('facadeAdapters',[])
        assert sorted((row['edgeId'],row['location']) for row in reported)==sorted(
            (eid,location) for eid,location,_,_ in adapters)
        assert report['checks'][i].get('facadeAdaptersNeedEngineeringReview',False)==bool(adapters)
        assert connection_count==v['connection_turn_count']
        assert abs(penalty-v['turn_penalty_m'])<1e-7
        assert abs(v['base_score']-(.7*v['calculated_cost']/25e6+.3*v['length']/100))<1e-8
        drift=0
        for line,_,_ in whole_edges.values():
            for a,b in zip(line.coords,line.coords[1:]):
                run=LineString([a,b]); wall=nearby_facade(run)
                if wall: drift+=16*max(0,run.length*math.sin(wall[1])-1)
        assert abs(drift-v.get('facade_alignment_penalty_m',0))<.002, ('facade drift',drift,v)
        assert abs(v['score']-(v['base_score']+.3*(penalty+drift)/100))<1e-5
        assert abs(v['ranking_length']-v['length']-penalty-drift)<.002
        assert abs(v['rating']-100*report['variants'][0]['score']/v['score'])<1e-7
        assert report['checks'][i]['turnPenalty']['includedInMonetaryCost'] is False
        total_cost=sum(f['properties'].get('cost',0) for f in features)
        shapes[variant]=unary_union(list(geometry.values()))
        v=report['variants'][i]
        assert v['connection_complete'] and v['connected_connection_count']==len(expected)
        assert not v['unconnected_entry_ids'] and not v['unconnected_oks_ids']
        assert 'unconnected_penalty' not in v
        assert abs(total_cost-v['calculated_cost'])<.01, ('cost mismatch',total_cost,v['calculated_cost'])
        assert abs(sum(g.length for g in geometry.values())-v['new_network_length'])<.001
        assert abs(2*v['new_network_length']-v['new_pipe_material_length'])<.0001
        assert len(report['checks'][i]['routeExplanations'])==len(edges)
        summaries.append(dict(variant=variant,connected=len(connected),required=len(expected),
                              buildings=len(buildings),newSections=len(pipes),ties=len(ties),
                              noUnauthorizedBuildingIntersections=True,assignedWallEntries=len(permitted),
                              indoorLengthM=indoor_total,noDerivedConsumerPoints=True,clearancesValid=True,
                              rootedAcyclicGraph=True,endpointReferencesValid=True,costSumValid=True,
                              fixedNearestWall=True,turnCounts=counts,connectionTurns=connection_count,
                              turnPenaltyM=penalty,facadeAlignmentPenaltyM=drift,
                              facadeAdapters=len(adapters),rankingFormulaValid=True,
                              penaltyExcludedFromCosts=True))
    differences = []
    keys=list(shapes)
    for i,a in enumerate(keys):
        for b in keys[i+1:]:
            different=shapes[a].difference(shapes[b].buffer(2)).length + shapes[b].difference(shapes[a].buffer(2)).length
            assert different > 1, ('geometrically duplicate alternatives',a,b)
            differences.append(dict(first=a,second=b,nonSharedLengthBeyond2m=different))
    from verify_current_depth import validate, ERRORS, RESULTS
    ERRORS.clear(); RESULTS.clear()
    validate('supplied.geojson',output_name)
    assert not ERRORS, ERRORS
    return dict(passed=True,variants=summaries,diversity=differences,
                depth=RESULTS,toleranceM=EPS)


if __name__ == '__main__':
    import argparse
    parser=argparse.ArgumentParser()
    parser.add_argument('--output-name',default='supplied-nearest-turns')
    parser.add_argument('--report-name',default='independent-nearest-turns-check.json')
    args=parser.parse_args()
    result=check(args.output_name)
    (ROOT/'validation'/args.report_name).write_text(json.dumps(result,indent=2)+'\n')
    print(json.dumps(result))
