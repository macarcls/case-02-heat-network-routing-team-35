package ru.teplotrassa.engine;

import java.util.*;
import java.util.function.BooleanSupplier;
import org.locationtech.jts.geom.*;
import ru.teplotrassa.data.*;

/**
 * Rotated corridor graph. A new branch searches from every available vertex of the growing tree.
 */
public final class TrunkGrid {
  private static final class Port {
    int demand, node;
    Coordinate point;
    double length, bearing;
    LineString approach;
  }

  private final FeatureStore store;
  private final List<Feature> demands;
  private final List<BuildingAccess> access = new ArrayList<>();
  private final List<Port> ports = new ArrayList<>();
  private final List<Feature> existingLines = new ArrayList<>();
  private final TreeRoots.Candidate root;
  private final BooleanSupplier stop;
  private final SpatialRules spatial;
  private final Planner.Options options;
  private final double c, s;
  private final int dn;
  private final boolean economic;
  private double[] xs, ys;
  private Coordinate[] points;
  private boolean[] open;
  private double[][] weights;
  private int cols, rows, rootNode;
  public final Map<String, Object> diagnostics = new LinkedHashMap<>();

  public TrunkGrid(
      FeatureStore store,
      List<Feature> demands,
      TreeRoots.Candidate root,
      Planner.Options options,
      BooleanSupplier stop) {
    this(
        store,
        demands,
        root,
        options,
        stop,
        Rules.diameter(demands.stream().mapToDouble(Feature::flow).sum()),
        false);
  }

  public TrunkGrid(
      FeatureStore store,
      List<Feature> demands,
      TreeRoots.Candidate root,
      Planner.Options options,
      BooleanSupplier stop,
      int proposalDn,
      boolean economic) {
    this.store = store;
    this.demands = demands;
    this.root = root;
    this.options = options;
    this.stop = stop;
    c = Math.cos(root.axis);
    s = Math.sin(root.axis);
    dn = proposalDn;
    this.economic = economic;
    Envelope local = new Envelope(0, 0, 0, 0), world = new Envelope(root.point);
    for (Feature d : demands) {
      BuildingAccess a = BuildingAccess.resolve(store, store.get(d.text("_tt_entry_id")));
      access.add(a);
      Coordinate p = a.entry.geometry.getCoordinate();
      local.expandToInclude(frame(p));
      world.expandToInclude(p);
    }
    local.expandBy(80);
    world.expandBy(180);
    List<Feature> objects = store.near(world);
    spatial = new SpatialRules(objects, true);
    for (Feature f : objects) if (f.type.equals("heat_network")) existingLines.add(f);
    for (int i = 0; i < demands.size(); i++) preparePorts(i);
    TreeSet<Double> xx = new TreeSet<>(), yy = new TreeSet<>();
    xx.add(0d);
    yy.add(0d);
    for (Port p : ports) {
      Coordinate q = frame(p.point);
      xx.add(q.x);
      yy.add(q.y);
    }
    double margin = Rules.buildingClearance(dn) + Rules.width(dn) / 2 + .2;
    for (Feature f : objects) {
      if (!BuildingAccess.isBuilding(f)) continue;
      Envelope box = new Envelope();
      for (Coordinate p : f.geometry.getCoordinates()) box.expandToInclude(frame(p));
      for (double x : new double[] {box.getMinX() - margin, box.getMaxX() + margin})
        if (x >= local.getMinX() && x <= local.getMaxX()) xx.add(x);
      for (double y : new double[] {box.getMinY() - margin, box.getMaxY() + margin})
        if (y >= local.getMinY() && y <= local.getMaxY()) yy.add(y);
    }
    double spacing = Math.max(5, options.gridM);
    for (double x = local.getMinX(); x <= local.getMaxX(); x += spacing) xx.add(x);
    for (double y = local.getMinY(); y <= local.getMaxY(); y += spacing) yy.add(y);
    xs = distinct(xx);
    ys = distinct(yy);
    cols = xs.length;
    rows = ys.length;
    diagnostics.put("gridNodes", (long) cols * rows);
    diagnostics.put("constructionDiameter", dn);
    diagnostics.put("diameterRole", "proposal_clearance_only_final_flows_recomputed");
    diagnostics.put("chamberAndPipeCostsInSearch", economic);
    diagnostics.put("portalCount", ports.size());
    Map<String, Integer> pc = new LinkedHashMap<>();
    for (int j = 0; j < demands.size(); j++) {
      int count = 0;
      for (Port p : ports) if (p.demand == j) count++;
      pc.put(access.get(j).entry.id, count);
    }
    diagnostics.put("entryPortCounts", pc);
    diagnostics.put("role", "shared_tree_routing_graph");
    diagnostics.put("allTreeVerticesAreAttachmentCandidates", true);
    if ((long) cols * rows > options.maxCells) {
      diagnostics.put("status", "graph_capacity_exceeded");
      points = new Coordinate[0];
      return;
    }
    points = new Coordinate[cols * rows];
    open = new boolean[points.length];
    weights = new double[points.length][2];
    for (int i = 0; i < points.length && !stop.getAsBoolean(); i++) {
      points[i] = world(xs[i % cols], ys[i / cols]);
      open[i] = spatial.targetClear(points[i], dn, null);
    }
    rootNode = nearest(xs, 0) + cols * nearest(ys, 0);
    points[rootNode] = root.point.copy();
    for (Port p : ports) {
      Coordinate q = frame(p.point);
      p.node = nearest(xs, q.x) + cols * nearest(ys, q.y);
      p.point = points[p.node];
    }
    diagnostics.put("status", "ready");
  }

