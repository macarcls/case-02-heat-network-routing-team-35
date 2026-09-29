package ru.teplotrassa.engine;

import java.util.*;
import java.util.function.*;
import org.locationtech.jts.geom.*;
import org.locationtech.jts.index.strtree.STRtree;
import ru.teplotrassa.data.*;

/** Aligns long common runs with nearby building walls without moving any terminal or chamber. */
public final class FacadeAlignment {
  private static final double SEARCH_M = 40;
  private static final double WALL_M = 30;
  private static final double RUN_M = 60;
  private static final double MAX_DIFFERENCE = Math.toRadians(6);
  private static final double MAX_ADAPTER = Math.toRadians(4);
  private static final double WALL_MATCH = Math.toRadians(.2);
  private static final double DRIFT_FREE_M = 1;
  private static final double DRIFT_WEIGHT = 16;

  private static final class Side {
    final LineString line;
    final double axis;
    final String buildingId;

    Side(LineString line, double axis, String buildingId) {
      this.line = line;
      this.axis = axis;
      this.buildingId = buildingId;
    }
  }

  public static final class Facade {
    public final double axis, difference, distance;
    public final String buildingId;

    private Facade(double axis, double difference, double distance, String buildingId) {
      this.axis = axis;
      this.difference = difference;
      this.distance = distance;
      this.buildingId = buildingId;
    }
  }

  private final FeatureStore store;
  private final BooleanSupplier cancelled;
  private final Function<Network, Evaluation> evaluate;
  public final Map<String, Object> diagnostics = new LinkedHashMap<>();

  public FacadeAlignment(
      FeatureStore store, BooleanSupplier cancelled, Function<Network, Evaluation> evaluate) {
    this.store = store;
    this.cancelled = cancelled;
    this.evaluate = evaluate;
  }

  /** Bearing modulo a right angle; parallel and perpendicular walls define the same local grid. */
  private static double frameDifference(double a, double b) {
    double d = Math.abs(Math.IEEEremainder(a - b, Math.PI / 2));
    return Math.min(d, Math.PI / 2 - d);
  }

  private static STRtree walls(List<Feature> objects) {
    STRtree index = new STRtree();
    for (Feature f : objects) {
      if (!BuildingAccess.isBuilding(f)) continue;
      for (int j = 0; j < f.geometry.getNumGeometries(); j++) {
        Coordinate[] ring = ((Polygon) f.geometry.getGeometryN(j)).getExteriorRing().getCoordinates();
        for (int i = 1; i < ring.length; i++) {
          LineString wall = Geo.line(ring[i - 1], ring[i]);
          if (wall.getLength() < WALL_M) continue;
          double axis = Math.atan2(ring[i].y - ring[i - 1].y, ring[i].x - ring[i - 1].x);
          Envelope envelope = new Envelope(wall.getEnvelopeInternal());
          envelope.expandBy(SEARCH_M);
          index.insert(envelope, new Side(wall, axis, f.id));
        }
      }
    }
    index.build();
    return index;
  }

  public static Facade nearest(LineString run, FeatureStore store) {
    if (run.getLength() < RUN_M) return null;
    return nearest(run, walls(store.near(SpatialRules.expand(run.getEnvelopeInternal(), SEARCH_M))));
  }

  private static Facade nearest(LineString run, STRtree walls) {
    if (run.getLength() < RUN_M) return null;
    double heading = Math.atan2(
        run.getEndPoint().getY() - run.getStartPoint().getY(),
        run.getEndPoint().getX() - run.getStartPoint().getX());
    Facade best = null;
    for (Object value : walls.query(run.getEnvelopeInternal())) {
      Side side = (Side) value;
      double distance = run.distance(side.line);
      if (distance > SEARCH_M) continue;
      double difference = frameDifference(heading, side.axis);
      if (difference > MAX_DIFFERENCE) continue;
      // Use the wall direction closest to this run, not the opposite house side.
      double axis = side.axis + Math.rint((heading - side.axis) / (Math.PI / 2)) * Math.PI / 2;
      Facade candidate = new Facade(axis, difference, distance, side.buildingId);
      if (best == null || distance < best.distance - 1e-6
          || Math.abs(distance - best.distance) < 1e-6
              && difference < best.difference) best = candidate;
    }
    return best;
  }

