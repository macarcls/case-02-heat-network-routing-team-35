package ru.teplotrassa.engine;

import java.util.*;
import java.util.function.*;
import org.locationtech.jts.geom.*;
import ru.teplotrassa.data.*;

/**
 * Finite local search over junction positions and adjacent branch assignments. Consumer endpoints,
 * tie-ins and assigned entrance walls are never moved. Local estimates only order proposals; only
 * full-network evaluation can accept one.
 */
public final class JunctionOptimizer {
  private final FeatureStore store;
  private final Planner.Options options;
  private final BooleanSupplier stop;
  private final Function<Network, Evaluation> evaluate;
  private final BiConsumer<Integer, String> progress;
  public final Map<String, Object> diagnostics = new LinkedHashMap<>();
  private final List<Map<String, Object>> trials = new ArrayList<>();
  private int accepted, checked, routeChecks, cacheHits;
  private int boundPruned;

  public JunctionOptimizer(
      FeatureStore store,
      Planner.Options options,
      BooleanSupplier stop,
      Function<Network, Evaluation> evaluate,
      BiConsumer<Integer, String> progress) {
    this.store = store;
    this.options = options;
    this.stop = stop;
    this.evaluate = evaluate;
    this.progress = progress;
  }

  public Evaluation improve(Evaluation initial, Consumer<Evaluation> save) {
    long started = System.nanoTime();
    Evaluation best = initial;
    diagnostics.put("method", "joint_positions_and_adjacent_branch_exchange");
    diagnostics.put("beforeScore", initial.score);
    diagnostics.put("beforeLengthM", initial.summary.get("new_network_length"));
    diagnostics.put("trials", trials);
    diagnostics.put("globalOptimalityProven", false);
    for (int pass = 0; pass < options.treeRepairPasses && !stop.getAsBoolean(); pass++) {
      int changes = accepted;
      // Singles first, then adjacent pairs: the latter can escape a single-junction minimum.
      for (boolean pairs : new boolean[] {false, true}) {
        List<List<String>> patches = patches(best.network, pairs);
        for (int item = 0; item < Math.min(options.treeRepairCandidates, patches.size()); item++) {
          List<String> patch =
              patches.get((pass * options.treeRepairCandidates + item) % patches.size());
          if (stop.getAsBoolean()) break;
          if (!best.network.nodes.keySet().containsAll(patch)) continue;
          progress.accept(
              89,
              "Перестройка " + (pairs ? "пары развилок" : "развилки") + ": проход " + (pass + 1));
          int beforeChecks = checked;
          Evaluation next = improvePatch(best, patch);
          boolean changed = next.score < best.score - 1e-8;
          Map<String, Object> row = new LinkedHashMap<>();
          row.put("junctionIds", patch);
          row.put("pass", pass + 1);
          row.put("candidateCount", checked - beforeChecks);
          row.put("beforeScore", best.score);
          row.put("afterScore", next.score);
          row.put(
              "savedLengthM",
              (double) best.summary.get("new_network_length")
                  - (double) next.summary.get("new_network_length"));
          row.put("accepted", changed);
          if (changed) {
            List<Map<String, Object>> moved = new ArrayList<>();
            for (String id : patch) {
              Coordinate from = Geo.ll(best.network.nodes.get(id).point);
              Network.Node after = next.network.nodes.get(id);
              Map<String, Object> movement = new LinkedHashMap<>();
              movement.put("nodeId", id);
              movement.put("from", new double[] {from.x, from.y});
              if (after == null) movement.put("merged", true);
              else {
                Coordinate to = Geo.ll(after.point);
                movement.put("to", new double[] {to.x, to.y});
              }
              moved.add(movement);
            }
            row.put("movements", moved);
            row.put("removedChambers", best.network.nodes.size() - next.network.nodes.size());
          }
          trials.add(row);
          if (changed) {
            best = next;
            accepted++;
            save.accept(best);
          }
        }
      }
      diagnostics.put("completedPasses", pass + 1);
      if (accepted == changes
          && (pass + 1) * options.treeRepairCandidates
              >= Math.max(
                  patches(best.network, false).size(), patches(best.network, true).size())) {
        diagnostics.put("stopReason", "no_improving_junction_patch");
        break;
      }
    }
    diagnostics.putIfAbsent(
        "stopReason", stop.getAsBoolean() ? "cancelled" : "configured_passes_completed");
    diagnostics.put("acceptedPatches", accepted);
    diagnostics.put("evaluatedNetworks", checked);
    diagnostics.put("lowerBoundPruned", boundPruned);
    diagnostics.put("checkedRoutes", routeChecks);
    diagnostics.put("routeCacheHits", cacheHits);
    diagnostics.put("afterScore", best.score);
    diagnostics.put("afterLengthM", best.summary.get("new_network_length"));
    diagnostics.put("elapsedMs", (System.nanoTime() - started) / 1000000);
    return best;
  }

