package ru.teplotrassa.engine;

import java.util.*;
import java.util.function.*;
import org.locationtech.jts.geom.*;
import ru.teplotrassa.data.*;

/**
 * Root relocation and nonlocal subtree edge exchange, accepted by the complete engineering model.
 */
public final class GeometricOptimizer {
  private final FeatureStore store;
  private final Planner.Options options;
  private final BooleanSupplier stop;
  private final Function<Network, Evaluation> evaluate;
  private final BiConsumer<Integer, String> progress;
  private final Set<String> evaluated = new HashSet<>();
  private final List<Map<String, Object>> moves = new ArrayList<>();
  public final Map<String, Object> diagnostics = new LinkedHashMap<>();
  private int checks,
      boundPruned,
      duplicatePruned,
      accepted,
      graphSearches,
      graphSkips,
      routeChecks;
  private final Map<String, Integer> rejected = new TreeMap<>();

  public GeometricOptimizer(
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
    return improve(initial, save, options.treeRepairPasses);
  }

  public Evaluation improve(Evaluation initial, Consumer<Evaluation> save, int passes) {
    long started = System.nanoTime();
    Evaluation best = initial;
    diagnostics.put("method", "nonlocal_subtree_exchange_root_relocation_leaf_dn_refinement");
    diagnostics.put("beforeScore", initial.score);
    diagnostics.put("beforeLengthM", initial.summary.get("new_network_length"));
    diagnostics.put("globalOptimalityProven", false);
    diagnostics.put("allCandidatesExactlyRevalidated", true);
    diagnostics.put("moves", moves);
    for (int pass = 0; pass < passes && !stop.getAsBoolean(); pass++) {
      int before = accepted;
      Evaluation root = relocateRoot(best);
      if (root.score < best.score - 1e-8) {
        best = accept(best, root, "root_relocation", null, pass, save);
      }
      Evaluation merged = mergeBranches(best);
      if (merged.score < best.score - 1e-8)
        best = accept(best, merged, "nonadjacent_steiner_merge", null, pass, save);
      best = polishLeaves(best, pass, save);
      // Every current stem, including leaf stems, is examined; no arbitrary first eight leaves.
      List<Network.Edge> stems = new ArrayList<>(best.network.edges.values());
      stems.sort(
          Comparator.comparingDouble(
                  (Network.Edge e) ->
                      -(e.geometry.getLength() + RoutingQuality.bends(e.geometry).equivalentM))
              .thenComparing(e -> e.id));
      for (Network.Edge old : stems) {
        if (stop.getAsBoolean()) break;
        Network.Edge stem = best.network.edges.get(old.id);
        if (stem == null) continue;
        progress.accept(
            88, "Геометрическая перестройка: проход " + (pass + 1) + ", ветвь " + stem.id);
        Evaluation next = exchange(best, stem);
        if (next.score < best.score - 1e-8)
          best = accept(best, next, "subtree_exchange", stem.id, pass, save);
      }
      diagnostics.put("completedPasses", pass + 1);
      if (accepted == before) {
        diagnostics.put("stopReason", "no_improvement_in_examined_neighbourhood");
        break;
      }
    }
    diagnostics.putIfAbsent(
        "stopReason", stop.getAsBoolean() ? "cancelled" : "configured_passes_completed");
    diagnostics.put("acceptedMoves", accepted);
    diagnostics.put("evaluatedNetworks", checks);
    diagnostics.put("lowerBoundPruned", boundPruned);
    diagnostics.put("duplicatePruned", duplicatePruned);
    diagnostics.put("rejectedCandidates", rejected);
    diagnostics.put("visibilityGraphSearches", graphSearches);
    diagnostics.put("visibilityGraphCapacitySkips", graphSkips);
    diagnostics.put("checkedRoutes", routeChecks);
    diagnostics.put("afterScore", best.score);
    diagnostics.put("afterLengthM", best.summary.get("new_network_length"));
    diagnostics.put("elapsedMs", (System.nanoTime() - started) / 1000000);
    return best;
  }

