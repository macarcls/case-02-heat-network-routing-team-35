package ru.teplotrassa;

import static org.junit.jupiter.api.Assertions.*;
import static ru.teplotrassa.EngineTest.*;

import java.util.*;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.locationtech.jts.geom.*;
import ru.teplotrassa.data.FeatureStore;
import ru.teplotrassa.data.GeoJsonInput;
import ru.teplotrassa.engine.*;

class GeometrySearchTest {
  @Test
  void markedLongTrunkFollowsBuildingFacadeAndPreservesTheNetwork() throws Exception {
    Store raw = new Store();
    new GeoJsonInput(JSON).read(Path.of("../examples/supplied.geojson"), raw::add, false);
    Planner.Options o = new Planner.Options();
    o.routingStrategy = "tree";
    o.treeSingleRootRequired = true;
    o.mode = "depth";
    o.dataMode = "scenario";
    o.existingLoadPercent = 50d;
    FeatureStore store = InputData.scenario(raw, o);
    Planner planner = new Planner(store, o, () -> false, (p, m) -> {});
    Network saved =
        NetworkSnapshot.read(JSON.readTree(Path.of("../validation/geometry-final/checkpoint.json").toFile()));
    String originalRoute = saved.edges.get("corridor_19").geometry.toText();
    Evaluation before = planner.validateIncumbent(saved);
    FacadeAlignment alignment =
        new FacadeAlignment(store, () -> false, planner::validateIncumbent);
    Evaluation after = alignment.improve(before);
    assertTrue(after.score < before.score);
    assertEquals(17, after.network.connected.size());
    assertEquals(1, after.network.roots().size());
    assertEquals(true, after.checks.get("allConnectionsConnected"));
    assertEquals(true, after.checks.get("buildingIntersectionsValid"));
    assertEquals(true, after.checks.get("facadeAdaptersNeedEngineeringReview"));
    assertEquals(originalRoute, saved.edges.get("corridor_19").geometry.toText(),
        "The original route must remain unchanged");
    LineString aligned = after.network.edges.get("corridor_19").geometry;
    assertEquals(3, aligned.getNumPoints());
    LineString longRun = Geo.line(aligned.getCoordinateN(1), aligned.getCoordinateN(2));
    FacadeAlignment.Facade facade = FacadeAlignment.nearest(longRun, store);
    assertNotNull(facade);
    assertTrue(Math.toDegrees(facade.difference) < .2);
    assertTrue(NetworkCostBound.score(after.network.copy(), store, o, true) <= after.score + 1e-8);
  }

  @Test
  void removesSnakeWithoutMovingConsumerOrViolatingRules() {
    Store store = basic();
    Planner.Options o = new Planner.Options();
    o.routingStrategy = "tree";
    o.treeSingleRootRequired = true;
    Network network = one(store, 100, "a", 10);
    network.edges.put(
        "e",
        new Network.Edge(
            "e",
            "r",
            "a",
            Geo.line(c(0, 100), c(30, 100), c(30, 140), c(70, 140), c(70, 100), c(100, 100))));
    Planner planner = new Planner(store, o, () -> false, (p, m) -> {});
    Evaluation before = planner.validateIncumbent(network);
    GeometricOptimizer optimizer =
        new GeometricOptimizer(store, o, () -> false, planner::validateIncumbent, (p, m) -> {});
    Evaluation after = optimizer.improve(before, v -> {});
    assertTrue(after.score < before.score);
    assertTrue((double) after.summary.get("new_network_length") <= Math.sqrt(2) * 100 + .001);
    assertEquals(0, after.network.nodes.get("a").point.distance(c(100, 100)), 1e-9);
    assertEquals(1, after.network.roots().size());
    assertEquals(true, after.checks.get("allConnectionsConnected"));
    assertEquals(
        180, network.edges.get("e").geometry.getLength(), 1e-8, "Input network must be immutable");
  }

  @Test
  void routesAroundBarrierWithExactEndpoints() {
    Store store =
        new Store()
            .add(
                feature(
                    "barrier",
                    "restriction",
                    box(40, -15, 20, 30),
                    "restriction_type",
                    "prohibited_site"));
    Coordinate start = c(.37, .29), goal = c(100.73, .29);
    Planner.Options o = new Planner.Options();
    VisibilityRouter router =
        new VisibilityRouter(store, new Envelope(start, goal), List.of(), o, () -> false);
    List<LineString> paths = router.routes(start, goal, 100, null, null, 0, 0d, null, true);
    assertFalse(paths.isEmpty());
    LineString route = paths.get(0);
    assertEquals(0, route.getCoordinateN(0).distance(start), 1e-9);
    assertEquals(0, route.getCoordinateN(route.getNumPoints() - 1).distance(goal), 1e-9);
    assertTrue(
        new SpatialRules(store.near(SpatialRules.expand(route.getEnvelopeInternal(), 30)))
            .assess(route, 100, null, goal)
            .valid());
    assertTrue(RouteFinder.standardBends(route));
    assertTrue(router.graphSearches > 0);
    assertTrue(route.getLength() > start.distance(goal));
  }