  private static boolean movable(Network n, String id) {
    Network.Node v = n.nodes.get(id);
    return v != null
        && v.type.equals("chamber")
        && v.entryId == null
        && v.existingId == null
        && v.demand == 0
        && n.incident(id).size() >= 3;
  }

  private List<List<String>> patches(Network n, boolean pairs) {
    List<List<String>> out = new ArrayList<>();
    if (pairs) {
      for (Network.Edge e : n.edges.values())
        if (movable(n, e.start) && movable(n, e.end)) out.add(List.of(e.start, e.end));
    } else {
      for (String id : n.nodes.keySet()) if (movable(n, id)) out.add(List.of(id));
    }
    out.sort(
        Comparator.comparingDouble(
                (List<String> ids) -> {
                  double value = 0;
                  for (Network.Edge e : n.edges.values())
                    if (ids.contains(e.start) || ids.contains(e.end))
                      value +=
                          e.geometry.getLength() + RoutingQuality.bends(e.geometry).equivalentM;
                  return -value;
                })
            .thenComparing(Object::toString));
    return out;
  }

  private static final class Proposal {
    final Map<String, Coordinate> points;
    final Map<String, String> parents;
    final double estimate;

    Proposal(Map<String, Coordinate> p, Map<String, String> parents, double estimate) {
      this.points = p;
      this.parents = parents;
      this.estimate = estimate;
    }
  }

