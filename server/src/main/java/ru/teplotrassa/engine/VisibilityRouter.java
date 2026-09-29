package ru.teplotrassa.engine;

import java.util.*;
import java.util.function.BooleanSupplier;
import org.locationtech.jts.geom.*;
import org.locationtech.jts.index.strtree.STRtree;
import org.locationtech.jts.operation.buffer.BufferParameters;
import ru.teplotrassa.data.*;

/** Finite, direction-state routing on obstacle/terminal ordinates. No endpoint snapping. */
public final class VisibilityRouter {
  private final List<Feature> objects;
  private final SpatialRules spatial;
  private final Planner.Options options;
  private final BooleanSupplier stop;
  private final STRtree occupied = new STRtree();
  private final Map<LineString, Double> assessedCosts = new IdentityHashMap<>();
  public int graphSearches, graphCapacitySkips, expandedStates, checkedRoutes;

  public VisibilityRouter(
      FeatureStore store,
      Envelope area,
      Collection<LineString> pipes,
      Planner.Options options,
      BooleanSupplier stop) {
    this.objects = store.near(SpatialRules.expand(area, 160));
    this.spatial = new SpatialRules(objects);
    this.options = options;
    this.stop = stop;
    for (LineString line : pipes) occupied.insert(line.getEnvelopeInternal(), line);
    occupied.build();
  }

  /** Routes point-to-parent, so start is the only endpoint allowed through its own wall. */
  public List<LineString> routes(
      Coordinate start,
      Coordinate goal,
      int dn,
      String building,
      LineString wall,
      double axis,
      Double receivingAxis,
      LineString previous,
      boolean graph) {
    if (start.distance(goal) < .05 || stop.getAsBoolean()) return List.of();
    List<LineString> proposals = new ArrayList<>(RouteFinder.connections(start, goal, axis));
    // Both 45-degree and orthogonal families are legal in the same root direction family.
    proposals.addAll(RouteFinder.connections(start, goal, axis + Math.PI / 4));
    if (previous != null) {
      Coordinate[] old = previous.getCoordinates();
      if (old[0].distance(start) < 1e-5) {
        proposals.add(previous);
        for (int i = 1; i < old.length; i++) {
          for (LineString suffix : RouteFinder.connections(old[i], goal, axis)) {
            List<Coordinate> joined = new ArrayList<>(Arrays.asList(old).subList(0, i));
            joined.addAll(Arrays.asList(suffix.getCoordinates()));
            proposals.add(compact(joined));
          }
        }
      }
    }
    List<LineString> valid = filter(proposals, start, goal, dn, building, wall, receivingAxis);
    if (graph && !stop.getAsBoolean()) {
      for (double margin : new double[] {30, 120}) {
        LineString path = search(start, goal, dn, building, axis, receivingAxis, previous, margin);
        if (path != null) {
          List<LineString> checked =
              filter(List.of(path), start, goal, dn, building, wall, receivingAxis);
          valid.addAll(checked);
          if (!checked.isEmpty()) break;
        }
        if (!valid.isEmpty() || stop.getAsBoolean()) break;
      }
    }
    valid.sort(
        Comparator.comparingDouble((LineString l) -> objective(l, dn, receivingAxis))
            .thenComparingDouble(LineString::getLength)
            .thenComparing(LineString::toText));
    List<LineString> result = new ArrayList<>();
    for (LineString l : valid) {
      if (result.stream().noneMatch(p -> p.equalsExact(l, 1e-5))) result.add(l);
      if (result.size() == 3) break;
    }
    return result;
  }

  private List<LineString> filter(
      List<LineString> lines,
      Coordinate start,
      Coordinate goal,
      int dn,
      String building,
      LineString wall,
      Double receivingAxis) {
    lines = new ArrayList<>(lines);
    lines.sort(Comparator.comparingDouble(l -> objective(l, dn, receivingAxis)));
    Set<String> seen = new HashSet<>();
    List<LineString> valid = new ArrayList<>();
    for (LineString path : lines) {
      if (stop.getAsBoolean()) break;
      if (path == null
          || path.getLength() < .05
          || !seen.add(path.toText())
          || path.getCoordinateN(0).distance(start) > 1e-5
          || path.getCoordinateN(path.getNumPoints() - 1).distance(goal) > 1e-5
          || !RouteFinder.standardBends(path)
          || !path.isSimple()) continue;
      Coordinate a = path.getCoordinateN(path.getNumPoints() - 2);
      double bearing = Math.atan2(goal.y - a.y, goal.x - a.x);
      if (receivingAxis != null && !RouteFinder.standardAngle(bearing, receivingAxis)) continue;
      checkedRoutes++;
      try {
        if (!occupiedClear(path, start, goal)) continue;
        SpatialRules.Assessment assessment = spatial.assess(path, dn, building, goal, start, wall);
        if (assessment.valid()) {
          double cost = 0;
          for (DepthPlanner.Section section : DepthPlanner.base(path, assessment.passages))
            cost += (section.to - section.from) * section.factor * Rules.NEW[Rules.index(dn)];
          assessedCosts.put(path, cost);
          valid.add(path);
        }
      } catch (IllegalArgumentException | TopologyException rejected) {
        // A candidate never relaxes the authoritative vector rules.
      }
    }
    return valid;
  }