  private void preparePorts(int index) {
    BuildingAccess a = access.get(index);
    Coordinate entry = a.entry.geometry.getCoordinate();
    int leafDn = Rules.diameter(demands.get(index).flow());
    BuildingAccess.Gate gate = a.gate == null ? null : a.gate.forDiameter(leafDn);
    List<Port> candidates = new ArrayList<>();
    for (int direction = 0; direction < 8; direction++) {
      double bearing = root.axis + direction * Math.PI / 4;
      Coordinate ray = new Coordinate(entry.x + Math.cos(bearing), entry.y + Math.sin(bearing));
      Coordinate portal = gate == null ? entry : gate.crossingToward(ray);
      if (portal == null) continue;
      for (double outside :
          new double[] {Rules.buildingClearance(dn) + Rules.width(dn) / 2 + .2, 8, 10, 14}) {
        double length = entry.distance(portal) + outside;
        Coordinate p =
            new Coordinate(
                entry.x + Math.cos(bearing) * length, entry.y + Math.sin(bearing) * length);
        if (!spatial.targetClear(p, dn, null)) continue;
        if (!spatial.assess(Geo.line(entry, p), leafDn, a.buildingId(), null, entry).valid())
          continue;
        Port candidate = new Port();
        candidate.demand = index;
        candidate.point = p;
        candidate.length = length;
        candidate.bearing = bearing;
        candidate.approach = Geo.line(entry, p);
        candidates.add(candidate);
        break;
      }
    }
    if (candidates.isEmpty() && gate != null) {
      // Exact short exits from a narrow wall precede the common orthogonal corridors.
      // The logical consumer coordinate remains unchanged; the route chooses a wall.
      for (int direction = 0; direction < 8; direction++) {
        double bearing = root.axis + direction * Math.PI / 4;
        Coordinate portal =
            gate.crossingToward(
                new Coordinate(entry.x + Math.cos(bearing), entry.y + Math.sin(bearing)));
        if (portal == null) continue;
        for (double offset : new double[] {.05, Rules.width(leafDn) / 2 + .1, 1}) {
          double distance = entry.distance(portal) + offset;
          Coordinate anchor =
              new Coordinate(
                  entry.x + distance * Math.cos(bearing), entry.y + distance * Math.sin(bearing));
          for (Coordinate outside : gate.outsideCandidates(portal, dn)) {
            if (stop.getAsBoolean()) return;
            if (!spatial.targetClear(outside, dn, null)) continue;
            List<LineString> tails = RouteFinder.connections(anchor, outside, bearing);
            tails.addAll(RouteFinder.connections(anchor, outside, bearing + Math.PI / 4));
            for (LineString tail : tails) {
              List<Coordinate> points = new ArrayList<>();
              points.add(entry);
              points.addAll(Arrays.asList(tail.getCoordinates()));
              LineString prefix = compact(points);
              Coordinate penultimate = prefix.getCoordinateN(prefix.getNumPoints() - 2),
                  last = prefix.getCoordinateN(prefix.getNumPoints() - 1);
              double heading = Math.atan2(last.y - penultimate.y, last.x - penultimate.x);
              if (!RouteFinder.standardBends(prefix)
                  || !RouteFinder.standardAngle(heading, root.axis)) continue;
              if (!spatial
                  .assess(prefix, leafDn, access.get(index).buildingId(), null, entry)
                  .valid()) continue;
              Port candidate = new Port();
              candidate.demand = index;
              candidate.point = outside;
              candidate.approach = prefix;
              candidate.length = prefix.getLength() + RoutingQuality.bends(prefix).equivalentM;
              candidate.bearing = heading;
              if (candidates.stream().noneMatch(p -> p.point.distance(outside) < .1))
                candidates.add(candidate);
            }
          }
        }
      }
    }
    candidates.sort(
        Comparator.comparingDouble((Port p) -> p.length + .02 * p.point.distance(root.point))
            .thenComparingDouble(p -> p.bearing));
    ports.addAll(candidates.subList(0, Math.min(4, candidates.size())));
  }

