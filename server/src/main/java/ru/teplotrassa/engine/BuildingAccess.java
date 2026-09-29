package ru.teplotrassa.engine;

import java.util.*;
import org.locationtech.jts.algorithm.RobustLineIntersector;
import org.locationtech.jts.geom.*;
import org.locationtech.jts.geom.prep.PreparedGeometry;
import org.locationtech.jts.geom.prep.PreparedGeometryFactory;
import org.locationtech.jts.index.strtree.STRtree;
import ru.teplotrassa.data.*;

/** Keeps the source's logical endpoint; its terminal approach may enter only its own building. */
public final class BuildingAccess {
  public static final double EPS = 1e-6;
  public final Feature entry, building;
  public final Gate gate;

  private BuildingAccess(Feature entry, Feature building) {
    this.entry = entry;
    this.building = building;
    gate = gate(building, entry.geometry.getCoordinate());
  }

  public static boolean isBuilding(Feature f) {
    return f.type.equals("oks_existing")
        || f.type.equals("oks_future")
        || f.type.equals("restriction") && f.restriction().equals("oks");
  }

  public static BuildingAccess resolve(FeatureStore store, Feature entry) {
    List<Feature> containing = new ArrayList<>();
    Envelope box = new Envelope(entry.geometry.getEnvelopeInternal());
    box.expandBy(EPS);
    for (Feature f : store.near(box))
      if (isBuilding(f) && f.geometry.distance(entry.geometry) <= EPS) containing.add(f);
    if (containing.size() > 1)
      throw new IllegalArgumentException(
          "AMBIGUOUS_BUILDING_ENTRY: точка "
              + entry.id
              + " принадлежит нескольким контурам зданий; уточните геометрию ввода");
    Feature building = containing.isEmpty() ? store.get(entry.text("oks_id")) : containing.get(0);
    return new BuildingAccess(entry, building != null && isBuilding(building) ? building : null);
  }

  public String buildingId() {
    return building == null ? null : building.id;
  }

  /** There is exactly one terminal: the unchanged input point. */
  public List<Coordinate> candidates(Coordinate target) {
    return List.of(entry.geometry.getCoordinate().copy());
  }

  public static Gate gate(Feature building, Coordinate point) {
    if (building == null || !isBuilding(building) || point == null) return null;
    Point p = Geo.point(point);
    for (int i = 0; i < building.geometry.getNumGeometries(); i++) {
      Polygon polygon = (Polygon) building.geometry.getGeometryN(i);
      if (polygon.contains(p) && polygon.getBoundary().distance(p) > EPS)
        return new Gate(polygon, point);
    }
    return null;
  }

  /** The terminal may cross an exterior side nearest to the original input point. */
  public static final class Gate {
    public final Coordinate endpoint;
    public final List<LineString> walls = new ArrayList<>();
    private final List<LineString> exteriorSides = new ArrayList<>();
    public final LineString nearestWall;
    public double nearestWallDistanceM;
    private final Polygon polygon;
    private final double reach;
    private final Map<Integer, Geometry> approaches = new HashMap<>();
    private final Map<Integer, PreparedGeometry> preparedApproaches = new HashMap<>();
    private final Map<Integer, LineString> outsideBoundaries = new HashMap<>();
    private final STRtree boundarySegments = new STRtree();
    private boolean queryIndependent = true;
    private final List<Coordinate> baselinePortals = new ArrayList<>();

    void preserveBaselineQueries() {
      queryIndependent = false;
    }

