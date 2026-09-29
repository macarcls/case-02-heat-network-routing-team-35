package ru.teplotrassa;

import static org.junit.jupiter.api.Assertions.*;
import static ru.teplotrassa.EngineTest.*;

import java.nio.file.Path;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.locationtech.jts.geom.*;
import ru.teplotrassa.engine.*;

class FullConnectionTest {
  @TempDir Path temp;

  @Test
  void explanationMeasuresWhyAStraightTieWouldNeedANonstandardAngle() {
    Store store =
        basic()
            .add(feature("a", "oks_future", box(100, 70, 20, 20), "flow_tph", 10, "heat_load", .5))
            .add(feature("entry_a", "oks_connection_point", Geo.point(c(100, 80)), "oks_id", "a"));
    Network network = one(store, 100, "a", 10);
    Network.Node leaf = network.nodes.get("a");
    leaf.point.y = c(100, 80).y;
    network.edges.put(
        "e",
        new Network.Edge("e", "r", "a", Geo.line(c(0, 100), c(20, 100), c(20, 80), c(100, 80))));
    Evaluation result = new Evaluation(network, store, store.all("oks_future"), false, 6);
    result.describeRoutes(store);
    String description = JSON.valueToTree(result.checks).path("routeExplanations").toString();
    assertTrue(description.contains("78,69°"), description);
  }

  @Test
  void sharedTieExportsTheSameTotalCostAsTheEstimate() throws Exception {
    Store store =
        basic()
            .add(feature("b", "oks_future", box(100, 190, 20, 20), "flow_tph", 10, "heat_load", .5))
            .add(feature("entry_b", "oks_connection_point", Geo.point(c(100, 200)), "oks_id", "b"));
    Network network = one(store, 100, "a", 10);
    Network.Node b = new Network.Node("b", c(100, 200), "oks");
    b.oksId = "b";
    b.entryId = "entry_b";
    b.demand = 10;
    network.nodes.put(b.id, b);
    Network.Edge branch = new Network.Edge("second", "r", "b", Geo.line(c(0, 100), b.point));
    network.edges.put(branch.id, branch);
    network.connected.add("b");
    Evaluation evaluation = new Evaluation(network, store, store.all("oks_future"), false, 6);
    Planner.Result result = new Planner.Result();
    result.variants.add(evaluation);
    Path file = temp.resolve("two-connections.geojson");
    GeoJsonOutput.write(file, result, store, JSON);
    double total = 0;
    boolean foundTie = false;
    for (var feature : JSON.readTree(file.toFile()).path("features")) {
      var properties = feature.path("properties");
      total += properties.path("cost").asDouble();
      if (properties.path("object_type").asText().equals("tie_in")) {
        foundTie = true;
        assertEquals(2, properties.path("connection_count").asInt());
        assertEquals(10e6, properties.path("cost").asDouble());
      }
    }
    assertTrue(foundTie);
    assertEquals((double) evaluation.summary.get("calculated_cost"), total, .001);
  }

  @Test
  void anUnreachableConsumerPreventsPublicationOfAcheapPartialNetwork() throws Exception {
    Store store =
        basic()
            .add(feature("b", "oks_future", box(200, 90, 20, 20), "flow_tph", 10, "heat_load", .5))
            .add(feature("entry_b", "oks_connection_point", Geo.point(c(200, 100)), "oks_id", "b"))
            .add(
                feature(
                    "closed",
                    "restriction",
                    box(180, 70, 60, 60),
                    "restriction_type",
                    "prohibited_site"));
    Planner.Options options = new Planner.Options();
    options.candidateLimit = 1;
    options.variantLimit = 1;
    Planner.Result result = new Planner(store, options, () -> false, (p, t) -> {}).calculate();
    assertTrue(result.variants.isEmpty());
    assertEquals("NO_FEASIBLE_SOLUTION_FOUND", result.search.get("status"));
    assertEquals(1, result.search.get("bestConnectedCount"));
    assertTrue(((List<?>) result.search.get("unresolvedEntryIds")).contains("entry_b"));
    assertTrue(result.diagnostics.containsKey("b"));
    Path output = temp.resolve("no-complete-result.geojson");
    GeoJsonOutput.write(output, result, store, JSON);
    assertEquals(0, JSON.readTree(output.toFile()).path("features").size());
  }

  @Test
  void anUnobstructedStaircaseBecomesStraightWithoutMovingTerminals() {
    Coordinate start = c(0, 0), end = c(200, 0);
    LineString snake = Geo.line(start, c(40, 0), c(40, 20), c(90, 20), c(90, 0), end);
    RouteFinder finder = new RouteFinder(new SpatialRules(List.of()), () -> false);
    LineString smooth = finder.simplify(snake, 50, null, start, end, 0d);
    assertEquals(2, smooth.getNumPoints());
    assertEquals(200, smooth.getLength(), 1e-7);
    assertEquals(start, smooth.getCoordinateN(0));
    assertEquals(end, smooth.getCoordinateN(1));
  }

  @Test
  void simplificationPreservesBuildingClearancesAndStandardBends() {
    var building = feature("building", "oks_existing", box(80, -10, 40, 20));
    SpatialRules spatial = new SpatialRules(List.of(building));
    Coordinate start = c(0, 0), end = c(200, 0);
    LineString detour = Geo.line(start, c(0, 40), c(200, 40), end);
    LineString result =
        new RouteFinder(spatial, () -> false).simplify(detour, 50, null, start, end, 0d);
    assertTrue(result.getLength() <= detour.getLength());
    assertTrue(spatial.assess(result, 50, null, end, start).valid());
    assertFalse(result.intersects(building.geometry));
    assertTrue(RouteFinder.standardBends(result));
  }

  @Test
  void searchAvoidsAlreadyBuiltBranchesBeforeFinalEvaluation() {
    LineString occupied = Geo.line(c(100, -20), c(100, 20));
    LineString route =
        new RouteFinder(new SpatialRules(List.of()), () -> false, List.of(occupied))
            .route(c(0, 0), c(200, 0), 50, null, 0, false, 20, 10000, 0d);
    assertNotNull(route);
    assertTrue(route.intersection(occupied).isEmpty());
    assertTrue(RouteFinder.standardBends(route));
  }

  @Test
  void earthworkComplexityIsBasedOnActualSpecialLengthAndNotMissingConsumers() {
    Store store =
        basic()
            .add(feature("road", "restriction", box(40, 0, 20, 200), "restriction_type", "road"));
    Evaluation result =
        new Evaluation(one(store, 100, "a", 10), store, store.all("oks_future"), false, 6);
    assertTrue((boolean) result.summary.get("connection_complete"));
    assertFalse(result.summary.containsKey("unconnected_penalty"));
    assertEquals(200, (double) result.summary.get("new_pipe_material_length"), 1e-7);
    assertEquals(26, (double) result.summary.get("special_passage_length"), 1e-7);
    assertEquals(115.6, (double) result.summary.get("weighted_construction_length"), 1e-7);
    assertEquals(1.156, (double) result.summary.get("construction_complexity_factor"), 1e-7);
  }
}