  public Network build(int order) {
    Network empty = new Network();
    if (points.length == 0 || stop.getAsBoolean() || !open[rootNode]) return empty;
    int n = points.length;
    int[] parent = new int[n], degree = new int[n];
    Arrays.fill(parent, -2);
    parent[rootNode] = -1;
    Port[] chosen = new Port[demands.size()];
    List<Integer> fixed = new ArrayList<>();
    for (int i = 0; i < demands.size(); i++) fixed.add(i);
    if (order == 1)
      fixed.sort(
          Comparator.comparingDouble(
                  (Integer i) -> access.get(i).entry.geometry.getCoordinate().distance(root.point))
              .reversed());
    else if (order == 2)
      fixed.sort(Comparator.comparingDouble((Integer i) -> demands.get(i).flow()).reversed());
    Feature source = store.get(root.existingId);
    int rootCapacity =
        4 - (source.type.equals("heat_chamber") ? InputValidator.existingDegree(source, store) : 2);
    for (int added = 0; added < demands.size() && !stop.getAsBoolean(); added++) {
      double[] distance = new double[n * 2];
      Arrays.fill(distance, Double.POSITIVE_INFINITY);
      int[] previous = new int[n * 2];
      Arrays.fill(previous, -1);
      PriorityQueue<double[]> queue =
          new PriorityQueue<>(
              Comparator.comparingDouble((double[] a) -> a[0]).thenComparingDouble(a -> a[1]));
      for (int i = 0; i < n; i++)
        if (parent[i] != -2 && degree[i] < (i == rootNode ? rootCapacity : 4)) {
          int heading = parent[i] < 0 ? 0 : (i % cols == parent[i] % cols ? 1 : 0);
          for (int h = 0; h < 2; h++) {
            int state = i * 2 + h;
            double cost = h == heading ? 0 : RoutingQuality.TURN_90_M;
            if (economic && degree[i] == 2 && i != rootNode)
              cost += options.score(Rules.chamber(dn), 0) / options.score(0, 1);
            distance[state] = cost;
            queue.add(new double[] {cost, state});
          }
        }
      int wanted = order >= 3 && added == 0 ? order - 3 : -1;
      if (order == 1 || order == 2) {
        fixed.removeIf(i -> chosen[i] != null);
        if (fixed.isEmpty()) break;
        wanted = fixed.get(0);
      }
      Map<Integer, List<Port>> destinations = new HashMap<>();
      for (Port port : ports)
        if (chosen[port.demand] == null
            && (wanted < 0 || wanted == port.demand)
            && degree[port.node] < 4)
          destinations.computeIfAbsent(port.node, k -> new ArrayList<>()).add(port);
      Port best = null;
      int bestState = -1;
      double bestCost = Double.POSITIVE_INFINITY;
      while (!queue.isEmpty() && !stop.getAsBoolean()) {
        double[] item = queue.remove();
        int state = (int) item[1], at = state / 2, heading = state % 2;
        if (item[0] > distance[state] + 1e-9) continue;
        if (item[0] >= bestCost) break;
        for (Port port : destinations.getOrDefault(at, List.of())) {
          double cost =
              item[0]
                  + port.length * metreWeight()
                  + RoutingQuality.connectionPenalty(
                      port.bearing, root.axis + heading * Math.PI / 2);
          if (cost < bestCost) {
            best = port;
            bestState = state;
            bestCost = cost;
          }
        }
        for (int direction = 0; direction < 4; direction++) {
          int next = neighbor(at, direction);
          if (next < 0 || !open[next] || parent[next] != -2) continue;
          double weight = weight(at, next);
          if (weight < 0) continue;
          int axis = direction % 2, nextState = next * 2 + axis;
          double cost = item[0] + weight + (axis == heading ? 0 : RoutingQuality.TURN_90_M);
          if (cost + 1e-9 < distance[nextState]) {
            distance[nextState] = cost;
            previous[nextState] = state;
            queue.add(new double[] {cost, nextState});
          }
        }
      }
      if (best == null) {
        if (order == 1 || order == 2) {
          fixed.remove(Integer.valueOf(wanted));
          if (!fixed.isEmpty()) {
            added--;
            continue;
          }
        }
        break;
      }
      int state = bestState;
      while (parent[state / 2] == -2) {
        int before = previous[state];
        if (before < 0) throw new IllegalStateException("Unrooted corridor path");
        parent[state / 2] = before / 2;
        degree[state / 2]++;
        degree[before / 2]++;
        state = before;
      }
      degree[best.node]++;
      chosen[best.demand] = best;
    }
    return network(parent, degree, chosen);
  }