  private Evaluation improvePatch(Evaluation original, List<String> ids) {
    Network n = original.network;
    if (ids.size() == 2
        && n.edges.values().stream()
            .noneMatch(e -> e.start.equals(ids.get(0)) && e.end.equals(ids.get(1))))
      return original;
    List<Network.Edge> edges = new ArrayList<>();
    Envelope area = new Envelope();
    for (Network.Edge e : n.edges.values())
      if (ids.contains(e.start) || ids.contains(e.end)) {
        edges.add(e);
        area.expandToInclude(e.geometry.getEnvelopeInternal());
      }
    Network.Edge incoming =
        edges.stream().filter(e -> !ids.contains(e.start)).findFirst().orElse(null);
    if (incoming == null) return original;
    Coordinate[] stem = incoming.geometry.getCoordinates();
    double axis = Math.atan2(stem[1].y - stem[0].y, stem[1].x - stem[0].x);
    List<Coordinate> pool = positions(n, edges, ids, axis);
    for (Coordinate point : pool) area.expandToInclude(point);
    SpatialRules spatial = new SpatialRules(store.near(SpatialRules.expand(area, 30)));
    List<Map<String, String>> assignments = new ArrayList<>();
    assignments.add(Map.of());
    if (ids.size() == 2) {
      // Exchange which downstream branch shares the second junction's stem.
      List<Network.Edge> outer = new ArrayList<>();
      for (Network.Edge e : edges) if (ids.contains(e.start) && !ids.contains(e.end)) outer.add(e);
      if (outer.size() >= 3 && outer.size() <= 5)
        for (int mask = 1; mask < (1 << outer.size()) - 1; mask++) {
          int second = Integer.bitCount(mask), first = outer.size() - second;
          if (first > 2 || second < 2 || second > 3) continue;
          Map<String, String> parents = new LinkedHashMap<>();
          boolean changed = false;
          for (int j = 0; j < outer.size(); j++) {
            String parent = ids.get((mask & (1 << j)) != 0 ? 1 : 0);
            parents.put(outer.get(j).id, parent);
            changed |= !parent.equals(outer.get(j).start);
          }
          if (changed) assignments.add(parents);
        }
    }
    Map<String, List<LineString>> cache = new HashMap<>();
    Evaluation best = original;
    for (Map<String, String> parents : assignments) {
      Network assigned = n.copy();
      for (Network.Edge e : edges)
        assigned.edges.put(
            e.id, new Network.Edge(e.id, parents.getOrDefault(e.id, e.start), e.end, e.geometry));
      assigned.flows();
      List<List<Coordinate>> choices = new ArrayList<>();
      for (String id : ids) {
        int clearanceDn = assigned.incident(id).stream().mapToInt(e -> e.dn).max().orElse(100);
        List<Coordinate> sorted = new ArrayList<>();
        for (Coordinate point : pool)
          if (spatial.targetClear(point, clearanceDn, null)) sorted.add(point);
        sorted.sort(
            Comparator.comparingDouble((Coordinate p) -> estimate(n, edges, Map.of(id, p), parents))
                .thenComparing(Geo::key));
        Map<Coordinate, Double> costs = new IdentityHashMap<>();
        for (Coordinate point : sorted.subList(0, Math.min(64, sorted.size()))) {
          double cost = 0;
          for (Network.Edge edge : edges) {
            Network.Edge assignedEdge = assigned.edges.get(edge.id);
            if (!assignedEdge.start.equals(id) && !assignedEdge.end.equals(id)) continue;
            if (ids.contains(assignedEdge.start) && ids.contains(assignedEdge.end)) continue;
            Network.Node a = assigned.nodes.get(assignedEdge.start).copy();
            Network.Node b = assigned.nodes.get(assignedEdge.end).copy();
            if (a.id.equals(id)) a.point.setCoordinate(point);
            else b.point.setCoordinate(point);
            List<LineString> paths = cachedPaths(edge, a, b, assignedEdge.dn, axis, spatial, cache);
            if (paths.isEmpty()) {
              cost = Double.POSITIVE_INFINITY;
              break;
            }
            cost += paths.get(0).getLength() + RoutingQuality.bends(paths.get(0)).equivalentM;
          }
          if (Double.isFinite(cost)) costs.put(point, cost);
        }
        sorted.removeIf(point -> !costs.containsKey(point));
        sorted.sort(
            Comparator.comparingDouble((Coordinate p) -> costs.get(p)).thenComparing(Geo::key));
        List<Coordinate> selected = new ArrayList<>(sorted.subList(0, Math.min(20, sorted.size())));
        Coordinate old = n.nodes.get(id).point;
        if (selected.stream().noneMatch(p -> p.distance(old) < .001)) selected.add(old);
        choices.add(selected);
      }
      List<Proposal> proposals = new ArrayList<>();
      for (Coordinate a : choices.get(0)) {
        if (ids.size() == 1) {
          Map<String, Coordinate> points = Map.of(ids.get(0), a);
          proposals.add(new Proposal(points, parents, estimate(n, edges, points, parents)));
        } else
          for (Coordinate b : choices.get(1)) {
            if (a.distance(b) < 1) continue;
            Map<String, Coordinate> points = Map.of(ids.get(0), a, ids.get(1), b);
            proposals.add(new Proposal(points, parents, estimate(n, edges, points, parents)));
          }
      }
      proposals.sort(Comparator.comparingDouble(p -> p.estimate));
      for (Proposal proposal : proposals.subList(0, Math.min(40, proposals.size()))) {
        if (stop.getAsBoolean()) return best;
        Network trial = n.copy();
        for (Map.Entry<String, Coordinate> p : proposal.points.entrySet())
          trial.nodes.get(p.getKey()).point.setCoordinate(p.getValue());
        for (Network.Edge e : edges)
          trial.edges.put(
              e.id, new Network.Edge(e.id, parents.getOrDefault(e.id, e.start), e.end, e.geometry));
        trial.flows();
        List<List<LineString>> routes = new ArrayList<>();
        boolean possible = true;
        for (Network.Edge e : edges) {
          Network.Edge replacement = trial.edges.get(e.id);
          Network.Node a = trial.nodes.get(replacement.start), b = trial.nodes.get(e.end);
          List<LineString> paths = cachedPaths(e, a, b, replacement.dn, axis, spatial, cache);
          if (paths.isEmpty()) {
            possible = false;
            break;
          }
          routes.add(paths);
        }
        if (!possible) continue;
        // Retain alternative elbow sides: the individually cheapest paths can cross.
        int combinations = 1 << routes.size();
        for (int mask = 0; mask < combinations && !stop.getAsBoolean(); mask++) {
          Network candidate = trial.copy();
          boolean duplicate = false;
          for (int j = 0; j < edges.size(); j++) {
            int choice = (mask >> j) & 1;
            if (choice >= routes.get(j).size()) {
              duplicate = true;
              break;
            }
            Network.Edge e = candidate.edges.get(edges.get(j).id);
            candidate.edges.put(
                e.id, new Network.Edge(e.id, e.start, e.end, routes.get(j).get(choice)));
          }
          if (duplicate) continue;
          if (options.treeGeometricSearch
              && NetworkCostBound.objectiveLowerBound(candidate, store, options, true) >= best.score - 1e-8) {
            boundPruned++;
            continue;
          }
          checked++;
          try {
            Evaluation result = evaluate.apply(candidate);
            if (result != null && result.score < best.score - 1e-8) best = result;
          } catch (IllegalArgumentException | TopologyException rejected) {
            // Full validation owns crossing, DN, depth, angle and wall constraints.
          }
        }
      }
    }
    if (ids.size() == 2 && !stop.getAsBoolean()) {
      Evaluation merged = contractPair(original, ids, edges, pool, axis, spatial, cache);
      if (merged.score < best.score - 1e-8) best = merged;
    }
    return best;
  }

