package ru.teplotrassa.engine;

import java.util.*;
import org.locationtech.jts.geom.*;
import ru.teplotrassa.data.*;

public final class Evaluation {
  public final Network network;
  public final Reconstruction reconstruction;
  public final Map<String, Object> summary = new LinkedHashMap<>();
  public final double score;
  public final Map<String, Object> checks = new LinkedHashMap<>();

  public Evaluation(
      Network network, FeatureStore store, List<Feature> futures, boolean depth, double maxDepth) {
    this(network, store, futures, depth, maxDepth, new Planner.Options());
  }

  public Evaluation(
      Network network,
      FeatureStore store,
      List<Feature> futures,
      boolean depth,
      double maxDepth,
      Planner.Options options) {
    this.network = network;
    if (depth) {
      options.maxDepthM = maxDepth;
    }
    if (options.routingStrategy.equals("tree")
        && options.treeSingleRootRequired
        && network.roots().size() > 1)
      throw new IllegalArgumentException(
          "SINGLE_ROOT_REQUIRED: новая сеть должна иметь одну общую врезку");
    checks.put(
        "singleRootRequired",
        options.routingStrategy.equals("tree") && options.treeSingleRootRequired);
    checks.put("rootPolicyValid", true);
    network.flows();
    checks.put("diameters", network.lengthChecks());
    network.noCrossings();
    double construction = 0, newLength = 0, cam = 0;
    int maximumDegree = 0;
    for (Network.Node n : network.nodes.values()) {
      int existing =
          n.existingId == null
              ? 0
              : store.get(n.existingId).type.equals("heat_chamber")
                  ? InputValidator.existingDegree(store.get(n.existingId), store)
                  : 2;
      maximumDegree = Math.max(maximumDegree, network.incident(n.id).size() + existing);
      if ((n.existingId != null && store.get(n.existingId).type.equals("heat_chamber")
                  ? network.roots().stream()
                      .filter(r -> n.existingId.equals(r.existingId))
                      .mapToInt(r -> network.children(r.id).size())
                      .sum()
                  : network.incident(n.id).size())
              + existing
          > 4) throw new IllegalArgumentException("Более четырёх участков в камере " + n.id);
    }
    Map<String, BuildingAccess.Gate> assessedEntries = new HashMap<>();
    List<Map<String, Object>> facadeAdapters = new ArrayList<>();
    for (Network.Edge e : network.edges.values()) {
      Coordinate[] vertices = e.geometry.getCoordinates();
      for (int i = 1; i < vertices.length - 1; i++) {
        double a = Math.atan2(vertices[i].y - vertices[i - 1].y, vertices[i].x - vertices[i - 1].x),
            b = Math.atan2(vertices[i + 1].y - vertices[i].y, vertices[i + 1].x - vertices[i].x);
        if (RoutingQuality.change(a, b) > Math.PI / 2 + 1e-6)
          throw new IllegalArgumentException("Поворот трассы превышает 90°");
      }

      Network.Node end = network.nodes.get(e.end), start = network.nodes.get(e.start);
      String leaf = end.buildingId == null ? end.oksId : end.buildingId;
      SpatialRules s =
          new SpatialRules(store.near(SpatialRules.expand(e.geometry.getEnvelopeInternal(), 15)));
      SpatialRules.Assessment a =
          s.assess(
              e.geometry,
              e.dn,
              leaf,
              start.type.equals("tie") ? start.point : null,
              end.type.equals("oks") ? end.point : null,
              end.entryWall);
      if (!a.valid()) throw new IllegalArgumentException(String.join("; ", a.issues));
      if (end.type.equals("oks")) assessedEntries.put(e.id, s.entryGate(leaf, end.point, e.dn));
      e.passages = a.passages;
      newLength += e.geometry.getLength();
    }
    if (depth) checks.put("depth", DepthPlanner.assign(network, options));
    else
      for (Network.Edge e : network.edges.values())
        e.sections = DepthPlanner.base(e.geometry, e.passages);
    for (Network.Edge e : network.edges.values())
      for (DepthPlanner.Section part : e.sections)
        construction +=
            (part.to - part.from)
                * Rules.NEW[Rules.index(e.dn)]
                * part.factor
                * (depth ? (Rules.depthFactor(part.h0) + Rules.depthFactor(part.h1)) / 2 : 1);
    RoutingQuality.Stats turns = new RoutingQuality.Stats();
    int pipeBends = 0, connectionTurns = 0;
    for (Network.Edge edge : network.edges.values()) {
      RoutingQuality.Stats bends = RoutingQuality.bends(edge.geometry);
      turns.add(bends);
      pipeBends += bends.count;
      Double axis = receivingAxis(network.nodes.get(edge.start), store);
      if (axis != null) {
        Coordinate first = edge.geometry.getCoordinateN(0),
            second = edge.geometry.getCoordinateN(1);
        double bearing = Math.atan2(second.y - first.y, second.x - first.x);
        // The appendix constrains bends along a route, not the junction angle
        // between a new edge and a pre-existing main or a different branch.
        double angle =
            RoutingQuality.connectionAngle(
                Math.atan2(second.y - first.y, second.x - first.x), axis);
        if (RoutingQuality.penalty(angle) > 0) connectionTurns++;
        turns.add(angle);
      }
    }
    checks.put("bendCount", pipeBends);
    checks.put(
        "turnPenalty",
        Map.of(
            "turn90EquivalentM", RoutingQuality.TURN_90_M,
            "turn45EquivalentM", RoutingQuality.TURN_45_M,
            "turn135EquivalentM", RoutingQuality.TURN_135_M,
            "equivalentM", turns.equivalentM,
            "includesConnections", true,
            "includedInMonetaryCost", false));
    checks.put("rankingProfile", options.rankingProfile);
    checks.put("facadeAdapters", facadeAdapters);
    checks.put("facadeAdaptersNeedEngineeringReview", !facadeAdapters.isEmpty());
    checks.put("maximumChamberDegree", maximumDegree);
    checks.put("chamberDegreeLimit", 4);
    checks.put("topologyValid", true);
    checks.put("spatialRulesValid", true);
    checks.put("buildingIntersectionsValid", true);
    List<Map<String, Object>> buildingEntries = new ArrayList<>();
    double indoorLength = 0;
    double networkFacingWallPenalty = 0;
    for (Network.Node n : network.nodes.values()) {
      if (!n.type.equals("oks")) continue;
      Feature original = store.get(n.entryId);
      if (original == null
          || original.geometry.getCoordinate().distance(n.point) > BuildingAccess.EPS)
        throw new IllegalArgumentException("INPUT_ENDPOINT_MOVED: " + n.entryId);
      for (Network.Edge edge : network.edges.values()) {
        if (!edge.end.equals(n.id)) continue;
        if (edge.geometry.getCoordinateN(edge.geometry.getNumPoints() - 1).distance(n.point)
            > BuildingAccess.EPS)
          throw new IllegalArgumentException("INPUT_ENDPOINT_MOVED: " + n.entryId);
        BuildingAccess.Gate gate = assessedEntries.get(edge.id);
        if (gate == null) continue;
        Coordinate portal = gate.terminalCrossing(edge.geometry);
        if (portal == null
            || n.entryWall == null
            || n.entryWall.distance(Geo.point(portal)) > BuildingAccess.EPS)
          throw new IllegalArgumentException("BUILDING_ENTRY_INVALID: " + n.entryId);
        // Keep the assigned wall, updating its usable window for the final diameter.
        n.entryWall = gate.wallFor(edge.geometry);
        Coordinate attachment = network.nodes.get(edge.start).point;
        double nearestToNetwork = gate.nearestWallDistance(attachment);
        double selectedToNetwork = n.entryWall.distance(Geo.point(attachment));
        double extraDistance = Math.max(0, selectedToNetwork - nearestToNetwork);
        // Prefer the wall facing this branch's network attachment, while retaining
        // valid alternatives when the geometrically nearest wall cannot be reached.
        networkFacingWallPenalty += 8 * extraDistance;
        double inside = n.point.distance(portal);
        indoorLength += inside;
        Coordinate source = Geo.ll(n.point), wall = Geo.ll(portal);
        Map<String, Object> record = new LinkedHashMap<>();
        record.put("sourceEntryId", n.entryId);
        record.put("buildingId", n.buildingId == null ? n.oksId : n.buildingId);
        record.put("edgeId", edge.id);
        record.put("connectionCoordinates", new double[] {source.x, source.y});
        record.put("wallCrossingCoordinates", new double[] {wall.x, wall.y});
        record.put("indoorLengthM", inside);
        record.put("includedInCost", true);
        record.put("entryRule", "nearest_exterior_boundary_to_entry");
        record.put("referencePointNearestWallDistanceM", gate.nearestWallDistanceM);
        record.put("selectedWallDistanceToEntryM", n.entryWall.distance(Geo.point(n.point)));
        record.put("nearestWallDistanceToNetworkM", nearestToNetwork);
        record.put("selectedWallDistanceToNetworkM", selectedToNetwork);
        record.put("geometricallyNearestToNetwork", extraDistance <= 1e-4);
        record.put("wallSelectionReason", "Выбрана ближайшая к исходной точке наружная стена.");
        List<double[][]> walls = new ArrayList<>();
        Coordinate a = Geo.ll(n.entryWall.getCoordinateN(0)),
            b = Geo.ll(n.entryWall.getCoordinateN(1));
        walls.add(new double[][] {{a.x, a.y}, {b.x, b.y}});
        record.put("allowedWalls", walls);
        buildingEntries.add(record);
      }
    }
    checks.put("permittedBuildingEntries", buildingEntries);
    checks.put("wallSelectionPolicy", "nearest_exterior_boundary_to_entry");
    checks.put("originalEndpointsPreserved", true);
    checks.put("buildingEntriesValid", true);
    summary.put("indoor_connection_length", indoorLength);
    summary.put("tie_in_count", network.roots().size());
    checks.put("depthChecked", depth);
    checks.put(
        "maximumSlope",
        depth
            ? network.edges.values().stream()
                .flatMap(e -> e.sections.stream())
                .mapToDouble(p -> Math.abs(p.h1 - p.h0) / (p.to - p.from))
                .max()
                .orElse(0)
            : null);
    boolean contestRanking = options.rankingProfile.equals("contest");
    reconstruction = contestRanking ? new Reconstruction() : Reconstruction.calculate(network, store);
    for (Network.Node n : network.nodes.values())
      if (n.type.equals("chamber")
          || n.type.equals("tie") && store.get(n.existingId).type.equals("heat_network"))
        cam += Rules.chamber(reconstruction.newChamberDiameter(n, network, store));
    double reconCost = reconstruction.pieces.stream().mapToDouble(Reconstruction.Piece::cost).sum(),
        reconLength = reconstruction.pieces.stream().mapToDouble(p -> p.geometry.getLength()).sum();
    double chamberRecon =
        reconstruction.chambers.values().stream().mapToDouble(Rules::chamber).sum();
    Set<Object> unconnected = new LinkedHashSet<>();
    List<String> unconnectedEntries = new ArrayList<>();
    double unconnectedPenalty = 0;
    for (Feature f : futures)
      if (!network.connected.contains(f.id)) {
        String entry = f.text("_tt_entry_id");
        unconnected.add(sourceId(store.get(entry)));
        unconnectedPenalty += 100e6 + 500000 * f.flow();
        String building = InputData.buildingId(f);
        if (!f.text("_tt_entry_id").isBlank()) unconnectedEntries.add(f.text("_tt_entry_id"));
        if (!building.equals(f.id) && network.reasons.containsKey(f.id))
          network.reasons.put(
              building, "Ввод " + f.text("_tt_entry_id") + ": " + network.reasons.get(f.id));
      }
    double engineeringTieCost = network.roots().stream().mapToInt(r -> network.children(r.id).size()).sum() * 5e6;
    double contestTieCost = ContestScore.existingChamberConnections(network, store);
    int existingTieCount = (int) (contestTieCost / 5e6);
    double constructionCost = construction + cam + contestTieCost;
    double calculatedCost = constructionCost + unconnectedPenalty;
    double engineeringCost = construction + cam + engineeringTieCost + reconCost + chamberRecon;
    double length = newLength + reconLength;
    double contestScore = Rules.score(calculatedCost, newLength);
    double engineeringScore = options.score(engineeringCost + unconnectedPenalty, length);
    double baseScore = contestRanking ? contestScore : engineeringScore;
    double turnPenaltyScore = options.score(0, turns.equivalentM);
    double facadePenalty = options.routingStrategy.equals("tree") && options.treeGeometricSearch
        ? FacadeAlignment.penalty(network, store) : 0;
    double balancedScore = contestRanking ? contestScore :
        baseScore + turnPenaltyScore
            + options.score(0, facadePenalty + networkFacingWallPenalty);
    ConstructionObjectives.Metrics works =
        ConstructionObjectives.measure(network, turns, facadeAdapters.size(), depth);
    score = options.variantObjective.equals("earthworks")
        ? works.earthworkIndex / 400 + .08 * balancedScore
        : options.variantObjective.equals("installation")
            ? works.installationIndex / 70 + .08 * balancedScore
            : balancedScore;
    summary.put("construction_cost", constructionCost);
    summary.put("new_pipe_construction_cost", construction);
    summary.put("chamber_construction_cost", cam);
    summary.put("existing_chamber_tie_in_count", existingTieCount);
    summary.put("existing_chamber_tie_in_cost", contestTieCost);
    summary.put("tie_in_cost", contestTieCost);
    summary.put("reconstruction_cost", reconCost);
    summary.put("chamber_reconstruction_cost", chamberRecon);
    summary.put("engineering_calculated_cost", engineeringCost);
    summary.put("connection_complete", network.connected.size() == futures.size());
    summary.put("required_connection_count", futures.size());
    summary.put("connected_connection_count", network.connected.size());
    summary.put("unconnected_entry_ids", unconnectedEntries);
    summary.put("unconnected_penalty", unconnectedPenalty);
    summary.put("calculated_cost", calculatedCost);
    summary.put("contest_cost", calculatedCost);
    summary.put("contest_length", newLength);
    summary.put("contest_tie_in_cost", contestTieCost);
    summary.put("contest_score", ContestScore.rounded(contestScore));
    summary.put("contest_score_raw", contestScore);
    summary.put("engineering_score", engineeringScore);
    summary.put("ranking_basis", contestRanking ? "construction_plus_unconnected_penalty_and_new_length" :
        "full_engineering_cost_and_length_with_geometry");
    summary.put("new_network_length", newLength);
    summary.put("new_pipe_material_length", 2 * newLength);
    summary.put("reconstruction_length", reconLength);
    summary.put("length", length);
    summary.put("score", contestRanking ? ContestScore.rounded(contestScore) : score);
    summary.put("score_raw", score);
    summary.put("balanced_score", balancedScore);
    summary.put("indicative_trench_volume_m3", works.trenchVolumeM3);
    summary.put("deep_excavation_volume_index_m3", works.deepVolumeM3);
    summary.put("earthwork_index", works.earthworkIndex);
    summary.put("installation_index", works.installationIndex);
    summary.put("depth_change_count", works.depthChangeCount);
    summary.put("total_vertical_change_m", works.verticalChangeM);
    summary.put("maximum_route_depth_m", depth ? works.maximumDepthM : null);
    summary.put("soil_properties_available", false);
    summary.put("base_score", baseScore);
    summary.put("turn_penalty_m", turns.equivalentM);
    summary.put("turn_penalty_score", turnPenaltyScore);
    summary.put("facade_alignment_penalty_m", facadePenalty);
    summary.put("network_facing_wall_penalty_m", networkFacingWallPenalty);
    summary.put("facade_adapter_count", facadeAdapters.size());
    summary.put("ranking_length", length + turns.equivalentM + facadePenalty
        + networkFacingWallPenalty);
    summary.put("turn_90_count", turns.turns90);
    summary.put("turn_45_count", turns.turns45);
    summary.put("turn_135_count", turns.turns135);
    summary.put("other_turn_count", turns.other);
    summary.put("connection_turn_count", connectionTurns);
    summary.put("unconnected_oks_ids", unconnected);
    summary.put("bend_count", checks.get("bendCount"));
    double weighted = 0, special = 0;
    for (Network.Edge edge : network.edges.values())
      for (DepthPlanner.Section section : edge.sections) {
        double partLength = section.to - section.from;
        weighted +=
            partLength
                * section.factor
                * (depth ? (Rules.depthFactor(section.h0) + Rules.depthFactor(section.h1)) / 2 : 1);
        if (section.factor > 1 + 1e-9) special += partLength;
      }
    summary.put("weighted_construction_length", weighted);
    summary.put("special_passage_length", special);
    summary.put("construction_complexity_factor", newLength > 0 ? weighted / newLength : 1);
    checks.put("allConnectionsRequired", true);
    checks.put("allConnectionsConnected", network.connected.size() == futures.size());
  }