  private double objective(LineString path, int dn, Double receivingAxis) {
    if (path == null) return Double.POSITIVE_INFINITY;
    double turns = RoutingQuality.bends(path).equivalentM;
    int last = path.getNumPoints() - 1;
    Coordinate a = path.getCoordinateN(last - 1), b = path.getCoordinateN(last);
    turns += RoutingQuality.connectionPenalty(Math.atan2(b.y - a.y, b.x - a.x), receivingAxis);
    return options.score(
        assessedCosts.getOrDefault(path, Rules.NEW[Rules.index(dn)] * path.getLength()),
        path.getLength() + turns);
  }

  private boolean occupiedClear(LineString line, Coordinate start, Coordinate goal) {
    for (Object value : occupied.query(line.getEnvelopeInternal())) {
      Geometry hit = line.intersection((LineString) value);
      if (hit.isEmpty()) continue;
      if (hit.getDimension() > 0) return false;
      for (Coordinate p : hit.getCoordinates())
        if (p.distance(start) > 1e-5 && p.distance(goal) > 1e-5) return false;
    }
    return true;
  }

  private LineString search(
      Coordinate start,
      Coordinate goal,
      int dn,
      String building,
      double axis,
      Double receivingAxis,
      LineString previous,
      double margin) {
    graphSearches++;
    double c = Math.cos(axis), s = Math.sin(axis);
    Coordinate end = local(goal, start, c, s);
    Envelope box = new Envelope(new Coordinate(0, 0), end);
    if (previous != null)
      for (Coordinate p : previous.getCoordinates()) box.expandToInclude(local(p, start, c, s));
    box.expandBy(margin);
    TreeSet<Double> xx = new TreeSet<>(), yy = new TreeSet<>();
    xx.add(0d);
    yy.add(0d);
    xx.add(end.x);
    yy.add(end.y);
    xx.add(box.getMinX());
    xx.add(box.getMaxX());
    yy.add(box.getMinY());
    yy.add(box.getMaxY());
    if (previous != null)
      for (Coordinate p : previous.getCoordinates()) {
        Coordinate q = local(p, start, c, s);
        xx.add(q.x);
        yy.add(q.y);
      }
    for (Feature f : objects) {
      boolean hard = BuildingAccess.isBuilding(f);
      Rules.Restriction rule = Rules.RESTRICTIONS.get(f.restriction());
      if (!hard && (rule == null || !rule.hard)) continue;
      double gap =
          (hard ? Rules.buildingClearance(dn) : rule.clearance) + Rules.width(dn) / 2 + .02;
      // Mitred offset vertices preserve concave pockets; a building's bounding box does not.
      BufferParameters params =
          new BufferParameters(1, BufferParameters.CAP_SQUARE, BufferParameters.JOIN_MITRE, 5);
      Geometry offset =
          org.locationtech.jts.operation.buffer.BufferOp.bufferOp(f.geometry, gap, params);
      for (Coordinate p : offset.getCoordinates()) {
        Coordinate q = local(p, start, c, s);
        if (box.contains(q)) {
          xx.add(q.x);
          yy.add(q.y);
        }
      }
    }
    double[] xs = ordinates(xx, 0, end.x), ys = ordinates(yy, 0, end.y);
    long count = (long) xs.length * ys.length;
    if (count > options.maxCells || count > 80000) {
      graphCapacitySkips++;
      return null;
    }
    int cols = xs.length, rows = ys.length, n = (int) count;
    int begin = nearest(xs, 0) + cols * nearest(ys, 0);
    int target = nearest(xs, end.x) + cols * nearest(ys, end.y);
    Coordinate[] points = new Coordinate[n];
    for (int i = 0; i < n; i++) points[i] = world(xs[i % cols], ys[i / cols], start, c, s);
    points[begin] = start.copy();
    points[target] = goal.copy();
    BuildingAccess.Gate gate = spatial.entryGate(building, start, dn);
    if (gate != null) gate.prepareSearch(dn, goal, axis, axis);
    double[][] weights = new double[n][2];
    double[] dist = new double[n * 4];
    Arrays.fill(dist, Double.POSITIVE_INFINITY);
    int[] prev = new int[n * 4];
    Arrays.fill(prev, -1);
    PriorityQueue<double[]> queue =
        new PriorityQueue<>(
            Comparator.comparingDouble((double[] a) -> a[0]).thenComparingDouble(a -> a[1]));
    double metre = options.score(Rules.NEW[Rules.index(dn)], 1);
    for (int d = 0; d < 4; d++) {
      dist[begin * 4 + d] = 0;
      queue.add(new double[] {start.distance(goal) * metre, begin * 4 + d, 0});
    }
    int winner = -1;
    double best = Double.POSITIVE_INFINITY;
    while (!queue.isEmpty() && !stop.getAsBoolean()) {
      double[] item = queue.remove();
      int state = (int) item[1], at = state / 4, heading = state % 4;
      if (item[2] > dist[state] + 1e-10) continue;
      // Euclidean distance omits bends and crossing costs, so this is an admissible bound.
      if (item[0] >= best - 1e-10) break;
      expandedStates++;
      if (at == target) {
        double value =
            dist[state]
                + options.score(
                    0,
                    RoutingQuality.connectionPenalty(axis + heading * Math.PI / 2, receivingAxis));
        if (value < best) {
          best = value;
          winner = state;
        }
        continue;
      }
      for (int d = 0; d < 4; d++) {
        if (d == (heading + 2) % 4 && at != begin) continue;
        int x = at % cols + (d == 0 ? 1 : d == 2 ? -1 : 0),
            y = at / cols + (d == 1 ? 1 : d == 3 ? -1 : 0);
        if (x < 0 || y < 0 || x >= cols || y >= rows) continue;
        int next = y * cols + x, key = Math.min(at, next), h = d % 2;
        if (weights[key][h] == 0) {
          LineString edge = Geo.line(points[at], points[next]);
          try {
            if (!spatial.hardClear(edge, dn, building, start) || !occupiedClear(edge, start, goal))
              weights[key][h] = -1;
            else {
              Coordinate mid =
                  new Coordinate(
                      (points[at].x + points[next].x) / 2, (points[at].y + points[next].y) / 2);
              double factor = spatial.multiplier(mid, 0, options.mode.equals("depth"), dn);
              weights[key][h] =
                  options.score(
                      edge.getLength() * Rules.NEW[Rules.index(dn)] * factor, edge.getLength());
            }
          } catch (IllegalArgumentException | TopologyException e) {
            weights[key][h] = -1;
          }
        }
        if (weights[key][h] < 0) continue;
        double value =
            dist[state]
                + weights[key][h]
                + (d == heading ? 0 : options.score(0, RoutingQuality.TURN_90_M));
        int ns = next * 4 + d;
        if (value + 1e-10 < dist[ns]) {
          dist[ns] = value;
          prev[ns] = state;
          queue.add(new double[] {value + points[next].distance(goal) * metre, ns, value});
        }
      }
    }
    if (winner < 0) return null;
    List<Coordinate> line = new ArrayList<>();
    for (int state = winner; state >= 0; state = prev[state]) line.add(points[state / 4]);
    Collections.reverse(line);
    return compact(line);
  }