  private Evaluation contractPair(
      Evaluation original,
      List<String> ids,
      List<Network.Edge> edges,
      List<Coordinate> pool,
      double axis,
      SpatialRules spatial,
      Map<String, List<LineString>> cache) {
    List<Network.Edge> arms = new ArrayList<>();
    for (Network.Edge e : edges) if (!(ids.contains(e.start) && ids.contains(e.end))) arms.add(e);
    // Two three-way junctions can become one legal four-way chamber.
    if (arms.size() > 4) return original;
    Network base = original.network.copy();
    base.nodes.remove(ids.get(1));
    for (Network.Edge e : edges) base.edges.remove(e.id);
    for (Network.Edge e : arms)
      base.edges.put(
          e.id,
          new Network.Edge(
              e.id,
              ids.contains(e.start) ? ids.get(0) : e.start,
              ids.contains(e.end) ? ids.get(0) : e.end,
              e.geometry));
    base.flows();
    int dn = base.incident(ids.get(0)).stream().mapToInt(e -> e.dn).max().orElse(100);
    List<Coordinate> points = new ArrayList<>();
    for (Coordinate point : pool) if (spatial.targetClear(point, dn, null)) points.add(point);
    points.sort(
        Comparator.comparingDouble(
            p ->
                estimate(
                    base,
                    new ArrayList<>(base.incident(ids.get(0))),
                    Map.of(ids.get(0), p),
                    Map.of())));
    Evaluation best = original;
    for (Coordinate point : points.subList(0, Math.min(40, points.size()))) {
      if (stop.getAsBoolean()) break;
      Network trial = base.copy();
      trial.nodes.get(ids.get(0)).point.setCoordinate(point);
      List<List<LineString>> routes = new ArrayList<>();
      for (Network.Edge old : arms) {
        Network.Edge edge = trial.edges.get(old.id);
        List<LineString> paths =
            cachedPaths(
                old,
                trial.nodes.get(edge.start),
                trial.nodes.get(edge.end),
                edge.dn,
                axis,
                spatial,
                cache);
        if (paths.isEmpty()) break;
        routes.add(paths);
      }
      if (routes.size() != arms.size()) continue;
      for (int mask = 0; mask < (1 << arms.size()) && !stop.getAsBoolean(); mask++) {
        Network candidate = trial.copy();
        boolean duplicate = false;
        for (int j = 0; j < arms.size(); j++) {
          int choice = (mask >> j) & 1;
          if (choice >= routes.get(j).size()) {
            duplicate = true;
            break;
          }
          Network.Edge edge = candidate.edges.get(arms.get(j).id);
          candidate.edges.put(
              edge.id, new Network.Edge(edge.id, edge.start, edge.end, routes.get(j).get(choice)));
        }
        if (duplicate) continue;
        if (options.treeGeometricSearch
            && NetworkCostBound.objectiveLowerBound(candidate, store, options, true) >= best.score - 1e-8) {
          boundPruned++;
          continue;
        }
        checked++;
        try {
          Evaluation value = evaluate.apply(candidate);
          if (value != null && value.score < best.score - 1e-8) best = value;
        } catch (IllegalArgumentException | TopologyException rejected) {
        }
      }
    }
    return best;
  }

  private List<LineString> cachedPaths(
      Network.Edge edge,
      Network.Node a,
      Network.Node b,
      int dn,
      double axis,
      SpatialRules spatial,
      Map<String, List<LineString>> cache) {
    String key = edge.id + ":" + dn + ":" + Geo.key(a.point) + ":" + Geo.key(b.point);
    List<LineString> routes = cache.get(key);
    if (routes == null) {
      routes = paths(edge.geometry, a, b, dn, axis, spatial);
      cache.put(key, routes);
    } else cacheHits++;
    return routes;
  }