  private double weight(int a, int b) {
    int key = Math.min(a, b), axis = a % cols == b % cols ? 1 : 0;
    if (weights[key][axis] == 0) {
      LineString line = Geo.line(points[a], points[b]);
      if (line.getLength() < 1e-6
          || !spatial.hardClear(line, dn, null, null)
          || !parallelClear(line)) weights[key][axis] = -1;
      else {
        Coordinate mid =
            new Coordinate((points[a].x + points[b].x) / 2, (points[a].y + points[b].y) / 2);
        weights[key][axis] =
            line.getLength()
                * (economic
                    ? options.score(
                            Rules.NEW[Rules.index(dn)]
                                * spatial.multiplier(mid, 0, options.mode.equals("depth"), dn),
                            1)
                        / options.score(0, 1)
                    : spatial.multiplier(mid, 0, options.mode.equals("depth"), dn));
      }
    }
    return weights[key][axis];
  }

  private double metreWeight() {
    return economic ? options.score(Rules.NEW[Rules.index(dn)], 1) / options.score(0, 1) : 1;
  }

  /**
   * Metric-closure spanning tree, expanded and cycle-pruned on the same obstacle graph. This
   * proposes a different global topology from sequential nearest attachment. The final nonlinear
   * flow/cost/angle model remains authoritative.
   */
  public Network buildMetricTree(int choice) {
    if (points.length == 0 || stop.getAsBoolean() || !open[rootNode]) return new Network();
    Port[] chosen = new Port[demands.size()];
    for (int i = 0; i < chosen.length; i++) {
      final int demand = i;
      List<Port> pp = new ArrayList<>();
      for (Port port : ports) if (port.demand == demand && open[port.node]) pp.add(port);
      if (pp.isEmpty()) return new Network();
      pp.sort(
          Comparator.comparingDouble((Port p) -> p.length + distanceToOtherPorts(p))
              .thenComparingDouble(p -> p.bearing));
      chosen[i] = pp.get(choice % pp.size());
    }
    int[] terminal = new int[chosen.length + 1];
    terminal[0] = rootNode;
    for (int i = 0; i < chosen.length; i++) terminal[i + 1] = chosen[i].node;
    List<double[]> links = new ArrayList<>();
    List<int[]> predecessors = new ArrayList<>();
    int n = points.length;
    for (int source = 0; source < terminal.length && !stop.getAsBoolean(); source++) {
      double[] dist = new double[n * 2];
      Arrays.fill(dist, Double.POSITIVE_INFINITY);
      int[] prev = new int[n * 2];
      Arrays.fill(prev, -1);
      PriorityQueue<double[]> queue =
          new PriorityQueue<>(
              Comparator.comparingDouble((double[] a) -> a[0]).thenComparingDouble(a -> a[1]));
      for (int h = 0; h < 2; h++) {
        dist[terminal[source] * 2 + h] = 0;
        queue.add(new double[] {0, terminal[source] * 2 + h});
      }
      while (!queue.isEmpty() && !stop.getAsBoolean()) {
        double[] item = queue.remove();
        int state = (int) item[1], at = state / 2, heading = state % 2;
        if (item[0] > dist[state] + 1e-9) continue;
        for (int d = 0; d < 4; d++) {
          int next = neighbor(at, d);
          if (next < 0 || !open[next]) continue;
          double w = weight(at, next);
          if (w < 0) continue;
          int h = d % 2, ns = next * 2 + h;
          double cost = item[0] + w + (h == heading ? 0 : RoutingQuality.TURN_90_M);
          if (cost + 1e-9 < dist[ns]) {
            dist[ns] = cost;
            prev[ns] = state;
            queue.add(new double[] {cost, ns});
          }
        }
      }
      predecessors.add(prev);
      for (int dest = source + 1; dest < terminal.length; dest++) {
        int at = terminal[dest] * 2;
        if (dist[at + 1] < dist[at]) at++;
        if (Double.isFinite(dist[at])) links.add(new double[] {dist[at], source, dest, at});
      }
    }
    if (stop.getAsBoolean()) return new Network();
    links.sort(
        Comparator.comparingDouble((double[] e) -> e[0])
            .thenComparingDouble(e -> e[1])
            .thenComparingDouble(e -> e[2]));
    int[] groups = new int[terminal.length];
    for (int i = 0; i < groups.length; i++) groups[i] = i;
    Map<Long, double[]> union = new LinkedHashMap<>();
    int joins = 0;
    for (double[] link : links) {
      int a = (int) link[1], b = (int) link[2], pa = find(groups, a), pb = find(groups, b);
      if (pa == pb) continue;
      groups[pa] = pb;
      joins++;
      int[] prev = predecessors.get(a);
      for (int at = (int) link[3]; prev[at] >= 0; at = prev[at]) {
        int x = at / 2, y = prev[at] / 2;
        long key = ((long) Math.min(x, y) << 32) | Math.max(x, y);
        union.putIfAbsent(key, new double[] {weight(x, y), x, y});
      }
    }
    if (joins != terminal.length - 1) return new Network();
    // Overlapping shortest paths can form cycles. Keep a spanning forest of their union.
    List<double[]> edges = new ArrayList<>(union.values());
    edges.sort(
        Comparator.comparingDouble((double[] e) -> e[0])
            .thenComparingDouble(e -> e[1])
            .thenComparingDouble(e -> e[2]));
    int[] component = new int[n], degree = new int[n];
    Map<Integer, List<Integer>> adj = new HashMap<>();
    for (int i = 0; i < n; i++) component[i] = i;
    for (double[] e : edges) {
      int a = (int) e[1], b = (int) e[2], pa = find(component, a), pb = find(component, b);
      if (pa == pb) continue;
      component[pa] = pb;
      adj.computeIfAbsent(a, k -> new ArrayList<>()).add(b);
      adj.computeIfAbsent(b, k -> new ArrayList<>()).add(a);
      degree[a]++;
      degree[b]++;
    }
    boolean[] required = new boolean[n], removed = new boolean[n];
    for (int t : terminal) required[t] = true;
    ArrayDeque<Integer> leaves = new ArrayDeque<>();
    for (int i : adj.keySet()) if (degree[i] == 1 && !required[i]) leaves.add(i);
    while (!leaves.isEmpty()) {
      int at = leaves.remove();
      if (removed[at]) continue;
      removed[at] = true;
      for (int next : adj.getOrDefault(at, List.of()))
        if (!removed[next] && --degree[next] == 1 && !required[next]) leaves.add(next);
    }
    int[] parent = new int[n];
    Arrays.fill(parent, -2);
    Arrays.fill(degree, 0);
    parent[rootNode] = -1;
    ArrayDeque<Integer> todo = new ArrayDeque<>();
    todo.add(rootNode);
    while (!todo.isEmpty()) {
      int at = todo.remove();
      for (int next : adj.getOrDefault(at, List.of()))
        if (!removed[next] && parent[next] == -2) {
          parent[next] = at;
          degree[next]++;
          degree[at]++;
          todo.add(next);
        }
    }
    for (Port port : chosen) {
      if (parent[port.node] == -2) return new Network();
      degree[port.node]++;
    }
    return network(parent, degree, chosen);
  }

