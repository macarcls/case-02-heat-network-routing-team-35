package ru.teplotrassa.engine;

import java.util.*;
import java.util.function.BooleanSupplier;
import org.locationtech.jts.geom.*;

/**
 * Direction-state A*: exact target coordinates, straight runs and 90-degree bends. Grid coordinates
 * include both terminals; neither terminal is snapped to a cell. A direct route is considered
 * first. Every returned route passes vector checks.
 */
public final class RouteFinder {
  private final SpatialRules spatial;
  private final BooleanSupplier stop;
  private double maxPhysicalLength = Double.POSITIVE_INFINITY;
  private final org.locationtech.jts.index.strtree.STRtree occupied =
      new org.locationtech.jts.index.strtree.STRtree();

  public RouteFinder(SpatialRules spatial, BooleanSupplier stop) {
    this(spatial, stop, List.of());
  }

  public RouteFinder(SpatialRules spatial, BooleanSupplier stop, Collection<LineString> pipes) {
    this.spatial = spatial;
    this.stop = stop;
    for (LineString pipe : pipes) occupied.insert(pipe.getEnvelopeInternal(), pipe);
    occupied.build();
  }

  /** Necessary physical bound supplied by the diameter model, never a time/search budget. */
  public RouteFinder withMaxLength(double metres) {
    if (!Double.isFinite(metres) || metres <= 0)
      throw new IllegalArgumentException("Route length bound must be positive and finite");
    maxPhysicalLength = metres;
    return this;
  }

  public LineString route(
      Coordinate start,
      Coordinate goal,
      int dn,
      String leafId,
      int mode,
      boolean depth,
      double grid,
      int maxCells) {
    return route(start, goal, dn, leafId, mode, depth, grid, maxCells, null);
  }