    private Gate(Polygon polygon, Coordinate endpoint) {
      this.polygon = polygon;
      this.endpoint = endpoint.copy();
      Coordinate[] ring = polygon.getExteriorRing().getCoordinates();
      double nearest = Double.POSITIVE_INFINITY;
      LineString selected = null;
      List<LineString> sides = new ArrayList<>();
      Point p = Geo.point(endpoint);
      for (int i = 1; i < ring.length; i++) {
        LineString wall = Geo.line(ring[i - 1], ring[i]);
        if (wall.getLength() < EPS) continue;
        // Canonical endpoints make equal-distance choices independent of polygon winding.
        if (wall.getCoordinateN(0).compareTo(wall.getCoordinateN(1)) > 0)
          wall = (LineString) wall.reverse();
        sides.add(wall);
        nearest = Math.min(nearest, wall.distance(p));
      }
      // At equal-distance corners prefer the longer side for the display label.
      for (LineString wall : sides) {
        if (wall.distance(p) <= nearest + EPS
            && (selected == null
                || wall.getLength() > selected.getLength()
                || wall.getLength() == selected.getLength() && wall.compareTo(selected) < 0)) {
          selected = wall;
        }
      }
      nearestWall = selected;
      for (LineString wall : sides)
        if (wall.distance(p) <= nearest + EPS) exteriorSides.add(wall);
      walls.addAll(exteriorSides);
      nearestWallDistanceM = nearest;
      Geometry boundary = polygon.getBoundary();
      for (int part = 0; part < boundary.getNumGeometries(); part++) {
        Coordinate[] vertices = boundary.getGeometryN(part).getCoordinates();
        for (int i = 1; i < vertices.length; i++)
          if (!vertices[i - 1].equals2D(vertices[i]))
            boundarySegments.insert(
                new Envelope(vertices[i - 1], vertices[i]),
                new LineSegment(vertices[i - 1], vertices[i]));
      }
      boundarySegments.build();
      Envelope e = polygon.getEnvelopeInternal();
      reach = Math.hypot(e.getWidth(), e.getHeight()) + 1;
    }

    /** Keep only usable sides at the nearest distance to the input point. */
    public Gate forDiameter(int dn) {
      double half = Rules.width(dn) / 2;
      walls.clear();
      for (LineString side : exteriorSides)
        if (side.getLength() > 2 * half + 2 * EPS)
          walls.add(Geo.part(side, half, side.getLength() - half));
      baselinePortals.clear();
      approaches.clear();
      preparedApproaches.clear();
      return this;
    }

    /** Geometric preference relative to the chosen upstream network attachment. */
    public double nearestWallDistance(Coordinate networkPoint) {
      if (walls.isEmpty()) return Double.POSITIVE_INFINITY;
      Point point = Geo.point(networkPoint);
      return walls.stream().mapToDouble(w -> w.distance(point)).min().orElse(Double.POSITIVE_INFINITY);
    }

    public LineString wallFor(LineString line) {
      Coordinate portal = terminalCrossing(line);
      if (portal == null) return null;
      for (LineString wall : walls) if (wall.distance(Geo.point(portal)) <= EPS) return wall;
      return null;
    }

    /** First exit of a ray; courtyard walls and other walls never gain permission. */
    public Coordinate crossingToward(Coordinate toward) {
      double distance = endpoint.distance(toward);
      if (distance < EPS) return null;
      Coordinate far =
          new Coordinate(
              endpoint.x + (toward.x - endpoint.x) * reach / distance,
              endpoint.y + (toward.y - endpoint.y) * reach / distance);
      Coordinate first = null;
      RobustLineIntersector intersection = new RobustLineIntersector();
      // This is the same first boundary intersection, without constructing a
      // polygon overlay for every short search fragment. Include courtyard rings.
      for (Object item : boundarySegments.query(new Envelope(endpoint, far))) {
        LineSegment side = (LineSegment) item;
        intersection.computeIntersection(endpoint, far, side.p0, side.p1);
        if (intersection.getIntersectionNum() > 1) return null;
        if (!intersection.hasIntersection()) continue;
        Coordinate c = intersection.getIntersection(0);
        if (endpoint.distance(c) > EPS
            && (first == null || endpoint.distance(c) < endpoint.distance(first))) first = c.copy();
      }
      if (first == null) return null;
      Point p = Geo.point(first);
      for (LineString wall : walls) if (wall.distance(p) <= EPS) return first.copy();
      return null;
    }

    /** Nearby points beyond this building's clearance for a short terminal escape. */
    public List<Coordinate> outsideCandidates(Coordinate portal, int dn) {
      LineString boundary =
          outsideBoundaries.computeIfAbsent(
              dn,
              k ->
                  ((Polygon) polygon.buffer(Rules.buildingClearance(dn) + Rules.width(dn) / 2 + .1))
                      .getExteriorRing());
      double at = Geo.index(boundary, portal), length = boundary.getLength();
      List<Coordinate> candidates = new ArrayList<>();
      for (double shift : new double[] {0, -.5, .5, -1, 1, -2, 2}) {
        double position = (at + shift + length) % length;
        Coordinate point = Geo.at(boundary, position);
        if (point.distance(portal) < SpatialRules.approachLength(dn)) candidates.add(point);
      }
      return candidates;
    }