  private double distanceToOtherPorts(Port p) {
    double nearest = p.point.distance(root.point);
    for (Port q : ports)
      if (q.demand != p.demand) nearest = Math.min(nearest, p.point.distance(q.point));
    return nearest;
  }

  private static int find(int[] parent, int at) {
    while (parent[at] != at) {
      parent[at] = parent[parent[at]];
      at = parent[at];
    }
    return at;
  }

  private boolean parallelClear(LineString line) {
    LineSegment edge = new LineSegment(line.getCoordinateN(0), line.getCoordinateN(1));
    double length = edge.getLength();
    for (Feature f : existingLines) {
      double gap = Rules.width(dn) / 2 + Rules.width(f.dn()) / 2 + 1;
      Envelope box = f.geometry.getEnvelopeInternal();
      box = new Envelope(box);
      box.expandBy(gap);
      if (!box.intersects(line.getEnvelopeInternal())) continue;
      if (f.geometry.distance(Geo.point(root.point)) < .26
          && Math.max(edge.p0.distance(root.point), edge.p1.distance(root.point)) < 2 * gap)
        continue;
      Coordinate[] vertices = f.geometry.getCoordinates();
      for (int j = 1; j < vertices.length; j++) {
        LineSegment other = new LineSegment(vertices[j - 1], vertices[j]);
        double size = other.getLength();
        if (size < 1e-6) continue;
        double sine =
            Math.abs(
                    (edge.p1.x - edge.p0.x) * (other.p1.y - other.p0.y)
                        - (edge.p1.y - edge.p0.y) * (other.p1.x - other.p0.x))
                / (length * size);
        if (sine < Math.sqrt(.5) - 1e-8 && edge.distance(other) < gap - 1e-5) return false;
      }
    }
    return true;
  }