  public LineString route(
      Coordinate start,
      Coordinate goal,
      int dn,
      String leafId,
      int mode,
      boolean depth,
      double grid,
      int maxCells,
      Double approachAxis) {
    if (start.distance(goal) < .001
        || start.distance(goal) > maxPhysicalLength + .001
        || !spatial.targetClear(goal, dn, leafId)) return null;
    LineString direct = Geo.line(start, goal);
    SpatialRules.Assessment directCheck = spatial.assess(direct, dn, leafId, goal, start);
    if ((spatial.queryIndependent
            ? occupiedClear(direct, start, goal)
            : clear(direct, dn, leafId, start, goal))
        && directCheck.valid()
        && (approachAxis == null
            || standardAngle(Math.atan2(goal.y - start.y, goal.x - start.x), approachAxis))
        && (mode == 0 || directCheck.passages.isEmpty())) return direct;
    // Exact one/two-bend connections precede discretisation. This avoids staircases
    // in open ground merely because terminals fall on different grid ordinates.
    double basis = approachAxis == null ? 0 : approachAxis;
    LineString simpleBest = null;
    double simpleCost = Double.POSITIVE_INFINITY;
    for (LineString candidate : connections(start, goal, basis)) {
      if (stop.getAsBoolean()) return null;
      if (!valid(candidate, dn, leafId, start, goal, approachAxis)) continue;
      double cost = objective(candidate, dn, leafId, start, goal, mode, approachAxis);
      if (cost < simpleCost) {
        simpleBest = candidate;
        simpleCost = cost;
      }
    }
    if (simpleBest != null) return simpleBest;
    double theta =
        approachAxis != null
            ? approachAxis + (mode == 2 ? Math.PI / 4 : 0)
            : mode == 1 ? 0 : Math.atan2(goal.y - start.y, goal.x - start.x);
    BuildingAccess.Gate gate = spatial.entryGate(leafId, start, dn);
    if (gate != null) gate.prepareSearch(dn, goal, basis, theta);
    LineString routed =
        gridRoute(
            start, start, goal, dn, leafId, mode, depth, grid, maxCells, approachAxis, theta, null);
    if (routed != null) return routed;
    if (gate == null) return null;
    // A tight entrance can need 45-degree turns just outside the chosen wall.
    // Solve this short approach exactly, then seed the orthogonal search beyond
    // the clearance boundary. Arbitrary-angle diagonal cell hops are not used.
    LineString best = null;
    double bestCost = Double.POSITIVE_INFINITY;
    List<LineString> prefixes = new ArrayList<>();
    List<Double> bearings = new ArrayList<>();
    for (int direction = 0; direction < 8; direction++)
      bearings.add(theta + direction * Math.PI / 4);
    for (LineString wall : gate.walls)
      for (double position : new double[] {.25, .5, .75}) {
        Coordinate point = Geo.at(wall, wall.getLength() * position);
        bearings.add(Math.atan2(point.y - start.y, point.x - start.x));
      }
    for (double bearing : bearings) {
      Coordinate portal =
          gate.crossingToward(
              new Coordinate(start.x + Math.cos(bearing), start.y + Math.sin(bearing)));
      if (portal == null) continue;
      for (double offset : new double[] {.05, Rules.width(dn) / 2 + .1, 1}) {
        if (stop.getAsBoolean()) return null;
        double distance = start.distance(portal) + offset;
        Coordinate anchor =
            new Coordinate(
                start.x + distance * Math.cos(bearing), start.y + distance * Math.sin(bearing));
        if (!clear(Geo.line(start, anchor), dn, leafId, start, goal)) continue;
        for (Coordinate outside : gate.outsideCandidates(portal, dn)) {
          List<LineString> approaches = connections(anchor, outside, bearing);
          approaches.addAll(connections(anchor, outside, bearing + Math.PI / 4));
          for (LineString approach : approaches) {
            List<Coordinate> points = new ArrayList<>();
            points.add(start);
            points.addAll(Arrays.asList(approach.getCoordinates()));
            LineString prefix = compact(points);
            if (!valid(prefix, dn, leafId, start, goal, null)) continue;
            if (prefixes.stream().noneMatch(p -> p.equalsExact(prefix, 1e-5))) prefixes.add(prefix);
          }
        }
      }
    }
    prefixes.sort(
        Comparator.comparingDouble(p -> p.getLength() + RoutingQuality.bends(p).equivalentM));
    for (LineString prefix : prefixes.subList(0, Math.min(6, prefixes.size()))) {
      Coordinate anchor = prefix.getCoordinateN(prefix.getNumPoints() - 1);
      LineString candidate =
          gridRoute(
              anchor,
              start,
              goal,
              dn,
              leafId,
              mode,
              depth,
              grid,
              maxCells,
              approachAxis,
              theta,
              prefix);
      if (candidate == null) continue;
      double cost = objective(candidate, dn, leafId, start, goal, mode, approachAxis);
      if (cost < bestCost) {
        bestCost = cost;
        best = candidate;
      }
    }
    return best;
  }