  public static LineString compact(List<Coordinate> points) {
    List<Coordinate> result = new ArrayList<>();
    for (Coordinate p : points) {
      if (!result.isEmpty() && result.get(result.size() - 1).distance(p) < 1e-7) continue;
      while (result.size() > 1) {
        Coordinate a = result.get(result.size() - 2), b = result.get(result.size() - 1);
        double ax = b.x - a.x, ay = b.y - a.y, bx = p.x - b.x, by = p.y - b.y;
        if (Math.abs(ax * by - ay * bx)
                > 1e-7 * Math.max(1, Math.hypot(ax, ay) * Math.hypot(bx, by))
            || ax * bx + ay * by <= 0) break;
        result.remove(result.size() - 1);
      }
      result.add(p.copy());
    }
    if (result.size() == 1) result.add(result.get(0).copy());
    return Geo.line(result.toArray(new Coordinate[0]));
  }

  private static Coordinate local(Coordinate p, Coordinate origin, double c, double s) {
    double x = p.x - origin.x, y = p.y - origin.y;
    return new Coordinate(x * c + y * s, -x * s + y * c);
  }

  private static Coordinate world(double x, double y, Coordinate origin, double c, double s) {
    return new Coordinate(origin.x + x * c - y * s, origin.y + x * s + y * c);
  }

  private static double[] ordinates(TreeSet<Double> values, double a, double b) {
    List<Double> out = new ArrayList<>();
    for (double v : values) {
      if (Math.abs(v - a) < 1e-6) v = a;
      if (Math.abs(v - b) < 1e-6) v = b;
      if (out.isEmpty() || v - out.get(out.size() - 1) > 1e-7) out.add(v);
    }
    return out.stream().mapToDouble(v -> v).toArray();
  }

  private static int nearest(double[] values, double v) {
    int best = 0;
    for (int i = 1; i < values.length; i++)
      if (Math.abs(values[i] - v) < Math.abs(values[best] - v)) best = i;
    return best;
  }
}