  /** Re-route the longest terminal branches after the whole tree has its final diameters. */
  private Evaluation polishLeaves(Evaluation initial, int pass, Consumer<Evaluation> save) {
    Evaluation best = initial;
    List<Network.Edge> leaves = new ArrayList<>();
    for (Network.Edge edge : initial.network.edges.values())
      if (initial.network.nodes.get(edge.end).type.equals("oks")) leaves.add(edge);
    leaves.sort(Comparator.comparingDouble((Network.Edge e) -> -e.geometry.getLength())
        .thenComparing(e -> e.id));
    int limit = Math.min(leaves.size(), options.treeRepairCandidates);
    for (int i = 0; i < limit && !stop.getAsBoolean(); i++) {
      Network.Edge edge = best.network.edges.get(leaves.get(i).id);
      if (edge == null) continue;
      Network.Node end = best.network.nodes.get(edge.end);
      Network.Node start = best.network.nodes.get(edge.start);
      Envelope area = new Envelope(edge.geometry.getEnvelopeInternal());
      List<LineString> occupied = new ArrayList<>();
      for (Network.Edge other : best.network.edges.values())
        if (!other.id.equals(edge.id)) occupied.add(other.geometry);
      VisibilityRouter router = new VisibilityRouter(store, area, occupied, options, stop);
      double axis = axis(best.network, start);
      // The existing wall remains fixed here. Other walls are searched when the
      // whole subtree or the attachment point is rebuilt.
      List<LineString> paths = router.routes(end.point, start.point, edge.dn,
          end.buildingId == null ? end.oksId : end.buildingId,
          end.entryWall, axis, axis, (LineString) edge.geometry.reverse(), i < 2);
      graphSearches += router.graphSearches;
      graphSkips += router.graphCapacitySkips;
      routeChecks += router.checkedRoutes;
      for (LineString path : paths) {
        if (path.equalsExact((LineString) edge.geometry.reverse(), 1e-5)) continue;
        Network candidate = best.network.copy();
        candidate.edges.put(edge.id,
            new Network.Edge(edge.id, edge.start, edge.end, (LineString) path.reverse()));
        Evaluation checked = check(candidate, best.score);
        if (checked != null)
          best = accept(best, checked, "leaf_dn_refinement", edge.id, pass, save);
      }
    }
    return best;
  }

  private Evaluation accept(
      Evaluation before,
      Evaluation after,
      String kind,
      String edge,
      int pass,
      Consumer<Evaluation> save) {
    Map<String, Object> row = new LinkedHashMap<>();
    row.put("kind", kind);
    row.put("edgeId", edge);
    row.put("pass", pass + 1);
    row.put("beforeScore", before.score);
    row.put("afterScore", after.score);
    row.put("beforeLengthM", before.summary.get("new_network_length"));
    row.put("afterLengthM", after.summary.get("new_network_length"));
    row.put("removedChambers", before.network.nodes.size() - after.network.nodes.size());
    moves.add(row);
    accepted++;
    save.accept(after);
    return after;
  }

  private static final class Target {
    String node, edge, existing;
    Coordinate point;
    double axis, priority;

    Target(Coordinate p, double axis) {
      point = p.copy();
      this.axis = axis;
    }
  }

  private static final class Pair {
    Network.Edge a, b;
    double priority;

    Pair(Network.Edge a, Network.Edge b, double p) {
      this.a = a;
      this.b = b;
      priority = p;
    }
  }