  private LineString gridRoute(
      Coordinate origin,
      Coordinate start,
      Coordinate goal,
      int dn,
      String leafId,
      int mode,
      boolean depth,
      double grid,
      int maxCells,
      Double approachAxis,
      double theta,
      LineString prefix) {
    double remainingLength = maxPhysicalLength - (prefix == null ? 0 : prefix.getLength());
    if (origin.distance(goal) > remainingLength + .001) return null;
    double cos = Math.cos(theta), sin = Math.sin(theta);
    Double initialBearing =
        prefix == null
            ? null
            : Math.atan2(
                origin.y - prefix.getCoordinateN(prefix.getNumPoints() - 2).y,
                origin.x - prefix.getCoordinateN(prefix.getNumPoints() - 2).x);
    Coordinate target =
        new Coordinate(
            (goal.x - origin.x) * cos + (goal.y - origin.y) * sin,
            -(goal.x - origin.x) * sin + (goal.y - origin.y) * cos);
    List<Double> entryXs = new ArrayList<>(), entryYs = new ArrayList<>();
    BuildingAccess.Gate gate = spatial.entryGate(leafId, start, dn);
    if (gate != null && initialBearing == null) {
      // Put real wall crossings on the grid. A 20 m step must not jump past a
      // narrow allowed wall window or force the first bend to stay inside the building.
      for (int direction = 0; direction < 4; direction++) {
        double bearing = theta + direction * Math.PI / 2;
        Coordinate portal =
            gate.crossingToward(
                new Coordinate(start.x + Math.cos(bearing), start.y + Math.sin(bearing)));
        if (portal == null) continue;
        double distance = start.distance(portal);
        List<Double> values = direction % 2 == 0 ? entryXs : entryYs;
        double sign = direction < 2 ? 1 : -1;
        for (double offset :
            new double[] {
              .01,
              Rules.width(dn) / 2,
              1,
              2,
              Rules.buildingClearance(dn) + Rules.width(dn) / 2 + .1,
              SpatialRules.approachLength(dn)
            }) values.add(sign * (distance + offset));
      }
      if (entryXs.isEmpty() && entryYs.isEmpty()) return null;
    }
    // Failure is bounded search failure, never proof of physical impossibility.
    // Retry narrow corridors at a finer resolution before enlarging the region.
    for (double[] search :
        new double[][] {
          {40, grid},
          {40, Math.min(grid, 5)},
          {120, Math.min(grid, 5)},
          {350, Math.min(grid, 5)},
          {700, Math.min(grid, 5)}
        }) {
      double margin = search[0];
      if (stop.getAsBoolean()) return null;
      double minX = Math.min(0, target.x) - margin,
          maxX = Math.max(0, target.x) + margin,
          minY = Math.min(0, target.y) - margin,
          maxY = Math.max(0, target.y) + margin;
      if (Double.isFinite(remainingLength)) {
        // Every feasible point is in this ellipse's bounding square. The finer
        // ellipse check below uses only geometric distances, never bend weights.
        double radius = (remainingLength + .001) / 2;
        minX = Math.max(minX, target.x / 2 - radius);
        maxX = Math.min(maxX, target.x / 2 + radius);
        minY = Math.max(minY, target.y / 2 - radius);
        maxY = Math.min(maxY, target.y / 2 + radius);
      }
      double step = Math.max(search[1], Math.sqrt((maxX - minX) * (maxY - minY) / (maxCells * .8)));
      double[] xs = axis(minX, maxX, step, 0, target.x, entryXs),
          ys = axis(minY, maxY, step, 0, target.y, entryYs);
      int cols = xs.length, rows = ys.length;
      if ((long) cols * rows > maxCells) continue;
      int sx = nearest(xs, 0),
          sy = nearest(ys, 0),
          gx = nearest(xs, target.x),
          gy = nearest(ys, target.y),
          source = sy * cols + sx,
          targetCell = gy * cols + gx;
      int states = cols * rows * 4;
      double[] cost = new double[states];
      Arrays.fill(cost, Double.POSITIVE_INFINITY);
      int[] parent = new int[states];
      Arrays.fill(parent, -1);
      byte[] valid = new byte[cols * rows * 4];
      double[] weights = new double[cols * rows * 4];
      PriorityQueue<double[]> heap =
          new PriorityQueue<>(
              Comparator.<double[]>comparingDouble(a -> a[0]).thenComparingDouble(a -> -a[1]));
      for (int d = 0; d < 4; d++) {
        int id = source * 4 + d;
        cost[id] = 0;
        heap.add(new double[] {Math.abs(target.x) + Math.abs(target.y), 0, id});
      }
      int[] dx = {1, 0, -1, 0}, dy = {0, 1, 0, -1};
      int found = -1, iterations = 0;
      while (!heap.isEmpty()) {
        double[] item = heap.poll();
        int state = (int) item[2];
        if (item[1] > cost[state] + 1e-8) continue;
        int cell = state / 4, heading = state % 4, x = cell % cols, y = cell / cols;
        if (cell == targetCell) {
          found = state;
          break;
        }
        if ((iterations++ & 255) == 0 && stop.getAsBoolean()) return null;
        Coordinate a = world(xs[x], ys[y], origin, cos, sin);
        for (int direction = 0; direction < 4; direction++) {
          if (direction == (heading + 2) % 4 && cell != source) continue;
          int nx = x + dx[direction], ny = y + dy[direction];
          if (nx < 0 || nx >= cols || ny < 0 || ny >= rows) continue;
          int neighbour = ny * cols + nx,
              next = neighbour * 4 + direction,
              edgeKey = cell * 4 + direction;
          Coordinate b = world(xs[nx], ys[ny], origin, cos, sin);
          if (origin.distance(b) + b.distance(goal) > remainingLength + .001) continue;
          if (valid[edgeKey] == 0)
            valid[edgeKey] = (byte) (clear(Geo.line(a, b), dn, leafId, start, goal) ? 1 : 2);
          if (valid[edgeKey] == 2) continue;
          // Tie and branching costs are evaluated in full by Evaluation. This is
          // a local routing objective with a bend term, not the final ranking score.
          double weight = weights[edgeKey];
          if (weight == 0) {
            weight =
                spatial.multiplier(
                    new Coordinate((a.x + b.x) / 2, (a.y + b.y) / 2), mode, depth, dn);
            weights[edgeKey] = weight;
          }
          double proposed =
              cost[state]
                  + a.distance(b) * weight
                  + (direction != heading && cell != source ? RoutingQuality.TURN_90_M : 0)
                  + (cell == source && initialBearing != null
                      ? RoutingQuality.penalty(
                          RoutingQuality.change(initialBearing, Math.atan2(b.y - a.y, b.x - a.x)))
                      : 0)
                  + (neighbour == targetCell
                      ? RoutingQuality.connectionPenalty(
                          Math.atan2(b.y - a.y, b.x - a.x), approachAxis)
                      : 0);
          if (proposed < cost[next] - 1e-9) {
            cost[next] = proposed;
            parent[next] = state;
            heap.add(
                new double[] {
                  proposed
                      + remainingCost(
                          target.x - xs[nx], target.y - ys[ny], direction, theta, approachAxis),
                  proposed,
                  next
                });
          }
        }
      }
      if (found < 0) continue;
      List<Coordinate> path = new ArrayList<>();
      for (int state = found; state >= 0; state = parent[state]) {
        int cell = state / 4;
        path.add(world(xs[cell % cols], ys[cell / cols], origin, cos, sin));
      }
      Collections.reverse(path);
      path.set(0, origin.copy());
      path.set(path.size() - 1, goal.copy());
      if (prefix != null) {
        List<Coordinate> full = new ArrayList<>(Arrays.asList(prefix.getCoordinates()));
        full.remove(full.size() - 1);
        full.addAll(path);
        path = full;
      }
      List<Coordinate> simple = new ArrayList<>();
      for (Coordinate p : path) {
        while (simple.size() >= 2
            && collinear(simple.get(simple.size() - 2), simple.get(simple.size() - 1), p))
          simple.remove(simple.size() - 1);
        simple.add(p);
      }
      LineString result = Geo.line(simple.toArray(new Coordinate[0]));
      if (valid(result, dn, leafId, start, goal, approachAxis))
        return simplify(result, dn, leafId, start, goal, approachAxis);
    }
    return null;
  }

