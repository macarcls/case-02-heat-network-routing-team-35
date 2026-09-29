package ru.teplotrassa.engine;

import java.util.*;
import java.util.function.BooleanSupplier;
import org.locationtech.jts.geom.*;
import ru.teplotrassa.data.*;

/** Territory-wide advisory graph. Its grid edges are never exported as actual pipes. */
public final class CorridorGraph {
  private static final int[] DX = {1, 1, 0, -1, -1, -1, 0, 1};
  private static final int[] DY = {0, 1, 1, 1, 0, -1, -1, -1};
  private final Coordinate[] points;
  private final boolean[] open, skeleton;
  private final double[][] weights;
  private final int cols, rows;
  private final double minX, minY, step;
  private final BooleanSupplier stop;
  private final Map<Integer, double[]> distanceCache =
      new LinkedHashMap<Integer, double[]>() {
        protected boolean removeEldestEntry(Map.Entry<Integer, double[]> e) {
          return size() > 64;
        }
      };
  private final Map<String, Integer> snaps = new HashMap<>();
  private int freeNodes, denseEdges, retainedEdges, anchors, components;

  public CorridorGraph(
      FeatureStore store,
      List<Coordinate> terminals,
      Planner.Options options,
      BooleanSupplier stop) {
    this.stop = stop;
    Envelope box = new Envelope();
    for (Coordinate c : terminals) box.expandToInclude(c);
    List<Coordinate> roots = new ArrayList<>();
    for (Feature f : store.all("heat_network")) {
      box.expandToInclude(f.geometry.getEnvelopeInternal());
      LineString line = (LineString) f.geometry;
      for (double at : new double[] {0, line.getLength() / 2, line.getLength()})
        roots.add(Geo.at(line, at));
    }
    for (Feature f : store.all("heat_chamber")) {
      roots.add(f.geometry.getCoordinate());
      box.expandToInclude(f.geometry.getCoordinate());
    }
    if (box.isNull()) box = new Envelope(0, 1, 0, 1);
    box.expandBy(80);
    double spacing = Math.max(options.gridM, 5);
    while ((Math.ceil(box.getWidth() / spacing) + 1) * (Math.ceil(box.getHeight() / spacing) + 1)
        > options.treeGraphMaxNodes) spacing *= 1.1;
    step = spacing;
    minX = box.getMinX();
    minY = box.getMinY();
    cols = (int) Math.ceil(box.getWidth() / step) + 1;
    rows = (int) Math.ceil(box.getHeight() / step) + 1;
    points = new Coordinate[cols * rows];
    open = new boolean[points.length];
    skeleton = new boolean[points.length];
    weights = new double[points.length][8];
    SpatialRules spatial = new SpatialRules(store.near(box), true);
    // A small pipe gives an optimistic overview; actual DN and all geometry are checked later.
    int dn = Rules.DN[0];
    for (int i = 0; i < points.length && !stop.getAsBoolean(); i++) {
      Coordinate p = new Coordinate(minX + i % cols * step, minY + i / cols * step);
      points[i] = p;
      open[i] = spatial.hardClear(Geo.line(p, p), dn, null, null);
      if (open[i]) freeNodes++;
    }
    if (stop.getAsBoolean()) return;
    List<double[]> edges = new ArrayList<>();
    for (int i = 0; i < points.length && !stop.getAsBoolean(); i++) {
      if (!open[i]) continue;
      for (int d = 0; d < 4; d++) {
        int j = neighbour(i, d);
        if (j < 0 || !open[j]) continue;
        LineString edge = Geo.line(points[i], points[j]);
        if (!spatial.hardClear(edge, dn, null, null)) continue;
        Coordinate mid =
            new Coordinate((points[i].x + points[j].x) / 2, (points[i].y + points[j].y) / 2);
        double cost =
            edge.getLength() * spatial.multiplier(mid, 0, options.mode.equals("depth"), dn);
        weights[i][d] = cost;
        weights[j][d + 4] = cost;
        edges.add(new double[] {cost, i, j});
      }
    }
    denseEdges = edges.size();
    if (stop.getAsBoolean()) return;
    boolean[] required = new boolean[points.length];
    List<Coordinate> all = new ArrayList<>(terminals);
    all.addAll(roots);
    for (Coordinate point : all) {
      int at = nearest(point);
      if (at >= 0) required[at] = true;
    }
    for (boolean value : required) if (value) anchors++;
    // Minimum spanning forest followed by non-terminal leaf removal. This is a corridor prior,
    // not an optimal Steiner tree or a hydraulically feasible district heating design.
    edges.sort(
        Comparator.comparingDouble((double[] e) -> e[0])
            .thenComparingDouble(e -> e[1])
            .thenComparingDouble(e -> e[2]));
    int[] parent = new int[points.length], degree = new int[points.length];
    List<List<Integer>> tree = new ArrayList<>();
    for (int i = 0; i < points.length; i++) {
      parent[i] = i;
      tree.add(new ArrayList<>());
    }
    for (double[] edge : edges) {
      if (stop.getAsBoolean()) return;
      int a = (int) edge[1], b = (int) edge[2], pa = root(parent, a), pb = root(parent, b);
      if (pa == pb) continue;
      parent[pa] = pb;
      tree.get(a).add(b);
      tree.get(b).add(a);
      degree[a]++;
      degree[b]++;
    }
    Set<Integer> regions = new HashSet<>();
    for (int i = 0; i < points.length; i++) if (open[i]) regions.add(root(parent, i));
    components = regions.size();
    ArrayDeque<Integer> leaves = new ArrayDeque<>();
    boolean[] removed = new boolean[points.length];
    for (int i = 0; i < points.length; i++) if (degree[i] <= 1 && !required[i]) leaves.add(i);
    while (!leaves.isEmpty()) {
      int i = leaves.remove();
      if (removed[i]) continue;
      removed[i] = true;
      for (int j : tree.get(i)) if (!removed[j] && --degree[j] <= 1 && !required[j]) leaves.add(j);
    }
    for (int i = 0; i < points.length; i++) {
      skeleton[i] = open[i] && !removed[i];
      if (skeleton[i]) for (int j : tree.get(i)) if (j > i && !removed[j]) retainedEdges++;
    }
  }