  /** Simultaneously reconnect two disjoint subtrees through a new shared junction. */
  private Evaluation mergeBranches(Evaluation original) {
    Network n = original.network;
    List<Network.Edge> all = new ArrayList<>(n.edges.values());
    Map<String, Set<String>> subtrees = new HashMap<>();
    for (Network.Edge e : all) subtrees.put(e.id, descendants(n, e.end));
    List<Pair> pairs = new ArrayList<>();
    for (int i = 0; i < all.size(); i++)
      for (int j = i + 1; j < all.size(); j++) {
        Network.Edge a = all.get(i), b = all.get(j);
        if (!Collections.disjoint(subtrees.get(a.id), subtrees.get(b.id))) continue;
        Coordinate pa = n.nodes.get(a.end).point, pb = n.nodes.get(b.end).point;
        double potential = a.geometry.getLength() + b.geometry.getLength() - pa.distance(pb);
        if (potential > 10) pairs.add(new Pair(a, b, potential));
      }
    pairs.sort(
        Comparator.comparingDouble((Pair p) -> -p.priority)
            .thenComparing(p -> p.a.id + ":" + p.b.id));
    Evaluation best = original;
    for (Pair pair : pairs.subList(0, Math.min(pairs.size(), options.treeRepairCandidates))) {
      if (stop.getAsBoolean()) break;
      progress.accept(88, "Совместное объединение двух поддеревьев");
      Network base = n.copy();
      base.edges.remove(pair.a.id);
      base.edges.remove(pair.b.id);
      Set<String> excluded = new HashSet<>(subtrees.get(pair.a.id));
      excluded.addAll(subtrees.get(pair.b.id));
      prune(base, excluded);
      Network.Node a = base.nodes.get(pair.a.end), b = base.nodes.get(pair.b.end);
      int sharedDn = Rules.diameter(pair.a.flow + pair.b.flow);
      double bearing = axis(n, n.nodes.get(pair.a.start)),
          c = Math.cos(bearing),
          s = Math.sin(bearing);
      TreeSet<Double> xs = new TreeSet<>(), ys = new TreeSet<>();
      List<Coordinate> seeds =
          new ArrayList<>(
              List.of(
                  a.point,
                  b.point,
                  n.nodes.get(pair.a.start).point,
                  n.nodes.get(pair.b.start).point));
      List<Target> near =
          targets(
              base,
              excluded,
              new Coordinate((a.point.x + b.point.x) / 2, (a.point.y + b.point.y) / 2),
              pair.a);
      for (Target t : near.subList(0, Math.min(4, near.size()))) seeds.add(t.point);
      for (Coordinate p : seeds) {
        double x = p.x - a.point.x, y = p.y - a.point.y;
        xs.add(x * c + y * s);
        ys.add(-x * s + y * c);
      }
      Envelope area = new Envelope(a.point, b.point);
      List<LineString> occupied = new ArrayList<>();
      for (Network.Edge e : base.edges.values()) {
        occupied.add(e.geometry);
        area.expandToInclude(e.geometry.getEnvelopeInternal());
      }
      SpatialRules rules = new SpatialRules(store.near(SpatialRules.expand(area, 160)));
      List<Coordinate> hubs = new ArrayList<>();
      for (double x : xs)
        for (double y : ys) {
          Coordinate p = new Coordinate(a.point.x + x * c - y * s, a.point.y + x * s + y * c);
          if (p.distance(a.point) < .1
              || p.distance(b.point) < .1
              || !rules.targetClear(p, sharedDn, null)) continue;
          if (base.nodes.values().stream().anyMatch(v -> v.point.distance(p) < .05)) continue;
          hubs.add(p);
        }
      hubs.sort(
          Comparator.comparingDouble(
                  (Coordinate p) ->
                      p.distance(a.point)
                          + p.distance(b.point)
                          + near.stream().mapToDouble(t -> p.distance(t.point)).min().orElse(0))
              .thenComparing(Geo::key));
      VisibilityRouter router = new VisibilityRouter(store, area, occupied, options, stop);
      for (Coordinate hub : hubs.subList(0, Math.min(12, hubs.size()))) {
        if (stop.getAsBoolean()) break;
        List<Target> parents = targets(base, excluded, hub, pair.a);
        List<LineString> ra = null, rb = null;
        for (Target target : parents.subList(0, Math.min(6, parents.size()))) {
          if (stop.getAsBoolean()) break;
          Network provisional =
              mergeCandidate(
                  base,
                  pair,
                  hub,
                  target,
                  Geo.line(target.point, hub),
                  Geo.line(hub, a.point),
                  Geo.line(hub, b.point));
          if (provisional == null || !mayImprove(provisional, best.score, false)) continue;
          if (ra == null) {
            ra =
                router.routes(
                    a.point,
                    hub,
                    pair.a.dn,
                    a.type.equals("oks") ? a.buildingId : null,
                    a.entryWall,
                    bearing,
                    bearing,
                    (LineString) pair.a.geometry.reverse(),
                    false);
            rb =
                router.routes(
                    b.point,
                    hub,
                    pair.b.dn,
                    b.type.equals("oks") ? b.buildingId : null,
                    b.entryWall,
                    bearing,
                    bearing,
                    (LineString) pair.b.geometry.reverse(),
                    false);
          }
          if (ra.isEmpty() || rb.isEmpty()) break;
          List<LineString> rt =
              router.routes(
                  hub, target.point, sharedDn, null, null, bearing, target.axis, null, false);
          for (LineString trunk : rt)
            for (LineString left : ra)
              for (LineString right : rb) {
                Evaluation value =
                    check(
                        mergeCandidate(
                            base,
                            pair,
                            hub,
                            target,
                            (LineString) trunk.reverse(),
                            (LineString) left.reverse(),
                            (LineString) right.reverse()),
                        best.score);
                if (value != null) best = value;
              }
        }
      }
      recordRouter(router);
    }
    return best;
  }