  private int neighbor(int at, int direction) {
    int x = at % cols, y = at / cols;
    switch (direction) {
      case 0:
        x++;
        break;
      case 1:
        y++;
        break;
      case 2:
        x--;
        break;
      default:
        y--;
    }
    return x < 0 || y < 0 || x >= cols || y >= rows ? -1 : y * cols + x;
  }

  private Network network(int[] parent, int[] degree, Port[] chosen) {
    Network n = new Network();
    Map<Integer, List<Integer>> children = new HashMap<>();
    Map<Integer, Integer> terminal = new HashMap<>();
    for (int i = 0; i < parent.length; i++)
      if (parent[i] >= 0) children.computeIfAbsent(parent[i], k -> new ArrayList<>()).add(i);
    for (int i = 0; i < chosen.length; i++)
      if (chosen[i] != null) {
        children.computeIfAbsent(chosen[i].node, k -> new ArrayList<>()).add(-i - 1);
        terminal.put(-i - 1, i);
      }
    if (terminal.isEmpty()) return n;
    for (int i = 0; i < parent.length; i++)
      if (parent[i] != -2 && (i == rootNode || degree[i] != 2)) {
        Network.Node node =
            new Network.Node("grid_" + i, points[i], i == rootNode ? "tie" : "chamber");
        if (i == rootNode) node.existingId = root.existingId;
        n.nodes.put(node.id, node);
      }
    for (int i : terminal.values()) {
      Feature d = demands.get(i);
      BuildingAccess a = access.get(i);
      Network.Node leaf = new Network.Node("entry_" + i, a.entry.geometry.getCoordinate(), "oks");
      leaf.entryId = a.entry.id;
      leaf.oksId = InputData.buildingId(d);
      leaf.buildingId = a.buildingId();
      leaf.demand = d.flow();
      leaf.entryWall =
          a.gate == null
              ? null
              : a.gate.forDiameter(Rules.diameter(d.flow())).wallFor(chosen[i].approach);
      n.nodes.put(leaf.id, leaf);
      n.connected.add(d.id);
    }
    for (int start = 0; start < parent.length; start++) {
      if (!n.nodes.containsKey("grid_" + start)) continue;
      for (int first : children.getOrDefault(start, List.of())) {
        List<Coordinate> line = new ArrayList<>();
        line.add(points[start]);
        int at = first;
        while (at >= 0 && !n.nodes.containsKey("grid_" + at)) {
          line.add(points[at]);
          List<Integer> tail = children.getOrDefault(at, List.of());
          if (tail.size() != 1) throw new IllegalStateException("Invalid corridor degree");
          at = tail.get(0);
        }
        String end;
        if (at < 0) {
          int index = -at - 1;
          end = "entry_" + index;
          Coordinate[] tail = chosen[index].approach.getCoordinates();
          for (int j = tail.length - 2; j >= 0; j--) line.add(tail[j]);
        } else {
          end = "grid_" + at;
          line.add(points[at]);
        }
        LineString geometry = compact(line);
        String id = n.next("corridor");
        n.edges.put(id, new Network.Edge(id, "grid_" + start, end, geometry));
      }
    }
    return n;
  }