  private static Object sourceId(Feature feature) {
    if (feature == null) return null;
    com.fasterxml.jackson.databind.JsonNode original = feature.properties.path("_tt_source_id");
    return original.isIntegralNumber() ? original.numberValue() : feature.id;
  }

  /** Explain observed obstacles, without claiming every remaining bend is globally necessary. */
  public void describeRoutes(FeatureStore store) {
    List<Map<String, Object>> routes = new ArrayList<>();
    for (Network.Edge edge : network.edges.values()) {
      Network.Node start = network.nodes.get(edge.start), end = network.nodes.get(edge.end);
      LineString direct = Geo.line(start.point, end.point);
      SpatialRules spatial =
          new SpatialRules(store.near(SpatialRules.expand(direct.getEnvelopeInternal(), 15)));
      List<String> reasons =
          new ArrayList<>(
              spatial.assess(
                      direct,
                      edge.dn,
                      end.buildingId == null ? end.oksId : end.buildingId,
                      start.type.equals("tie") ? start.point : null,
                      end.type.equals("oks") ? end.point : null,
                      end.entryWall)
                  .issues);
      for (Network.Edge other : network.edges.values()) {
        if (other.id.equals(edge.id)) continue;
        Geometry hit = direct.intersection(other.geometry);
        if (hit.isEmpty()) continue;
        boolean beyondNodes = hit.getDimension() > 0;
        for (Coordinate point : hit.getCoordinates())
          if (point.distance(start.point) > .01 && point.distance(end.point) > .01)
            beyondNodes = true;
        if (beyondNodes) reasons.add("Прямая пересекла бы новую ветвь " + other.id);
      }
      Double axis = receivingAxis(start, store);
      double directBearing = Math.atan2(end.point.y - start.point.y, end.point.x - start.point.x);
      if (edge.geometry.getNumPoints() > 2
          && axis != null
          && !RouteFinder.standardAngle(directBearing, axis))
        reasons.add(
            String.format(
                Locale.forLanguageTag("ru"),
                "Угол прямого присоединения к принимающей трубе %.2f°; допустимы 0°, 45° или 90°",
                Math.toDegrees(Math.acos(Math.min(1, Math.abs(Math.cos(directBearing - axis)))))));
      if (edge.geometry.getNumPoints() > 2 && reasons.isEmpty())
        reasons.add(
            "Упрощение не принято после проверки всей сети и профиля; необходимость каждого"
                + " оставшегося поворота не доказана");
      Map<String, Object> row = new LinkedHashMap<>();
      row.put("edgeId", edge.id);
      row.put("lengthM", edge.geometry.getLength());
      row.put("straightDistanceM", direct.getLength());
      row.put("detourM", Math.max(0, edge.geometry.getLength() - direct.getLength()));
      RoutingQuality.Stats bends = RoutingQuality.bends(edge.geometry);
      row.put("bendCount", bends.count);
      row.put("turn90Count", bends.turns90);
      row.put("turn45Count", bends.turns45);
      row.put("turn135Count", bends.turns135);
      row.put("turnPenaltyM", bends.equivalentM);
      row.put(
          "connectionPenaltyM",
          RoutingQuality.connectionPenalty(
              Math.atan2(
                  edge.geometry.getCoordinateN(1).y - start.point.y,
                  edge.geometry.getCoordinateN(1).x - start.point.x),
              axis));
      row.put("directRouteConstraints", reasons);
      routes.add(row);
    }
    checks.put("routeExplanations", routes);
    checks.put(
        "complexityBasis",
        "Стоимость, длина, DN, спецпроходы и глубина; штрафы поворотов и присоединений:"
            + " 90° — 25 условных метров, 45° — 50, 135° — 75. Штраф не включён в смету."
            + " Свойства грунта не заданы и не предполагаются");
  }

  private Double receivingAxis(Network.Node node, FeatureStore store) {
    if (node.existingId != null) {
      Feature f = store.get(node.existingId);
      Set<String> visited = new HashSet<>();
      while (f != null && f.type.equals("heat_chamber") && visited.add(f.id))
        f = store.get(f.text("upstream_object_id"));
      if (f == null || !f.type.equals("heat_network")) return null;
      LineString line = (LineString) f.geometry;
      return RoutingQuality.upstreamBearing(line, node.point);
    }
    for (Network.Edge edge : network.edges.values())
      if (edge.end.equals(node.id)) {
        int last = edge.geometry.getNumPoints() - 1;
        Coordinate a = edge.geometry.getCoordinateN(last - 1),
            b = edge.geometry.getCoordinateN(last);
        return Math.atan2(b.y - a.y, b.x - a.x);
      }
    return null;
  }
}