  private Network mergeCandidate(
      Network base,
      Pair pair,
      Coordinate point,
      Target target,
      LineString trunk,
      LineString left,
      LineString right) {
    Network n = base.copy();
    String parent = target.node;
    if (parent == null) {
      if (!n.edges.containsKey(target.edge)) return null;
      parent = n.split(target.edge, target.point);
    }
    if (!n.nodes.containsKey(parent) || n.nodes.get(parent).type.equals("oks")) return null;
    Network.Node hub = new Network.Node(n.next("steiner"), point, "chamber");
    n.nodes.put(hub.id, hub);
    String stem = n.next("shared");
    n.edges.put(stem, new Network.Edge(stem, parent, hub.id, trunk));
    n.edges.put(pair.a.id, new Network.Edge(pair.a.id, hub.id, pair.a.end, left));
    n.edges.put(pair.b.id, new Network.Edge(pair.b.id, hub.id, pair.b.end, right));
    prune(n, Set.of());
    return n;
  }

  private Evaluation exchange(Evaluation original, Network.Edge cut) {
    Network base = original.network.copy();
    base.edges.remove(cut.id);
    Set<String> downstream = descendants(base, cut.end);
    prune(base, downstream);
    Network.Node child = base.nodes.get(cut.end);
    if (child == null) return original;
    List<Target> targets = targets(base, downstream, child.point, cut);
    Evaluation best = original;
    Envelope area = new Envelope(child.point);
    List<LineString> pipes = new ArrayList<>();
    for (Network.Edge e : base.edges.values()) {
      pipes.add(e.geometry);
      area.expandToInclude(e.geometry.getEnvelopeInternal());
    }
    VisibilityRouter router = new VisibilityRouter(store, area, pipes, options, stop);
    String building =
        child.type.equals("oks")
            ? (child.buildingId == null ? child.oksId : child.buildingId)
            : null;
    double axis = axis(original.network, original.network.nodes.get(cut.start));
    int limit = Math.min(targets.size(), Math.max(24, options.candidateLimit * 4));
    for (int i = 0; i < limit && !stop.getAsBoolean(); i++) {
      Target t = targets.get(i);
      // First screen topology, flow and minimum possible costs with a straight provisional stem.
      Network provisional = attach(base, t, cut, Geo.line(t.point, child.point));
      if (provisional == null || !mayImprove(provisional, best.score, false)) continue;
      List<LineString> routes =
          router.routes(
              child.point,
              t.point,
              cut.dn,
              building,
              child.entryWall,
              axis,
              t.axis,
              (LineString) cut.geometry.reverse(),
              i < 3);
      for (LineString route : routes) {
        Network candidate = attach(base, t, cut, (LineString) route.reverse());
        Evaluation value = check(candidate, best.score);
        if (value != null) best = value;
      }
    }
    recordRouter(router);
    return best;
  }