  private static int root(int[] parents, int i) {
    while (parents[i] != i) {
      parents[i] = parents[parents[i]];
      i = parents[i];
    }
    return i;
  }

  private int neighbour(int i, int d) {
    int x = i % cols + DX[d], y = i / cols + DY[d];
    return x < 0 || x >= cols || y < 0 || y >= rows ? -1 : y * cols + x;
  }

  private int nearest(Coordinate p) {
    String key = Geo.key(p);
    Integer old = snaps.get(key);
    if (old != null) return old;
    int x = Math.max(0, Math.min(cols - 1, (int) Math.round((p.x - minX) / step)));
    int y = Math.max(0, Math.min(rows - 1, (int) Math.round((p.y - minY) / step)));
    int best = -1;
    double distance = Double.POSITIVE_INFINITY;
    for (int r = 0; r < Math.max(cols, rows); r++) {
      for (int yy = Math.max(0, y - r); yy <= Math.min(rows - 1, y + r); yy++)
        for (int xx = Math.max(0, x - r); xx <= Math.min(cols - 1, x + r); xx++) {
          if (r > 0 && Math.abs(xx - x) != r && Math.abs(yy - y) != r) continue;
          int i = yy * cols + xx;
          if (open[i] && points[i].distance(p) < distance) {
            distance = points[i].distance(p);
            best = i;
          }
        }
      if (best >= 0 && r > distance / step + 2) break;
      if (stop.getAsBoolean()) return -1;
    }
    snaps.put(key, best);
    return best;
  }

  public double distance(Coordinate a, Coordinate b) {
    int source = nearest(a), target = nearest(b);
    if (source < 0 || target < 0 || stop.getAsBoolean()) return a.distance(b);
    double[] ds = distanceCache.get(source);
    if (ds == null) {
      ds = new double[points.length];
      Arrays.fill(ds, Double.POSITIVE_INFINITY);
      ds[source] = 0;
      PriorityQueue<double[]> queue = new PriorityQueue<>(Comparator.comparingDouble(v -> v[0]));
      queue.add(new double[] {0, source});
      while (!queue.isEmpty() && !stop.getAsBoolean()) {
        double[] v = queue.remove();
        int i = (int) v[1];
        if (v[0] > ds[i]) continue;
        for (int d = 0; d < 8; d++) {
          if (weights[i][d] <= 0) continue;
          int j = neighbour(i, d);
          double cost = v[0] + weights[i][d];
          if (cost < ds[j]) {
            ds[j] = cost;
            queue.add(new double[] {cost, j});
          }
        }
      }
      if (!stop.getAsBoolean()) distanceCache.put(source, ds);
    }
    // A disconnected coarse graph never excludes an exact candidate.
    return Double.isFinite(ds[target])
        ? ds[target] + a.distance(points[source]) + b.distance(points[target])
        : a.distance(b) + 4 * step;
  }

  public boolean onSkeleton(Coordinate p) {
    int i = nearest(p);
    return i >= 0 && skeleton[i];
  }

  public Map<String, Object> diagnostics() {
    Map<String, Object> m = new LinkedHashMap<>();
    m.put("gridNodes", points.length);
    m.put("freeNodes", freeNodes);
    m.put("candidateEdges", denseEdges);
    m.put("skeletonEdges", retainedEdges);
    m.put("removedCandidateEdges", denseEdges - retainedEdges);
    m.put("components", components);
    m.put("anchorNodes", anchors);
    m.put("spacingM", step);
    m.put("role", "advisory_corridor_ranking_and_remaining_cost");
    m.put("exactRoutesRevalidated", true);
    m.put("pruning", "minimum_spanning_forest_then_nonterminal_leaf_removal");
    m.put("diffusionModelUsed", false);
    m.put("globalOptimalityProven", false);
    return m;
  }
}