  /** Equivalent metres in the length part of the fixed 70/30 cost/geometry ranking. */
  public static double penalty(Network network, FeatureStore store) {
    double metres = 0;
    Envelope area = new Envelope();
    for (Network.Edge edge : network.edges.values())
      area.expandToInclude(edge.geometry.getEnvelopeInternal());
    if (area.isNull()) return 0;
    STRtree walls = walls(store.near(SpatialRules.expand(area, SEARCH_M)));
    for (Network.Edge edge : network.edges.values()) {
      Coordinate[] vertices = edge.geometry.getCoordinates();
      for (int i = 1; i < vertices.length; i++) {
        LineString run = Geo.line(vertices[i - 1], vertices[i]);
        Facade wall = nearest(run, walls);
        if (wall != null)
          metres += DRIFT_WEIGHT * Math.max(0, run.getLength() * Math.sin(wall.difference) - DRIFT_FREE_M);
      }
    }
    return metres;
  }

  /** An off-catalogue adapter is permitted only at a wall-aligned run near that wall. */
  public static boolean adapter(double first, double second, Coordinate at, FeatureStore store) {
    double angle = RoutingQuality.change(first, second);
    if (RouteFinder.standardAngle(first, second)) return false;
    double near = Math.min(Math.abs(angle - Math.PI / 2),
        Math.min(angle, Math.abs(angle - Math.PI / 4)));
    if (near > MAX_ADAPTER || near < Math.toRadians(.05)) return false;
    Envelope area = new Envelope(at);
    area.expandBy(SEARCH_M);
    for (Object value : walls(store.near(area)).query(new Envelope(at))) {
      Side side = (Side) value;
      if (side.line.distance(Geo.point(at)) > SEARCH_M) continue;
      if (frameDifference(first, side.axis) <= WALL_MATCH
          || frameDifference(second, side.axis) <= WALL_MATCH) return true;
    }
    return false;
  }

  public Evaluation improve(Evaluation initial) {
    Evaluation best = initial;
    int checked = 0, accepted = 0;
    List<Map<String, Object>> moves = new ArrayList<>();
    for (Network.Edge original : new ArrayList<>(initial.network.edges.values())) {
      if (cancelled.getAsBoolean()) break;
      Network.Edge edge = best.network.edges.get(original.id);
      if (edge == null || edge.geometry.getNumPoints() != 2) continue;
      Facade facade = nearest(edge.geometry, store);
      if (facade == null || facade.difference < Math.toRadians(.5)) continue;
      Coordinate a = edge.geometry.getCoordinateN(0), b = edge.geometry.getCoordinateN(1);
      double ux = Math.cos(facade.axis), uy = Math.sin(facade.axis);
      double along = (b.x - a.x) * ux + (b.y - a.y) * uy;
      double across = -(b.x - a.x) * uy + (b.y - a.y) * ux;
      for (boolean firstParallel : new boolean[] {true, false}) {
        if (cancelled.getAsBoolean()) break;
        Coordinate corner = firstParallel
            ? new Coordinate(a.x + along * ux, a.y + along * uy)
            : new Coordinate(a.x - across * uy, a.y + across * ux);
        if (corner.distance(a) < 2 || corner.distance(b) < 2) continue;
        Network trial = best.network.copy();
        trial.edges.put(edge.id, new Network.Edge(edge.id, edge.start, edge.end, Geo.line(a, corner, b)));
        checked++;
        try {
          Evaluation candidate = evaluate.apply(trial);
          if (candidate.score < best.score - 1e-8) {
            Map<String, Object> move = new LinkedHashMap<>();
            move.put("edgeId", edge.id);
            move.put("buildingId", facade.buildingId);
            move.put("beforeScore", best.score);
            move.put("afterScore", candidate.score);
            move.put("wallAxisDegrees", Math.toDegrees(facade.axis));
            moves.add(move);
            best = candidate;
            accepted++;
          }
        } catch (IllegalArgumentException | TopologyException invalid) {
          // The full cost, building, depth, and topology checks are authoritative.
        }
      }
    }
    diagnostics.put("candidateRoutesChecked", checked);
    diagnostics.put("acceptedAlignedRoutes", accepted);
    diagnostics.put("moves", moves);
    diagnostics.put("beforeScore", initial.score);
    diagnostics.put("afterScore", best.score);
    return best;
  }
}
