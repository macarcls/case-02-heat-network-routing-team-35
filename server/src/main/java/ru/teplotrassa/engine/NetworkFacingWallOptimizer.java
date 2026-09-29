package ru.teplotrassa.engine;

import java.util.*;
import java.util.function.*;
import org.locationtech.jts.geom.*;
import ru.teplotrassa.data.*;

/** Reconsiders terminal walls after the shared network's attachment points are known. */
public final class NetworkFacingWallOptimizer {
  private final FeatureStore store;
  private final Planner.Options options;
  private final BooleanSupplier cancelled;
  private final Function<Network, Evaluation> evaluate;
  public final Map<String, Object> diagnostics = new LinkedHashMap<>();

  public NetworkFacingWallOptimizer(FeatureStore store, Planner.Options options,
      BooleanSupplier cancelled, Function<Network, Evaluation> evaluate) {
    this.store = store;
    this.options = options;
    this.cancelled = cancelled;
    this.evaluate = evaluate;
  }

  public Evaluation improve(Evaluation initial) {
    Evaluation best = initial;
    int checked = 0, accepted = 0;
    List<Map<String, Object>> moves = new ArrayList<>();
    for (Network.Edge original : new ArrayList<>(initial.network.edges.values())) {
      if (cancelled.getAsBoolean()) break;
      Network.Edge edge = best.network.edges.get(original.id);
      if (edge == null) continue;
      Network.Node end = best.network.nodes.get(edge.end);
      if (end == null || !end.type.equals("oks") || end.buildingId == null) continue;
      Network.Node start = best.network.nodes.get(edge.start);
      if (start == null) continue;
      Envelope area = new Envelope(edge.geometry.getEnvelopeInternal());
      area.expandBy(1200);
      SpatialRules spatial = new SpatialRules(store.near(area));
      BuildingAccess.Gate gate = spatial.entryGate(end.buildingId, end.point, edge.dn);
      if (gate == null || gate.walls.size() < 2) continue;
      double nearest = gate.nearestWallDistance(start.point);
      double previousDistance = end.entryWall == null ? Double.POSITIVE_INFINITY
          : end.entryWall.distance(Geo.point(start.point));
      if (previousDistance <= nearest + .05) continue;
      List<LineString> occupied = new ArrayList<>();
      for (Network.Edge other : best.network.edges.values())
        if (!other.id.equals(edge.id)) occupied.add(other.geometry);
      Double axis = receivingAxis(best.network, start);
      for (int mode = 0; mode < 3 && !cancelled.getAsBoolean(); mode++) {
        LineString route =
            new RouteFinder(spatial, cancelled, occupied)
                .route(end.point, start.point, edge.dn, end.buildingId, mode,
                    options.mode.equals("depth"), options.gridM, options.maxCells, axis);
        if (route == null) continue;
        LineString chosen = gate.wallFor(route);
        if (chosen == null || chosen.distance(Geo.point(start.point))
            >= previousDistance - .05) continue;
        Network trial = best.network.copy();
        trial.nodes.get(end.id).entryWall = chosen;
        trial.edges.put(edge.id,
            new Network.Edge(edge.id, edge.start, edge.end, (LineString) route.reverse()));
        checked++;
        try {
          Evaluation candidate = evaluate.apply(trial);
          if (candidate.score < best.score - 1e-8) {
            Map<String, Object> move = new LinkedHashMap<>();
            move.put("sourceEntryId", end.entryId);
            move.put("edgeId", edge.id);
            move.put("beforeScore", best.score);
            move.put("afterScore", candidate.score);
            move.put("chosenWallDistanceToNetworkM", chosen.distance(Geo.point(start.point)));
            moves.add(move);
            best = candidate;
            accepted++;
          }
        } catch (IllegalArgumentException | TopologyException invalid) {
          // Keep the original complete solution if the new terminal fails any check.
        }
      }
    }
    diagnostics.put("candidateRoutesChecked", checked);
    diagnostics.put("acceptedWalls", accepted);
    diagnostics.put("moves", moves);
    diagnostics.put("beforeScore", initial.score);
    diagnostics.put("afterScore", best.score);
    return best;
  }

  private Double receivingAxis(Network network, Network.Node node) {
    if (node.existingId != null) {
      Feature f = store.get(node.existingId);
      Set<String> seen = new HashSet<>();
      while (f != null && f.type.equals("heat_chamber") && seen.add(f.id))
        f = store.get(f.text("upstream_object_id"));
      if (f != null && f.type.equals("heat_network"))
        return RoutingQuality.upstreamBearing((LineString) f.geometry, node.point);
    }
    for (Network.Edge parent : network.edges.values())
      if (parent.end.equals(node.id)) {
        int last = parent.geometry.getNumPoints() - 1;
        Coordinate a = parent.geometry.getCoordinateN(last - 1),
            b = parent.geometry.getCoordinateN(last);
        return Math.atan2(b.y - a.y, b.x - a.x);
      }
    return null;
  }
}