  private List<Target> targets(
      Network base, Set<String> excluded, Coordinate child, Network.Edge cut) {
    Map<String, Target> unique = new LinkedHashMap<>();
    for (Network.Node n : base.nodes.values()) {
      if (excluded.contains(n.id)
          || n.type.equals("oks")
          || base.incident(n.id).size() >= capacity(n)) continue;
      Target t = new Target(n.point, axis(base, n));
      t.node = n.id;
      put(unique, t, child);
    }
    for (Network.Edge e : base.edges.values()) {
      if (excluded.contains(e.start) || excluded.contains(e.end)) continue;
      Coordinate[] vertices = e.geometry.getCoordinates();
      for (int j = 1; j < vertices.length; j++) {
        LineSegment seg = new LineSegment(vertices[j - 1], vertices[j]);
        double angle = Math.atan2(seg.p1.y - seg.p0.y, seg.p1.x - seg.p0.x);
        List<Coordinate> anchors = List.of(child);
        for (Coordinate p : attachmentPoints(seg, anchors, angle)) {
          double at = Geo.index(e.geometry, p), len = e.geometry.getLength();
          if (at < .1 || at > len - .1) continue;
          Target t = new Target(p, RoutingQuality.upstreamBearing(e.geometry, p));
          t.edge = e.id;
          put(unique, t, child);
        }
      }
    }
    List<Target> out = new ArrayList<>(unique.values());
    out.sort(
        Comparator.comparingDouble((Target t) -> t.priority).thenComparing(t -> Geo.key(t.point)));
    // Always retain the original parent for same-topology route improvement.
    for (int i = 0; i < out.size(); i++)
      if (Objects.equals(out.get(i).node, cut.start)) {
        Target old = out.remove(i);
        out.add(0, old);
        break;
      }
    return out;
  }

  private void put(Map<String, Target> out, Target t, Coordinate from) {
    if (t.point.distance(from) < .1) return;
    t.priority = t.point.distance(from) + (t.edge == null ? 0 : 10);
    out.putIfAbsent(Geo.key(t.point), t);
  }

  /** Closest projection and exact ray/segment intersections, independent of a raster step. */
  static List<Coordinate> attachmentPoints(
      LineSegment segment, List<Coordinate> anchors, double axis) {
    Map<String, Coordinate> points = new LinkedHashMap<>();
    points.put(Geo.key(segment.p0), segment.p0);
    points.put(Geo.key(segment.p1), segment.p1);
    double vx = segment.p1.x - segment.p0.x, vy = segment.p1.y - segment.p0.y;
    for (Coordinate p : anchors) {
      Coordinate q = segment.closestPoint(p);
      points.put(Geo.key(q), q);
      for (int d = 0; d < 8; d++) {
        double dx = Math.cos(axis + d * Math.PI / 4),
            dy = Math.sin(axis + d * Math.PI / 4),
            den = dx * vy - dy * vx;
        if (Math.abs(den) < 1e-9) continue;
        double ax = segment.p0.x - p.x, ay = segment.p0.y - p.y;
        double t = (ax * vy - ay * vx) / den, u = (ax * dy - ay * dx) / den;
        if (t < -.001 || u < 0 || u > 1) continue;
        q = new Coordinate(segment.p0.x + u * vx, segment.p0.y + u * vy);
        points.put(Geo.key(q), q);
      }
    }
    return new ArrayList<>(points.values());
  }

  private Network attach(Network base, Target t, Network.Edge cut, LineString path) {
    Network n = base.copy();
    String parent = t.node;
    if (parent == null) {
      if (t.edge == null || !n.edges.containsKey(t.edge)) return null;
      parent = n.split(t.edge, t.point);
    }
    if (!n.nodes.containsKey(parent) || n.nodes.get(parent).type.equals("oks")) return null;
    Coordinate exact = n.nodes.get(parent).point;
    if (exact.distance(path.getCoordinateN(0)) > 1e-5) return null;
    n.edges.put(cut.id, new Network.Edge(cut.id, parent, cut.end, path));
    prune(n, Set.of());
    return n;
  }