  @Test
  void cancellationKeepsIncumbentAndProducesNoPartialReplacement() {
    Store store = basic();
    Planner.Options o = new Planner.Options();
    o.routingStrategy = "tree";
    o.treeSingleRootRequired = true;
    Planner planner = new Planner(store, o, () -> false, (p, m) -> {});
    Evaluation before = planner.validateIncumbent(one(store, 100, "a", 10));
    GeometricOptimizer optimizer =
        new GeometricOptimizer(store, o, () -> true, planner::validateIncumbent, (p, m) -> {});
    assertSame(
        before,
        optimizer.improve(
            before,
            v -> {
              fail("cancelled search must not publish a replacement");
            }));
    assertEquals("cancelled", optimizer.diagnostics.get("stopReason"));
  }

  @Test
  void smallBranchUsesCorridorThatCannotFitLargeTrunk() {
    Store store =
        new Store()
            .add(feature("top", "oks_existing", box(10, 5.7, 80, 30)))
            .add(feature("bottom", "oks_existing", box(10, -35.7, 80, 30)));
    Planner.Options o = new Planner.Options();
    Coordinate start = c(0, 0), goal = c(100, 0);
    VisibilityRouter thin =
        new VisibilityRouter(store, new Envelope(start, goal), List.of(), o, () -> false);
    List<LineString> a = thin.routes(start, goal, 100, null, null, 0, 0d, null, true);
    assertFalse(a.isEmpty());
    assertEquals(100, a.get(0).getLength(), 1e-6);
    VisibilityRouter thick =
        new VisibilityRouter(store, new Envelope(start, goal), List.of(), o, () -> false);
    List<LineString> b = thick.routes(start, goal, 500, null, null, 0, 0d, null, true);
    assertTrue(
        b.isEmpty() || b.get(0).getLength() > 150, "Large DN must not use the narrow passage");
  }

  @Test
  void existingBendUsesActualIncomingSegmentNotBisector() {
    LineString old = Geo.line(c(0, 0), c(50, 0), c(80, 30));
    assertEquals(0, TreeRoots.axis(old, c(50, 0)), 1e-9);
    assertEquals(Math.PI / 4, TreeRoots.axis(old, c(60, 10)), 1e-9);
  }

  @Test
  void metricTreeConnectsEveryConsumerAndPassesVectorRules() {
    Store store = basic();
    store.map.remove("a");
    store.map.remove("entry_a");
    store.add(feature("a", "oks_connection_point", Geo.point(c(100, 20)), "flow_tph", 10));
    store.add(feature("b", "oks_connection_point", Geo.point(c(140, 60)), "flow_tph", 15));
    store.add(feature("c", "oks_connection_point", Geo.point(c(90, 120)), "flow_tph", 20));
    Planner.Options o = new Planner.Options();
    o.routingStrategy = "tree";
    o.treeSingleRootRequired = true;
    var demands = InputData.demands(store);
    TreeRoots roots = new TreeRoots(store, demands, null, 3, () -> false);
    boolean valid = false;
    for (TreeRoots.Candidate root : roots.selected) {
      TrunkGrid graph = new TrunkGrid(store, demands, root, o, () -> false, 100, true);
      Network n = graph.buildMetricTree(0);
      if (n.connected.size() != 3) continue;
      try {
        Evaluation value = new Evaluation(n, store, demands, false, 6, o);
        assertEquals(true, value.checks.get("allConnectionsConnected"));
        assertEquals(1, n.roots().size());
        valid = true;
      } catch (IllegalArgumentException invalidCandidate) {
        // Different root candidates are allowed to be rejected by exact rules.
      }
    }
    assertTrue(valid, "At least one global tree must pass all vector checks");
  }

  @Test
  void costBoundNeverExceedsExactScoreForFeasibleGeometry() {
    Store store = basic();
    Planner.Options o = new Planner.Options();
    Planner planner = new Planner(store, o, () -> false, (p, m) -> {});
    for (boolean depth : new boolean[] {false, true}) {
      o.mode = depth ? "depth" : "plan";
      for (double detour : new double[] {0, 20, 40}) {
        Network network = one(store, 100, "a", 10);
        if (detour > 0)
          network.edges.put(
              "e",
              new Network.Edge(
                  "e",
                  "r",
                  "a",
                  Geo.line(
                      c(0, 100),
                      c(30, 100),
                      c(30, 100 + detour),
                      c(70, 100 + detour),
                      c(70, 100),
                      c(100, 100))));
        Evaluation exact = planner.validateIncumbent(network);
        double bound = NetworkCostBound.score(exact.network.copy(), store, o, true);
        assertTrue(bound <= exact.score + 1e-8, "Bound must never prune a genuine improvement");
      }
    }
  }
}