  /**
   * Lower bound for the four-direction grid, including unavoidable future bends. Opposite headings
   * are deliberately treated as one axis: permitting a free reversal can only lower the bound.
   * Terrain costs are at least one per metre.
   */
  static double remainingCost(double dx, double dy, int heading, double theta, Double axis) {
    double length = Math.abs(dx) + Math.abs(dy);
    if (length < 1e-8) return 0; // arrival connection is already charged in g
    double xConnection = RoutingQuality.connectionPenalty(theta, axis);
    double yConnection = RoutingQuality.connectionPenalty(theta + Math.PI / 2, axis);
    int currentAxis = heading % 2;
    if (Math.abs(dy) < 1e-8)
      return length + (currentAxis == 0 ? 0 : RoutingQuality.TURN_90_M) + xConnection;
    if (Math.abs(dx) < 1e-8)
      return length + (currentAxis == 1 ? 0 : RoutingQuality.TURN_90_M) + yConnection;
    double finishX = (currentAxis == 0 ? 2 : 1) * RoutingQuality.TURN_90_M + xConnection;
    double finishY = (currentAxis == 1 ? 2 : 1) * RoutingQuality.TURN_90_M + yConnection;
    return length + Math.min(finishX, finishY);
  }

  private boolean clear(LineString line, int dn, String leaf, Coordinate entry, Coordinate goal) {
    return spatial.hardClear(line, dn, leaf, entry) && occupiedClear(line, entry, goal);
  }