  private Evaluation relocateRoot(Evaluation original) {
    if (original.network.roots().size() != 1) return original;
    Network.Node root = original.network.roots().get(0);
    List<Network.Edge> outgoing = original.network.children(root.id);
    if (outgoing.size() != 1) return original;
    Network.Edge first = outgoing.get(0);
    Network.Node child = original.network.nodes.get(first.end);
    double oldAxis = axis(original.network, root);
    Map<String, Target> candidates = new LinkedHashMap<>();
    List<Feature> chambers = store.all("heat_chamber");
    for (Feature f : store.all("heat_network")) {
      Coordinate[] vv = f.geometry.getCoordinates();
      for (int j = 1; j < vv.length; j++) {
        LineSegment segment = new LineSegment(vv[j - 1], vv[j]);
        if (segment.getLength() < .01) continue;
        double a = Math.atan2(segment.p1.y - segment.p0.y, segment.p1.x - segment.p0.x);
        if (!RouteFinder.standardAngle(a, oldAxis)) continue;
        for (Coordinate p : attachmentPoints(segment, List.of(child.point), a)) {
          if (p.distance(root.point) < .05
              || chambers.stream().anyMatch(ch -> ch.geometry.distance(Geo.point(p)) <= 10.000001))
            continue;
          Target t = new Target(p, a);
          t.existing = f.id;
          put(candidates, t, child.point);
        }
      }
    }
    for (Feature f : chambers) {
      if (InputValidator.existingDegree(f, store) >= 4) continue;
      Network.Node node = new Network.Node("candidate", f.geometry.getCoordinate(), "tie");
      node.existingId = f.id;
      double a = axis(original.network, node);
      if (!RouteFinder.standardAngle(a, oldAxis)) continue;
      Target t = new Target(node.point, a);
      t.existing = f.id;
      put(candidates, t, child.point);
    }
    List<Target> sorted = new ArrayList<>(candidates.values());
    sorted.sort(
        Comparator.comparingDouble((Target t) -> t.priority)
            .thenComparing(t -> t.existing)
            .thenComparing(t -> Geo.key(t.point)));
    List<LineString> pipes = new ArrayList<>();
    Envelope area = new Envelope(child.point);
    for (Network.Edge e : original.network.edges.values())
      if (!e.id.equals(first.id)) {
        pipes.add(e.geometry);
        area.expandToInclude(e.geometry.getEnvelopeInternal());
      }
    for (Target t : sorted) area.expandToInclude(t.point);
    VisibilityRouter router = new VisibilityRouter(store, area, pipes, options, stop);
    Evaluation best = original;
    for (int i = 0;
        i < Math.min(sorted.size(), Math.max(24, options.candidateLimit * 4))
            && !stop.getAsBoolean();
        i++) {
      Target t = sorted.get(i);
      Network provisional =
          rootCandidate(original.network, root, first, t, Geo.line(t.point, child.point));
      if (!mayImprove(provisional, best.score, false)) continue;
      String building =
          child.type.equals("oks")
              ? (child.buildingId == null ? child.oksId : child.buildingId)
              : null;
      for (LineString route :
          router.routes(
              child.point,
              t.point,
              first.dn,
              building,
              child.entryWall,
              oldAxis,
              t.axis,
              (LineString) first.geometry.reverse(),
              i < 3)) {
        Evaluation value =
            check(
                rootCandidate(original.network, root, first, t, (LineString) route.reverse()),
                best.score);
        if (value != null) best = value;
      }
    }
    recordRouter(router);
    return best;
  }

  private static Network rootCandidate(
      Network old, Network.Node root, Network.Edge edge, Target t, LineString path) {
    Network n = old.copy();
    Network.Node r = n.nodes.get(root.id);
    r.point.setCoordinate(t.point);
    r.existingId = t.existing;
    n.edges.put(edge.id, new Network.Edge(edge.id, root.id, edge.end, path));
    return n;
  }

  private void recordRouter(VisibilityRouter r) {
    graphSearches += r.graphSearches;
    graphSkips += r.graphCapacitySkips;
    routeChecks += r.checkedRoutes;
  }

