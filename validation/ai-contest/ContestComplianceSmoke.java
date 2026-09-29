package ru.teplotrassa.experiment;

import com.fasterxml.jackson.databind.JsonNode;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.locationtech.jts.geom.Coordinate;
import org.locationtech.jts.geom.LineString;
import ru.teplotrassa.data.Feature;
import ru.teplotrassa.data.FeatureStore;
import ru.teplotrassa.engine.*;

/** Small independent assertions for the required appendix fields and topology. */
public final class ContestComplianceSmoke {
  private static void check(boolean valid, String explanation) {
    if (!valid) throw new AssertionError(explanation);
  }

  private static Network branchNetwork(double trunkLength, double branchLength) {
    Network n = new Network();
    Network.Node r = new Network.Node("r", new Coordinate(0, 0), "tie");
    Network.Node j = new Network.Node("j", new Coordinate(trunkLength, 0), "chamber");
    Network.Node a = new Network.Node("a", new Coordinate(trunkLength, branchLength), "oks");
    Network.Node b = new Network.Node("b", new Coordinate(trunkLength, -branchLength), "oks");
    a.demand = b.demand = 1;
    for (Network.Node node : List.of(r, j, a, b)) n.nodes.put(node.id, node);
    n.edges.put("trunk", new Network.Edge("trunk", "r", "j", Geo.line(r.point, j.point)));
    n.edges.put("a", new Network.Edge("a", "j", "a", Geo.line(j.point, a.point)));
    n.edges.put("b", new Network.Edge("b", "j", "b", Geo.line(j.point, b.point)));
    n.flows();
    n.lengthChecks();
    return n;
  }

  private static Feature numbered(String id, int numeric, String type,
      org.locationtech.jts.geom.Geometry geometry, Object... extra) {
    Feature original = NeuralExperiment.feature(id, type, geometry, extra);
    com.fasterxml.jackson.databind.node.ObjectNode props = original.properties.deepCopy();
    props.put("_tt_source_id", numeric);
    return new Feature(id, type, props, geometry);
  }