  private boolean occupiedClear(LineString line, Coordinate entry, Coordinate goal) {
    Envelope envelope = new Envelope(line.getEnvelopeInternal());
    envelope.expandBy(1e-5);
    for (Object item : occupied.query(envelope)) {
      LineString other = (LineString) item;
      Geometry hit = line.intersection(other);
      if (!hit.isEmpty() && hit.getDimension() > 0) return false;
      for (Coordinate c : hit.getCoordinates())
        if (c.distance(goal) > .001 && c.distance(entry) > .001) return false;
      if (line.isWithinDistance(other, 1e-5)) {
        Geometry nearby =
            line.intersection(occupiedTolerance.computeIfAbsent(other, g -> g.buffer(1e-5, 1)));
        for (Coordinate c : nearby.getCoordinates())
          if (c.distance(goal) > .001 && c.distance(entry) > .001) return false;
      }
    }
    return true;
  }

  private final Map<LineString, Geometry> occupiedTolerance = new IdentityHashMap<>();

  private boolean valid(
      LineString line, int dn, String leaf, Coordinate entry, Coordinate goal, Double axis) {
    if (line.getLength() < .001
        || line.getLength() > maxPhysicalLength + .001
        || !line.isSimple()
        || !standardBends(line)) return false;
    Coordinate[] c = line.getCoordinates();
    if (axis != null
        && !standardAngle(
            Math.atan2(
                c[c.length - 1].y - c[c.length - 2].y, c[c.length - 1].x - c[c.length - 2].x),
            axis)) return false;
    // Full paths use the exact assessment, independent of a previous grid query's envelope.
    return (spatial.queryIndependent
            ? occupiedClear(line, entry, goal)
            : clear(line, dn, leaf, entry, goal))
        && spatial.assess(line, dn, leaf, goal, entry).valid();
  }

  public boolean canReuse(
      LineString line, int dn, String leaf, Coordinate entry, Coordinate goal, Double axis) {
    return valid(line, dn, leaf, entry, goal, axis);
  }

  /** Prefer fewer/easier turns by the same objective as search, keeping every hard constraint. */
  public LineString simplify(
      LineString line, int dn, String leaf, Coordinate entry, Coordinate goal, Double axis) {
    LineString current = line;
    boolean changed;
    do {
      changed = false;
      Coordinate[] points = current.getCoordinates();
      double oldObjective = objective(current, dn, leaf, entry, goal, 0, axis);
      outer:
      for (int span = points.length - 1; span >= 2; span--)
        for (int i = 0; i + span < points.length; i++) {
          if (stop.getAsBoolean()) return current;
          int j = i + span;
          double basis = Math.atan2(points[i + 1].y - points[i].y, points[i + 1].x - points[i].x);
          List<LineString> replacements = connections(points[i], points[j], basis);
          if (axis != null) replacements.addAll(connections(points[i], points[j], axis));
          for (LineString middle : replacements) {
            List<Coordinate> joined = new ArrayList<>();
            for (int k = 0; k < i; k++) joined.add(points[k]);
            joined.addAll(Arrays.asList(middle.getCoordinates()));
            for (int k = j + 1; k < points.length; k++) joined.add(points[k]);
            LineString replacement = compact(joined);
            if (replacement.getNumPoints() > points.length) continue;
            if (!valid(replacement, dn, leaf, entry, goal, axis)) continue;
            if (objective(replacement, dn, leaf, entry, goal, 0, axis) >= oldObjective - .01)
              continue;
            current = replacement;
            changed = true;
            break outer;
          }
        }
    } while (changed);
    return current;
  }