  private Evaluation check(Network network, double incumbent) {
    if (network == null || stop.getAsBoolean()) return null;
    String key = signature(network);
    if (!evaluated.add(key)) {
      duplicatePruned++;
      return null;
    }
    if (!mayImprove(network, incumbent, true)) return null;
    checks++;
    try {
      Evaluation result = evaluate.apply(network);
      if (result != null
          && Boolean.TRUE.equals(result.checks.get("allConnectionsConnected"))
          && result.score < incumbent - 1e-8) return result;
    } catch (IllegalArgumentException | TopologyException e) {
      String message = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
      rejected.merge(message.length() > 160 ? message.substring(0, 160) : message, 1, Integer::sum);
    }
    return null;
  }

  /** Optimistic full-network objective: omits special/depth premiums, never charges them twice. */
  private boolean mayImprove(Network n, double incumbent, boolean includeTurns) {
    if (NetworkCostBound.objectiveLowerBound(n, store, options, includeTurns) >= incumbent - 1e-8) {
      boundPruned++;
      return false;
    }
    return true;
  }

  private int capacity(Network.Node node) {
    if (node.existingId == null) return 4;
    Feature f = store.get(node.existingId);
    return 4 - (f.type.equals("heat_chamber") ? InputValidator.existingDegree(f, store) : 2);
  }

  private double axis(Network n, Network.Node node) {
    if (node.existingId != null) {
      Feature f = store.get(node.existingId);
      Set<String> seen = new HashSet<>();
      while (f != null && f.type.equals("heat_chamber") && seen.add(f.id))
        f = store.get(f.text("upstream_object_id"));
      if (f != null && f.geometry instanceof LineString)
        return TreeRoots.axis((LineString) f.geometry, node.point);
    }
    for (Network.Edge e : n.edges.values())
      if (e.end.equals(node.id)) {
        int last = e.geometry.getNumPoints() - 1;
        Coordinate a = e.geometry.getCoordinateN(last - 1), b = e.geometry.getCoordinateN(last);
        return Math.atan2(b.y - a.y, b.x - a.x);
      }
    return 0;
  }

  private static Set<String> descendants(Network n, String at) {
    Set<String> out = new HashSet<>();
    Deque<String> todo = new ArrayDeque<>();
    todo.add(at);
    while (!todo.isEmpty()) {
      String id = todo.remove();
      if (!out.add(id)) continue;
      for (Network.Edge e : n.children(id)) todo.add(e.end);
    }
    return out;
  }

  /** Remove abandoned stubs and unnecessary degree-two chambers, preserving all consumers. */
  static void prune(Network n, Set<String> protectedNodes) {
    boolean changed;
    do {
      changed = false;
      for (Network.Node node : new ArrayList<>(n.nodes.values())) {
        if (!node.type.equals("chamber") || protectedNodes.contains(node.id)) continue;
        List<Network.Edge> children = n.children(node.id), incident = n.incident(node.id);
        if (children.isEmpty()) {
          for (Network.Edge e : incident) n.edges.remove(e.id);
          n.nodes.remove(node.id);
          changed = true;
          break;
        }
        if (children.size() == 1 && incident.size() == 2) {
          Network.Edge out = children.get(0),
              in = incident.get(0).id.equals(out.id) ? incident.get(1) : incident.get(0);
          if (!in.end.equals(node.id)) continue;
          List<Coordinate> points = new ArrayList<>(Arrays.asList(in.geometry.getCoordinates()));
          points.addAll(Arrays.asList(out.geometry.getCoordinates()));
          n.edges.remove(in.id);
          n.edges.remove(out.id);
          n.nodes.remove(node.id);
          n.edges.put(
              in.id, new Network.Edge(in.id, in.start, out.end, VisibilityRouter.compact(points)));
          changed = true;
          break;
        }
      }
    } while (changed);
  }

  private static String signature(Network n) {
    List<String> rows = new ArrayList<>();
    for (Network.Edge e : n.edges.values())
      rows.add(
          Geo.key(n.nodes.get(e.start).point)
              + ":"
              + Geo.key(n.nodes.get(e.end).point)
              + ":"
              + e.geometry.toText());
    for (Network.Node root : n.roots())
      rows.add("root:" + root.existingId + ":" + Geo.key(root.point));
    Collections.sort(rows);
    return String.join("|", rows);
  }
}
