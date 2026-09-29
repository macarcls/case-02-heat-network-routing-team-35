package ru.teplotrassa;

import static org.junit.jupiter.api.Assertions.*;
import static ru.teplotrassa.EngineTest.*;

import com.fasterxml.jackson.databind.*;
import java.nio.file.*;
import java.util.*;
import org.junit.jupiter.api.*;
import org.locationtech.jts.geom.*;
import ru.teplotrassa.data.*;
import ru.teplotrassa.engine.*;

class ProtocolTest {
  @Test
  void sameDnBranchesAreCheckedOnSeparatePaths() {
    Network n = new Network();
    Network.Node root = new Network.Node("r", c(0, 0), "tie"),
        ch = new Network.Node("c", c(50, 0), "chamber"),
        a = new Network.Node("a", c(50, 80), "oks"),
        b = new Network.Node("b", c(50, -80), "oks");
    a.demand = 1;
    b.demand = 1;
    for (Network.Node node : List.of(root, ch, a, b)) n.nodes.put(node.id, node);
    n.edges.put("t", new Network.Edge("t", "r", "c", Geo.line(root.point, ch.point)));
    n.edges.put("a", new Network.Edge("a", "c", "a", Geo.line(ch.point, a.point)));
    n.edges.put("b", new Network.Edge("b", "c", "b", Geo.line(ch.point, b.point)));
    n.flows();
    List<Map<String, Object>> checks = n.lengthChecks();
    assertEquals(3, checks.size());
    assertEquals(130, (double) checks.get(1).get("continuousPathLengthM"), 1e-6);
    assertTrue(n.edges.values().stream().allMatch(e -> e.dn == 50));
  }

  @Test
  void severalNominalIncreasesAreAllowedWhenLengthRequiresThem() {
    Network n = one(basic(), 100, "a", 1);
    Network.Edge e = n.edges.get("e");
    n.edges.put("e", new Network.Edge("e", e.start, e.end, Geo.line(c(0, 100), c(300, 100))));
    n.flows();
    n.lengthChecks();
    assertEquals(80, n.edges.get("e").dn);
  }

  @Test
  void specialOverlapsUseMaximumTariffAndSinglePartition() {
    Feature road = feature("road", "restriction", box(40, 0, 20, 200), "restriction_type", "road"),
        gas =
            feature(
                "gas",
                "restriction",
                Geo.line(c(50, 0), c(50, 200)),
                "restriction_type",
                "gas_pipeline");
    LineString line = Geo.line(c(0, 100), c(100, 100));
    SpatialRules.Assessment a = new SpatialRules(List.of(road, gas)).assess(line, 100, null, null);
    assertTrue(a.valid(), a.issues.toString());
    List<DepthPlanner.Section> parts = DepthPlanner.plan(line, 100, a.passages, false, 6);
    assertEquals(5, parts.size());
    assertEquals(26, parts.stream().filter(p -> p.method.equals("special"))
        .mapToDouble(p -> p.to - p.from).sum(), 1e-6);
    assertEquals(1.6, parts.get(2).factor);
  }

  @Test
  void weightsAreExplicitAndNotSwappedSilently() {
    Planner.Options p = new Planner.Options();
    assertEquals(.7 * 2 + .3 * 1, p.score(50e6, 100), 1e-9);
    p.rankingProfile = "protocol";
    assertEquals(.3 * 2 + .7 * 1, p.score(50e6, 100), 1e-9);
  }

  @Test
  void interiorSourcePointIsPreservedButBuildingCannotBeCrossed() {
    Store s = basic();
    s.add(feature("entry_a", "oks_connection_point", Geo.point(c(110, 100)),
        "oks_id", "a", "flow_tph", 10));
    FeatureStore prepared = InputData.scenario(s, new Planner.Options());
    assertEquals(c(110, 100), InputData.portal(prepared.get("entry_a")));
    assertFalse(
        new SpatialRules(prepared.near(new Envelope(c(0, 0), c(200, 200))))
            .hardClear(Geo.line(c(0, 100), c(110, 100)), 100, "a"));
  }

  @Test
  void suppliedInputNeedsNoInventedExistingFlowForContest() throws Exception {
    Store s = new Store();
    new GeoJsonInput(JSON).read(Path.of("../examples/supplied.geojson"), s::add, false);
    List<Map<String, Object>> findings = InputDiagnostics.inspect(s);
    assertTrue(findings.stream().anyMatch(f -> f.get("code").equals("MISSING_EXISTING_FLOW")));
    FeatureStore trial = InputData.scenario(s, new Planner.Options());
    assertEquals(17, InputData.demands(trial).size());
    for (String id : s.ids("oks_connection_point"))
      assertEquals(s.get(id).geometry, trial.get(id).geometry);
    assertFalse(trial.get("115").properties.has("flow_tph"));
    assertFalse(s.get("115").properties.has("flow_tph"));
  }

  @Test
  void standardBendsAndClearanceAfterObstacleAvoidance() {
    Feature obstacle = feature("block", "oks_existing", box(40, -20, 20, 40));
    SpatialRules rules = new SpatialRules(List.of(obstacle));
    LineString route =
        new RouteFinder(rules, () -> false)
            .route(c(0, 0), c(100, 0), 100, null, 0, false, 5, 20000);
    assertNotNull(route);
    assertTrue(rules.assess(route, 100, null, null).valid());
    Coordinate[] p = route.getCoordinates();
    for (int i = 1; i < p.length - 1; i++) {
      double dot =
          (p[i].x - p[i - 1].x) * (p[i + 1].x - p[i].x)
              + (p[i].y - p[i - 1].y) * (p[i + 1].y - p[i].y);
      assertEquals(0, dot, 1e-5);
    }
  }

  @Test
  void futurePointsWithNoBuildingAndNumericIdsAccepted() throws Exception {
    Path p = Files.createTempFile("point-model-", ".geojson");
    try {
      Files.writeString(
          p,
          "{\"type\":\"FeatureCollection\",\"features\":[{\"type\":\"Feature\",\"properties\":{\"id\":7,\"object_type\":\"oks_connection_point\",\"flow_tph\":12},\"geometry\":{\"type\":\"Point\",\"coordinates\":[37.6,55.75]}}]}");
      Store s = new Store();
      new GeoJsonInput(JSON).read(p, s::add);
      assertEquals("7", InputData.demands(s).get(0).id);
    } finally {
      Files.deleteIfExists(p);
    }
  }
}