  private double objective(
      LineString line,
      int dn,
      String leaf,
      Coordinate entry,
      Coordinate goal,
      int mode,
      Double axis) {
    SpatialRules.Assessment check = spatial.assess(line, dn, leaf, goal, entry);
    TreeSet<Double> stations = new TreeSet<>(List.of(0d, line.getLength()));
    for (SpatialRules.Passage p : check.passages) {
      stations.add(p.from);
      stations.add(p.to);
    }
    double previous = 0, cost = 0;
    for (double at : stations) {
      double middle = (previous + at) / 2, factor = 1;
      for (SpatialRules.Passage p : check.passages)
        if (middle >= p.from && middle <= p.to)
          factor =
              Math.max(
                  factor,
                  p.rule.factor
                      * (mode == 1 && p.rule.polygon ? 3 : mode == 2 && !p.rule.polygon ? 4 : 1));
      cost += (at - previous) * factor;
      previous = at;
    }
    Coordinate a = line.getCoordinateN(line.getNumPoints() - 2),
        b = line.getCoordinateN(line.getNumPoints() - 1);
    return cost
        + RoutingQuality.bends(line).equivalentM
        + RoutingQuality.connectionPenalty(Math.atan2(b.y - a.y, b.x - a.x), axis);
  }

  public static boolean standardBends(LineString line) {
    Coordinate[] c = line.getCoordinates();
    for (int i = 1; i < c.length - 1; i++) {
      double ax = c[i].x - c[i - 1].x,
          ay = c[i].y - c[i - 1].y,
          bx = c[i + 1].x - c[i].x,
          by = c[i + 1].y - c[i].y;
      if (RoutingQuality.change(Math.atan2(ay, ax), Math.atan2(by, bx))
          > Math.PI / 2 + 1e-6) return false;
      if (Math.abs(ax * by - ay * bx) < 1e-6 && ax * bx + ay * by < 0) return false;
    }
    return true;
  }

  /** Simple one- and two-bend connectors; arbitrary legal bend angles also pass validation. */
  static List<LineString> connections(Coordinate a, Coordinate b, double theta) {
    double c = Math.cos(theta),
        s = Math.sin(theta),
        dx = b.x - a.x,
        dy = b.y - a.y,
        x = dx * c + dy * s,
        y = -dx * s + dy * c,
        d = Math.min(Math.abs(x), Math.abs(y)),
        u = Math.copySign(d, x),
        v = Math.copySign(d, y);
    List<LineString> paths = new ArrayList<>();
    paths.add(Geo.line(a, b));
    for (double[] corner : new double[][] {{x, 0}, {0, y}, {u, v}, {x - u, y - v}})
      paths.add(compact(List.of(a, world(corner[0], corner[1], a, c, s), b)));
    return paths;
  }

  private static LineString compact(List<Coordinate> points) {
    List<Coordinate> clean = new ArrayList<>();
    for (Coordinate p : points) {
      if (!clean.isEmpty() && p.distance(clean.get(clean.size() - 1)) < 1e-6) continue;
      while (clean.size() > 1
          && collinear(clean.get(clean.size() - 2), clean.get(clean.size() - 1), p))
        clean.remove(clean.size() - 1);
      clean.add(p.copy());
    }
    if (clean.size() == 1) clean.add(clean.get(0).copy());
    return Geo.line(clean.toArray(new Coordinate[0]));
  }

  public static boolean standardAngle(double a, double b) {
    // The receiving pipe is an unoriented axis. Any joining angle is within 90°
    // of one of its two directions; only turns within a LineString are restricted.
    return Double.isFinite(a) && Double.isFinite(b);
  }

  private static Coordinate world(double x, double y, Coordinate start, double c, double s) {
    return new Coordinate(start.x + x * c - y * s, start.y + x * s + y * c);
  }

  private static boolean collinear(Coordinate a, Coordinate b, Coordinate c) {
    return Math.abs((b.x - a.x) * (c.y - b.y) - (b.y - a.y) * (c.x - b.x)) < 1e-5;
  }

  private static int nearest(double[] values, double target) {
    int best = 0;
    for (int i = 1; i < values.length; i++)
      if (Math.abs(values[i] - target) < Math.abs(values[best] - target)) best = i;
    return best;
  }

  private static double[] axis(
      double min, double max, double step, double first, double last, List<Double> critical) {
    TreeSet<Double> values = new TreeSet<>();
    values.add(first);
    values.add(last);
    for (double x : critical) if (x >= min && x <= max) values.add(x);
    for (double x = min; x <= max + 1e-8; x += step)
      if (Math.abs(x - first) > .05 && Math.abs(x - last) > .05) values.add(x);
    return values.stream().mapToDouble(Double::doubleValue).toArray();
  }
}