  private static double estimate(
      Network n,
      List<Network.Edge> edges,
      Map<String, Coordinate> points,
      Map<String, String> parents) {
    double result = 0;
    for (Network.Edge e : edges) {
      String a = parents.getOrDefault(e.id, e.start);
      result +=
          points
              .getOrDefault(a, n.nodes.get(a).point)
              .distance(points.getOrDefault(e.end, n.nodes.get(e.end).point));
    }
    return result;
  }

  private static List<Coordinate> positions(
      Network n, List<Network.Edge> edges, List<String> ids, double axis) {
    Coordinate origin = n.nodes.get(ids.get(0)).point;
    double c = Math.cos(axis), s = Math.sin(axis);
    TreeSet<Double> xs = new TreeSet<>(), ys = new TreeSet<>();
    for (Network.Edge e : edges)
      for (Coordinate p : e.geometry.getCoordinates()) {
        double x = (p.x - origin.x) * c + (p.y - origin.y) * s;
        double y = -(p.x - origin.x) * s + (p.y - origin.y) * c;
        if (xs.stream().noneMatch(v -> Math.abs(v - x) < .001)) xs.add(x);
        if (ys.stream().noneMatch(v -> Math.abs(v - y) < .001)) ys.add(y);
      }
    List<Coordinate> result = new ArrayList<>();
    for (double x : xs)
      for (double y : ys)
        result.add(new Coordinate(origin.x + x * c - y * s, origin.y + x * s + y * c));
    return result;
  }

  private List<LineString> paths(
      LineString old, Network.Node a, Network.Node b, int dn, double axis, SpatialRules spatial) {
    if (a.point.distance(b.point) < .01) return List.of();
    List<LineString> candidates = new ArrayList<>(RouteFinder.connections(a.point, b.point, axis));
    Coordinate[] vertices = old.getCoordinates();
    // Keep known obstacle detours as reusable middle sections; move their ends together.
    for (int i = 0; i < vertices.length - 1; i++)
      for (int j = i + 1; j < vertices.length; j++) {
        List<LineString> prefixes = RouteFinder.connections(a.point, vertices[i], axis);
        List<LineString> suffixes = RouteFinder.connections(vertices[j], b.point, axis);
        for (LineString prefix : prefixes)
          for (LineString suffix : suffixes) {
            List<Coordinate> joined = new ArrayList<>(Arrays.asList(prefix.getCoordinates()));
            joined.addAll(Arrays.asList(vertices).subList(i + 1, j));
            joined.addAll(Arrays.asList(suffix.getCoordinates()));
            LineString line = compact(joined);
            if (line != null) candidates.add(line);
          }
      }
    candidates.sort(
        Comparator.comparingDouble(p -> p.getLength() + RoutingQuality.bends(p).equivalentM));
    List<LineString> valid = new ArrayList<>();
    Set<String> seen = new HashSet<>();
    String leaf = b.buildingId == null ? b.oksId : b.buildingId;
    for (LineString line : candidates) {
      if (stop.getAsBoolean()) break;
      if (!seen.add(line.toText()) || !line.isSimple() || !RouteFinder.standardBends(line))
        continue;
      Coordinate first = line.getCoordinateN(0), second = line.getCoordinateN(1);
      if (!RouteFinder.standardAngle(Math.atan2(second.y - first.y, second.x - first.x), axis))
        continue;
      routeChecks++;
      try {
        if (!spatial
            .assess(
                line,
                dn,
                leaf,
                a.type.equals("tie") ? a.point : null,
                b.type.equals("oks") ? b.point : null,
                b.entryWall)
            .valid()) continue;
      } catch (IllegalArgumentException | TopologyException invalid) {
        continue;
      }
      if (valid.stream().anyMatch(p -> p.equalsExact(line, 1e-5))) continue;
      valid.add(line);
      if (valid.size() == 2) break;
    }
    return valid;
  }

  private static LineString compact(List<Coordinate> points) {
    List<Coordinate> clean = new ArrayList<>();
    for (Coordinate p : points) {
      if (!clean.isEmpty() && clean.get(clean.size() - 1).distance(p) < 1e-6) continue;
      while (clean.size() > 1) {
        Coordinate a = clean.get(clean.size() - 2), b = clean.get(clean.size() - 1);
        double cross = (b.x - a.x) * (p.y - b.y) - (b.y - a.y) * (p.x - b.x);
        double dot = (b.x - a.x) * (p.x - b.x) + (b.y - a.y) * (p.y - b.y);
        if (Math.abs(cross) > 1e-6 || dot < 0) break;
        clean.remove(clean.size() - 1);
      }
      clean.add(p.copy());
    }
    return clean.size() < 2 ? null : Geo.line(clean.toArray(new Coordinate[0]));
  }
}