  public static void main(String[] args) throws Exception {
    Network split = branchNetwork(100, 80);
    check(split.edges.values().stream().allMatch(e -> e.dn == 50),
        "parallel branches must not be summed into a fictitious 260m path");
    Network longPath = branchNetwork(110, 100);
    check(longPath.edges.get("trunk").dn == 65, "overlong common stem must be promoted");
    check(longPath.edges.get("a").dn == 50 && longPath.edges.get("b").dn == 50,
        "independent branches retain minimal DN");
    Network straight = new Network();
    Network.Node start = new Network.Node("start", new Coordinate(0, 0), "tie");
    Network.Node end = new Network.Node("end", new Coordinate(800, 0), "oks");
    end.demand = 1;
    straight.nodes.put(start.id, start);
    straight.nodes.put(end.id, end);
    straight.edges.put("long", new Network.Edge("long", start.id, end.id,
        Geo.line(start.point, end.point)));
    straight.flows();
    straight.lengthChecks();
    check(straight.edges.get("long").dn == 200,
        "a constant-flow 800m path needs DN200, beyond a single nominal increment");

    NeuralExperiment.Store raw = new NeuralExperiment.Store();
    Coordinate origin = NeuralExperiment.p(0, 0), upstream = NeuralExperiment.p(0, -100),
        first = NeuralExperiment.p(100, 20), second = NeuralExperiment.p(100, -20);
    raw.add(NeuralExperiment.feature("source", "source", Geo.point(upstream)));
    raw.add(numbered("11", 11, "heat_network", Geo.line(upstream, origin), "diameter", 125));
    raw.add(numbered("12", 12, "heat_chamber", Geo.point(origin)));
    raw.add(numbered("101", 101, "oks_connection_point", Geo.point(first), "flow_tph", 1));
    raw.add(numbered("102", 102, "oks_connection_point", Geo.point(second), "flow_tph", 2));
    Planner.Options options = new Planner.Options();
    FeatureStore store = InputData.scenario(raw, options);
    List<Feature> demands = InputData.demands(store);
    check(demands.size() == 2, "minimal input permits two independent targets");
    NeuralExperiment.Store sharedBuilding = new NeuralExperiment.Store();
    sharedBuilding.add(NeuralExperiment.feature("building", "restriction",
        NeuralExperiment.box(0, 0, 30, 30), "restriction_type", "oks"));
    sharedBuilding.add(NeuralExperiment.feature("entry1", "oks_connection_point",
        Geo.point(NeuralExperiment.p(8, 8)), "oks_id", "building", "flow_tph", 1));
    sharedBuilding.add(NeuralExperiment.feature("entry2", "oks_connection_point",
        Geo.point(NeuralExperiment.p(20, 8)), "oks_id", "building", "flow_tph", 2));
    check(InputData.demands(sharedBuilding).size() == 2,
        "two independent targets may occupy the same building polygon");
    Network n = new Network();
    Network.Node root = new Network.Node("tie", origin, "tie");
    root.existingId = "12";
    n.nodes.put(root.id, root);
    for (Feature demand : demands) {
      Network.Node leaf = new Network.Node("node" + demand.id,
          demand.geometry.getCoordinate(), "oks");
      leaf.entryId = demand.id;
      leaf.oksId = demand.id;
      leaf.demand = demand.flow();
      n.nodes.put(leaf.id, leaf);
      n.edges.put("edge" + demand.id, new Network.Edge("edge" + demand.id,
          root.id, leaf.id, Geo.line(origin, leaf.point)));
      n.connected.add(demand.id);
    }
    Evaluation completed = new Evaluation(n, store, demands, false, 6, options);
    double construction = ((Number) completed.summary.get("construction_cost")).doubleValue();
    double pipeCost = ((Number) completed.summary.get("new_pipe_construction_cost")).doubleValue();
    check(Math.abs(construction - pipeCost - 10e6) < .01,
        "two new edges at an existing chamber must cost two tie-ins");
    check(((Number) completed.summary.get("existing_chamber_tie_in_count")).intValue() == 2,
        "tie-in count is per edge");

    Network partial = n.copy();
    partial.edges.remove("edge102");
    partial.nodes.remove("node102");
    partial.connected.remove("102");
    Evaluation incomplete = new Evaluation(partial, store, demands, false, 6, options);
    check(Math.abs(((Number) incomplete.summary.get("unconnected_penalty")).doubleValue()
        - 101e6) < .01, "each missing target adds its own flow penalty");
    check(incomplete.summary.get("unconnected_oks_ids")
        .equals(java.util.Set.of(102)),
        "numeric input IDs must stay numeric in the missing-target list");
    check(((Number) incomplete.summary.get("calculated_cost")).doubleValue()
        > ((Number) incomplete.summary.get("construction_cost")).doubleValue(),
        "the official total includes the missing-target penalty");

    Planner.Result result = new Planner.Result();
    completed.summary.put("rank", 1);
    result.variants.add(completed);
    Path output = Files.createTempFile("teplotrassa-1-9-5-", ".geojson");
    GeoJsonOutput.write(output, result, store, NeuralExperiment.JSON);
    JsonNode document = NeuralExperiment.JSON.readTree(output.toFile());
    check(document.path("features").size() == 3,
        "two edges plus one summary; existing chamber and ties are input objects");
    for (JsonNode object : document.path("features")) {
      JsonNode p = object.path("properties");
      check(List.of("heat_network", "variant_summary").contains(p.path("object_type").asText()),
          "output types must match section 7.1");
      if (p.path("object_type").asText().equals("heat_network")) {
        check(p.path("start_node_id").isInt() && p.path("start_node_id").asInt() == 12,
            "start endpoint must refer to existing numeric chamber ID");
        check(p.path("end_node_id").isInt(), "terminal ID must retain numeric JSON type");
      }
    }

    org.locationtech.jts.geom.Polygon building = NeuralExperiment.box(0, 0, 20, 20);
    BuildingAccess.Gate gate = BuildingAccess.gate(
        NeuralExperiment.feature("b", "restriction", building, "restriction_type", "oks"),
        NeuralExperiment.p(10, 2)).forDiameter(50);
    check(gate.crossingToward(NeuralExperiment.p(10, 30)) == null,
        "the farther north wall must not be a legal exit");
    check(gate.crossingToward(NeuralExperiment.p(10, -10)) != null,
        "the nearest south wall remains an eligible exit");
    Feature road = NeuralExperiment.feature("road", "restriction",
        NeuralExperiment.box(40, -40, 20, 80), "restriction_type", "road");
    Feature gas = NeuralExperiment.feature("gas", "restriction",
        Geo.GF.createMultiLineString(new LineString[] {
            Geo.line(NeuralExperiment.p(50, -40), NeuralExperiment.p(50, 40)),
            Geo.line(NeuralExperiment.p(70, -40), NeuralExperiment.p(70, 40))}),
        "restriction_type", "gas_pipeline");
    gas.validate();
    LineString straightCrossing = Geo.line(NeuralExperiment.p(0, 0), NeuralExperiment.p(100, 0));
    SpatialRules.Assessment special = new SpatialRules(List.of(road, gas)).assess(
        straightCrossing, 50, null, null);
    check(special.valid(), "a perpendicular straight road crossing must be accepted");
    check(DepthPlanner.base(straightCrossing, special.passages).size() >= 5,
        "overlapping special passages require explicit edges at every boundary");
    LineString bentCrossing = Geo.line(NeuralExperiment.p(0, 0),
        NeuralExperiment.p(50, 0), NeuralExperiment.p(100, 20));
    check(!new SpatialRules(List.of(road)).assess(bentCrossing, 50, null, null).valid(),
        "a bend inside the road's special crossing must be rejected");
    LineString shallowCrossing = Geo.line(NeuralExperiment.p(0, -80),
        NeuralExperiment.p(100, 120));
    check(!new SpatialRules(List.of(road)).assess(shallowCrossing, 50, null, null).valid(),
        "a shallow angle to the actual polygon boundary must be rejected");
    check(RouteFinder.standardBends(Geo.line(NeuralExperiment.p(0, 0),
        NeuralExperiment.p(10, 0), NeuralExperiment.p(20, 5))),
        "an arbitrary turn under 90 degrees is allowed");
    check(!RouteFinder.standardBends(Geo.line(NeuralExperiment.p(0, 0),
        NeuralExperiment.p(10, 0), NeuralExperiment.p(5, 5))),
        "a turn beyond 90 degrees is forbidden");
    System.out.println("appendix diameter, per-edge tie, partial penalty, typed GeoJSON IDs, nearest wall: OK");
  }
}
