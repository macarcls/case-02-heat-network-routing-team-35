package ru.teplotrassa.engine;

import java.util.*;
import org.locationtech.jts.geom.*;
import ru.teplotrassa.data.*;

/** Cheap, translation/rotation invariant descriptors; no map IDs or absolute coordinates. */
public final class CandidateFeatures {
  public static final List<String> NAMES =
      List.of(
          "distance_100m",
          "axis_manhattan_100m",
          "axis_misalignment",
          "dn_300",
          "flow_capacity_ratio",
          "existing_target",
          "edge_target",
          "lower_score_per_entry",
          "connected_20",
          "roots_8",
          "wall_distance_20m",
          "wall_length_50m",
          "open_bearings_ratio",
          "wall_direct_visible",
          "owner_vertices_50",
          "hard_intersections_10",
          "hard_length_100m",
          "bypass_extent_100m",
          "nearby_hard_20",
          "occupied_crossings_5",
          "soft_intersections_10",
          "length_limit_ratio");

  private CandidateFeatures() {}

  public static double[] extract(
      FeatureStore store,
      Network network,
      BuildingAccess access,
      Coordinate target,
      Double axis,
      int dn,
      double flow,
      boolean existing,
      boolean edgeTarget,
      double lowerScore) {
    Coordinate point = access.entry.geometry.getCoordinate();
    double distance = point.distance(target),
        bearing = Math.atan2(target.y - point.y, target.x - point.x);
    double delta = bearing - (axis == null ? bearing : axis);
    LineString direct = Geo.line(point, target);
    double hardCount = 0, hardLength = 0, bypass = 0, nearby = 0, occupied = 0, soft = 0;
    double cosine = Math.cos(bearing), sine = Math.sin(bearing);
    for (Feature f : store.near(SpatialRules.expand(direct.getEnvelopeInternal(), 20))) {
      boolean own = f.id.equals(access.buildingId());
      Rules.Restriction rule = Rules.RESTRICTIONS.get(f.restriction());
      boolean hard =
          BuildingAccess.isBuilding(f) || f.type.equals("restriction") && rule != null && rule.hard;
      if (hard && !own) {
        nearby++;
        if (f.geometry.distance(direct) <= Rules.buildingClearance(dn) + Rules.width(dn) / 2) {
          hardCount++;
          hardLength += f.geometry.intersection(direct).getLength();
          double minY = Double.POSITIVE_INFINITY, maxY = Double.NEGATIVE_INFINITY;
          for (Coordinate c : f.geometry.getCoordinates()) {
            double y = -(c.x - point.x) * sine + (c.y - point.y) * cosine;
            minY = Math.min(minY, y);
            maxY = Math.max(maxY, y);
          }
          if (minY <= 0 && maxY >= 0) bypass = Math.max(bypass, Math.min(-minY, maxY));
        }
      } else if (!hard && rule != null && direct.intersects(f.geometry)) soft++;
    }
    for (Network.Edge edge : network.edges.values())
      if (direct.intersects(edge.geometry)) occupied++;
    BuildingAccess.Gate gate = BuildingAccess.gate(access.building, point);
    double wallDistance = 0, wallLength = 0, open = 8, visible = 1, vertices = 0;
    if (gate != null) {
      gate.forDiameter(dn);
      wallDistance = gate.walls.isEmpty()
          ? gate.nearestWall.distance(Geo.point(target)) : gate.nearestWallDistance(target);
      wallLength = gate.walls.stream()
          .min(Comparator.comparingDouble(w -> w.distance(Geo.point(target))))
          .map(LineString::getLength).orElse(0d);
      open = 0;
      vertices = access.building.geometry.getNumPoints();
      double base = axis == null ? 0 : axis;
      for (int i = 0; i < 8; i++)
        if (gate.crossingToward(
                new Coordinate(
                    point.x + Math.cos(base + i * Math.PI / 4),
                    point.y + Math.sin(base + i * Math.PI / 4)))
            != null) open++;
      visible = gate.crossingToward(target) == null ? 0 : 1;
    }
    double maximum = Rules.LENGTH[Math.min(Rules.DN.length - 1, Rules.index(dn) + 1)];
    return new double[] {
      distance / 100,
      distance * (Math.abs(Math.cos(delta)) + Math.abs(Math.sin(delta))) / 100,
      Math.abs(Math.sin(2 * delta)),
      dn / 300d,
      flow / Rules.CAPACITY[Rules.index(dn)],
      existing ? 1 : 0,
      edgeTarget ? 1 : 0,
      lowerScore / (network.connected.size() + 1),
      network.connected.size() / 20d,
      network.roots().size() / 8d,
      wallDistance / 20,
      wallLength / 50,
      open / 8,
      visible,
      vertices / 50,
      hardCount / 10,
      hardLength / 100,
      bypass / 100,
      nearby / 20,
      occupied / 5,
      soft / 10,
      distance / maximum
    };
  }
}