    private Coordinate direction(LineString line, boolean terminal) {
      Coordinate[] c = line.getCoordinates();
      if (c[0].distance(endpoint) < EPS) return c[1];
      if (c[c.length - 1].distance(endpoint) < EPS) return c[c.length - 2];
      if (terminal) return null;
      Coordinate far = c[0];
      for (Coordinate p : c) if (p.distance(endpoint) > far.distance(endpoint)) far = p;
      return far;
    }

    public Coordinate terminalCrossing(LineString line) {
      Coordinate toward = direction(line, true);
      Coordinate portal = toward == null ? null : crossingToward(toward);
      return portal != null && endpoint.distance(toward) >= endpoint.distance(portal) - EPS
          ? portal
          : null;
    }

    /** Grid fragments and full paths may occupy only the single straight terminal ray. */
    public boolean allowsInterior(LineString line, Geometry interior) {
      if (line.getNumPoints() == 2) {
        Coordinate a = line.getCoordinateN(0), b = line.getCoordinateN(1);
        double length = a.distance(b);
        if (length > EPS
            && Math.abs((b.x - a.x) * (endpoint.y - a.y) - (b.y - a.y) * (endpoint.x - a.x))
                    / length
                > EPS) return false;
      }
      Coordinate toward = direction(line, false);
      Coordinate portal = toward == null ? null : crossingToward(toward);
      if (portal == null) return false;
      Geometry inside = line.intersection(interior);
      boolean allowed = Geo.line(endpoint, portal).buffer(2 * EPS).covers(inside);
      // Compatibility with the released solver. Experimental policies never take this branch.
      if (allowed && !queryIndependent) {
        boolean known = false;
        for (Coordinate old : baselinePortals)
          if (old.distance(portal) < EPS) {
            known = true;
            break;
          }
        if (!known) {
          baselinePortals.add(portal.copy());
          approaches.clear();
          preparedApproaches.clear();
        }
      }
      return allowed;
    }

    /** Set the complete query envelope before A*. Probing a segment never changes it. */
    public void prepareSearch(int dn, Coordinate goal, double... axes) {
      if (!queryIndependent) return;
      List<Coordinate> portals = new ArrayList<>();
      Coordinate direct = crossingToward(goal);
      if (direct != null) portals.add(direct);
      for (LineString wall : walls)
        for (double position : new double[] {.25, .5, .75}) {
          Coordinate target = Geo.at(wall, wall.getLength() * position);
          Coordinate crossing = crossingToward(target);
          if (crossing != null) portals.add(crossing);
        }
      for (double axis : axes)
        for (int direction = 0; direction < 8; direction++) {
          double bearing = axis + direction * Math.PI / 4;
          Coordinate portal =
              crossingToward(
                  new Coordinate(endpoint.x + Math.cos(bearing), endpoint.y + Math.sin(bearing)));
          if (portal != null) portals.add(portal);
        }
      approaches.put(
          dn,
          Geo.GF
              .createMultiPointFromCoords(portals.toArray(new Coordinate[0]))
              .buffer(SpatialRules.approachLength(dn), 8));
      preparedApproaches.remove(dn);
    }

    /** A permissive search envelope; assess() later checks actual terminal arc length. */
    public Geometry searchApproach(int dn) {
      return approaches.computeIfAbsent(
          dn,
          k ->
              (queryIndependent
                      ? Geo.GF.buildGeometry(walls)
                      : Geo.GF.createMultiPointFromCoords(
                          baselinePortals.toArray(new Coordinate[0])))
                  .buffer(SpatialRules.approachLength(dn), 8));
    }

    public boolean searchApproachCovers(LineString line, int dn) {
      return preparedApproaches
          .computeIfAbsent(dn, k -> PreparedGeometryFactory.prepare(searchApproach(dn)))
          .covers(line);
    }
  }
}