  private static LineString compact(List<Coordinate> input) {
    List<Coordinate> points = new ArrayList<>();
    for (Coordinate p : input) {
      if (!points.isEmpty() && points.get(points.size() - 1).distance(p) < 1e-7) continue;
      while (points.size() > 1) {
        Coordinate a = points.get(points.size() - 2), b = points.get(points.size() - 1);
        double cross = (b.x - a.x) * (p.y - b.y) - (b.y - a.y) * (p.x - b.x),
            dot = (b.x - a.x) * (p.x - b.x) + (b.y - a.y) * (p.y - b.y);
        if (Math.abs(cross) > 1e-6 || dot < 0) break;
        points.remove(points.size() - 1);
      }
      points.add(p.copy());
    }
    return Geo.line(points.toArray(new Coordinate[0]));
  }

  private Coordinate frame(Coordinate p) {
    double dx = p.x - root.point.x, dy = p.y - root.point.y;
    return new Coordinate(dx * c + dy * s, -dx * s + dy * c);
  }

  private Coordinate world(double x, double y) {
    return new Coordinate(root.point.x + x * c - y * s, root.point.y + x * s + y * c);
  }

  private static double[] distinct(TreeSet<Double> source) {
    List<Double> out = new ArrayList<>();
    for (double value : source)
      if (out.isEmpty() || Math.abs(value - out.get(out.size() - 1)) > 1e-5) out.add(value);
    return out.stream().mapToDouble(Double::doubleValue).toArray();
  }

  private static int nearest(double[] values, double value) {
    int at = Arrays.binarySearch(values, value);
    if (at >= 0) return at;
    at = -at - 1;
    if (at == 0) return 0;
    if (at == values.length) return at - 1;
    return Math.abs(values[at] - value) < Math.abs(values[at - 1] - value) ? at : at - 1;
  }
}
