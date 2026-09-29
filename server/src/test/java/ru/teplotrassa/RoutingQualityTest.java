package ru.teplotrassa;

import static org.junit.jupiter.api.Assertions.*;
import static ru.teplotrassa.EngineTest.*;

import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.locationtech.jts.geom.*;
import ru.teplotrassa.data.GeoJsonInput;
import ru.teplotrassa.engine.*;

class RoutingQualityTest {
  @Test
  void nearlyCollinearOverlapCannotSlipThroughExactFloatingPointIntersections() {
    Network network = new Network();
    for (String id : List.of("a", "b", "c", "d"))
      network.nodes.put(id, new Network.Node(id, c(0, 0), "chamber"));
    network.edges.put("one", new Network.Edge("one", "a", "b", Geo.line(c(0, 0), c(10, 0))));
    network.edges.put("two", new Network.Edge("two", "c", "d", Geo.line(c(2, 1e-8), c(12, 1e-8))));
    assertThrows(IllegalArgumentException.class, network::noCrossings);
    network.edges.put("two", new Network.Edge("two", "a", "d", Geo.line(c(0, 0), c(0, 10))));
    assertDoesNotThrow(network::noCrossings);
    LineString old = Geo.line(c(0, 0), c(10, 0));
    Coordinate entry = c(12, 1e-8), goal = c(2, 1e-8);
    RouteFinder finder = new RouteFinder(new SpatialRules(List.of()), () -> false, List.of(old));
    assertFalse(finder.canReuse(Geo.line(entry, goal), 80, null, entry, goal, 0d));
    LineString route = finder.route(entry, goal, 80, null, 0, false, 5, 10000, 0d);
    assertNotNull(route);
    assertTrue(route.getLength() > entry.distance(goal) + 1);
  }

  @Test
  void branchAtBendUsesIncomingSegmentAndDoesNotCreateBisectorAngles() {
    LineString line = Geo.line(c(0, 0), c(10, 0), c(20, 10));
    assertEquals(0, RoutingQuality.upstreamBearing(line, c(10, 0)), 1e-10);
    assertEquals(0, RoutingQuality.upstreamBearing(line, c(9.99, 0)), 1e-10);
    assertEquals(Math.PI / 4, RoutingQuality.upstreamBearing(line, c(10.01, .01)), 1e-7);
    assertEquals(Math.PI / 4, RoutingQuality.upstreamBearing(line, c(20, 10)), 1e-10);
    Store store = basic();
    Network network = one(store, 100, "a", 10);
    network.nodes.get("r").point.y = c(0, 125).y;
    network.edges.put(
        "e",
        new Network.Edge(
            "e", "r", "a", Geo.line(network.nodes.get("r").point, network.nodes.get("a").point)));
    IllegalArgumentException e =
        assertThrows(
            IllegalArgumentException.class,
            () -> new Evaluation(network, store, store.all("oks_future"), false, 6));
    assertTrue(e.getMessage().contains("угол присоединения"), e.getMessage());
  }

  @Test
  void equallyNearestReflexCornerUsesTheLongerWallWithoutChangingTheEndpoint() {
    Polygon body =
        Geo.GF.createPolygon(
            new Coordinate[] {
              c(0, 0), c(40, 0), c(40, 10), c(20, 10), c(20, 40), c(0, 40), c(0, 0)
            });
    Coordinate point = c(16, 8);
    var building = feature("b", "oks_existing", body);
    var gate = BuildingAccess.gate(building, point).forDiameter(80);
    var reversed =
        BuildingAccess.gate(feature("b", "oks_existing", body.reverse()), point).forDiameter(300);
    assertEquals(Math.sqrt(20), gate.nearestWallDistanceM, 1e-6);
    assertTrue(gate.nearestWall.equalsExact(Geo.line(c(20, 10), c(20, 40))));
    assertTrue(gate.nearestWall.equalsExact(reversed.nearestWall));
    assertNotNull(gate.crossingToward(c(25, 20)));
    assertNotNull(gate.crossingToward(c(35, 12)));
    Coordinate goal = c(60, 30);
    var spatial = new SpatialRules(List.of(building));
    LineString route =
        new RouteFinder(spatial, () -> false).route(point, goal, 80, "b", 0, false, 20, 100000, 0d);
    assertNotNull(route);
    assertTrue(route.getCoordinateN(0).equals2D(point));
    assertNotNull(gate.wallFor(route));
    assertTrue(spatial.assess(route, 80, "b", goal, point).valid());
  }

  @Test
  void diameterLengthBoundRejectsImpossibleDetoursAndPreservesAnAllowedBypass() {
    var obstacle = feature("obstacle", "oks_existing", box(40, -20, 20, 40));
    Coordinate from = c(0, 0), to = c(100, 0);
    var spatial = new SpatialRules(List.of(obstacle));
    var shortFinder = new RouteFinder(spatial, () -> false).withMaxLength(110);
    assertNull(shortFinder.route(from, to, 80, null, 0, false, 5, 100000, 0d));
    var enough = new RouteFinder(spatial, () -> false).withMaxLength(180);
    LineString route = enough.route(from, to, 80, null, 0, false, 5, 100000, 0d);
    assertNotNull(route);
    assertTrue(route.getLength() <= 180.001);
    assertTrue(spatial.assess(route, 80, null, to, from).valid());
    assertFalse(shortFinder.canReuse(route, 80, null, from, to, 0d));
  }

  @Test
  void physicalLengthBoundDoesNotCountTurnPenaltyAsMaterial() {
    Coordinate from = c(0, 0), to = c(20, 5);
    LineString route =
        new RouteFinder(new SpatialRules(List.of()), () -> false)
            .withMaxLength(25)
            .route(from, to, 80, null, 0, false, 5, 100000, 0d);
    assertNotNull(route);
    assertEquals(25, route.getLength(), 1e-6);
    assertEquals(25, RoutingQuality.bends(route).equivalentM);
  }

  @Test
  void aForbiddenTieDoesNotConsumeTheOnlyCandidateSlot() {
    Store store = basic();
    store.add(feature("obstacle", "oks_existing", box(-5, 80, 10, 40)));
    Planner.Options options = new Planner.Options();
    options.candidateLimit = 1;
    options.variantLimit = 1;
    Planner.Result result = new Planner(store, options, () -> false, (p, t) -> {}).calculate();
    assertEquals(1, result.variants.size(), result.diagnostics.toString());
    assertTrue((boolean) result.variants.get(0).summary.get("connection_complete"));
    for (Network.Node root : result.variants.get(0).network.roots())
      assertTrue(store.get("obstacle").geometry.distance(Geo.point(root.point)) > 5);
  }

  @Test
  void indexedFirstExitAgreesWithVectorOverlayForConcaveWallsAndCourtyards() {
    Polygon pocket =
        Geo.GF.createPolygon(
            new Coordinate[] {
              c(0, 0), c(40, 0), c(40, 40), c(20, 40), c(20, 12), c(12, 12), c(12, 40), c(0, 40),
              c(0, 0)
            });
    Polygon courtyard =
        Geo.GF.createPolygon(
            box(0, 0, 60, 60).getExteriorRing(),
            new LinearRing[] {box(20, 20, 20, 20).getExteriorRing()});
    for (Polygon body : List.of(pocket, courtyard)) {
      Coordinate endpoint = body == pocket ? c(14, 8) : c(15, 30);
      var gate = BuildingAccess.gate(feature("b", "oks_existing", body), endpoint).forDiameter(80);
      Envelope e = body.getEnvelopeInternal();
      double reach = Math.hypot(e.getWidth(), e.getHeight()) + 1;
      for (int degrees = 0; degrees < 360; degrees++) {
        double angle = Math.toRadians(degrees);
        Coordinate far =
            c(endpoint.x + reach * Math.cos(angle), endpoint.y + reach * Math.sin(angle));
        Geometry intersections = Geo.line(endpoint, far).intersection(body.getBoundary());
        Coordinate expected = null;
        if (!intersections.isEmpty() && intersections.getDimension() == 0) {
          for (Coordinate hit : intersections.getCoordinates())
            if (endpoint.distance(hit) > BuildingAccess.EPS
                && (expected == null || endpoint.distance(hit) < endpoint.distance(expected)))
              expected = hit;
          if (expected != null
              && gate.walls.get(0).distance(Geo.point(expected)) > BuildingAccess.EPS)
            expected = null;
        }
        Coordinate actual = gate.crossingToward(far);
        if (expected == null) assertNull(actual, "bearing " + degrees);
        else {
          assertNotNull(actual, "bearing " + degrees);
          assertTrue(actual.distance(expected) < BuildingAccess.EPS);
        }
      }
    }
  }

  @Test
  void unobstructedCompatibleDirectPathBeatsEveryBend() {
    Coordinate a = c(0, 0), b = c(100, 100);
    for (int mode = 0; mode < 3; mode++) {
      LineString line =
          new RouteFinder(new SpatialRules(List.of()), () -> false)
              .route(a, b, 80, null, mode, false, 20, 10000, 0d);
      assertEquals(2, line.getNumPoints());
      assertEquals(a.distance(b), line.getLength(), 1e-6);
      assertEquals(0, RoutingQuality.bends(line).equivalentM);
    }
  }

  @Test
  void oneRightAngleBeatsTheSlightlyShorterDiagonalWhenATurnIsNecessary() {
    Coordinate a = c(0, 0), b = c(20, 5);
    LineString line =
        new RouteFinder(new SpatialRules(List.of()), () -> false)
            .route(a, b, 80, null, 0, false, 20, 10000, 0d);
    RoutingQuality.Stats stats = RoutingQuality.bends(line);
    assertEquals(1, stats.turns90);
    assertEquals(0, stats.turns45);
    assertEquals(25, line.getLength(), 1e-6);
    assertEquals(25, stats.equivalentM);
  }

  @Test
  void simplifyingCanTradeASmallLengthIncreaseForAnEasierTurn() {
    Coordinate a = c(0, 0), b = c(20, 5);
    LineString diagonal = Geo.line(a, c(5, 5), b);
    RouteFinder finder = new RouteFinder(new SpatialRules(List.of()), () -> false);
    LineString simple = finder.simplify(diagonal, 80, null, a, b, 0d);
    assertTrue(simple.getLength() > diagonal.getLength());
    assertEquals(1, RoutingQuality.bends(simple).turns90);
    assertEquals(0, RoutingQuality.bends(simple).turns45);
  }

  @Test
  void penaltyAffectsNetworkChoiceButDoesNotInflateTheMonetaryEstimate() {
    Store store = basic();
    Network straight = one(store, 100, "a", 10);
    Network zigzag = straight.copy();
    zigzag.edges.put(
        "e",
        new Network.Edge(
            "e",
            "r",
            "a",
            Geo.line(c(0, 100), c(20, 100), c(30, 110), c(60, 110), c(70, 100), c(100, 100))));
    Evaluation easy = new Evaluation(straight, store, store.all("oks_future"), false, 6);
    Evaluation hard = new Evaluation(zigzag, store, store.all("oks_future"), false, 6);
    assertTrue(hard.score > easy.score);
    assertEquals(4, hard.summary.get("turn_45_count"));
    assertEquals(1, hard.summary.get("turn_90_count")); // connection to the existing vertical pipe
    assertEquals(225, (double) hard.summary.get("turn_penalty_m"), 1e-7);
    assertEquals(
        Rules.score(
            (double) hard.summary.get("calculated_cost"), (double) hard.summary.get("length")),
        (double) hard.summary.get("base_score"),
        1e-10);
    assertEquals((double) hard.summary.get("base_score") + .3 * 225 / 100, hard.score, 1e-10);
    assertEquals(
        zigzag.edges.get("e").geometry.getLength() * Rules.NEW[Rules.index(80)],
        (double) hard.summary.get("construction_cost"),
        .01);
  }

  @Test
  void insertingAJunctionAtACornerDoesNotEraseItsPenalty() {
    Store store = basic();
    Network whole = one(store, 100, "a", 10);
    whole.edges.put(
        "e",
        new Network.Edge(
            "e",
            "r",
            "a",
            Geo.line(c(0, 100), c(30, 100), c(30, 120), c(70, 120), c(70, 100), c(100, 100))));
    Network split = whole.copy();
    split.split("e", c(30, 100));
    Evaluation before = new Evaluation(whole, store, store.all("oks_future"), false, 6);
    Evaluation after = new Evaluation(split, store, store.all("oks_future"), false, 6);
    assertEquals(before.summary.get("turn_penalty_m"), after.summary.get("turn_penalty_m"));
  }

  @Test
  void nearestPointWallIsStableButTheNetworkCanUseAnotherExteriorWall() {
    Polygon body = box(100, 80, 30, 50);
    Coordinate point = c(102, 100);
    var first = feature("building", "oks_existing", body);
    var reversed = feature("building", "oks_existing", body.reverse());
    BuildingAccess.Gate a = BuildingAccess.gate(first, point).forDiameter(80);
    BuildingAccess.Gate b = BuildingAccess.gate(reversed, point).forDiameter(300);
    assertTrue(a.nearestWall.equalsExact(b.nearestWall));
    assertEquals(2, a.nearestWallDistanceM, 1e-6);
    assertEquals(4, a.walls.size());
    assertNotNull(a.crossingToward(c(200, 100)));
    assertNotNull(a.crossingToward(c(0, 100)));
    assertTrue(
        new SpatialRules(List.of(first))
            .assess(Geo.line(c(200, 100), point), 80, "building", null, point)
            .valid());
    assertTrue(a.nearestWallDistance(c(200, 100)) < a.nearestWallDistance(c(0, 100)));
  }

  @Test
  void equalDistanceWallChoiceIsStableWhenRingOrderChanges() {
    Polygon body = box(100, 80, 20, 20);
    Coordinate point = c(110, 90);
    var a = BuildingAccess.gate(feature("b", "oks_existing", body), point);
    var b = BuildingAccess.gate(feature("b", "oks_existing", body.reverse()), point);
    assertTrue(a.nearestWall.equalsExact(b.nearestWall));
    assertEquals(1, a.walls.size());
  }

  @Test
  void tightRealEntranceCanLeaveThroughItsNearestWallWithPenalisedDiagonalTurns() throws Exception {
    Store store = new Store();
    new GeoJsonInput(JSON).read(Path.of("../examples/supplied.geojson"), store::add, false);
    Coordinate entry = store.get("10").geometry.getCoordinate();
    LineString existing = (LineString) store.get("128").geometry;
    Coordinate goal = existing.getCoordinateN(existing.getNumPoints() - 1);
    Coordinate before = existing.getCoordinateN(existing.getNumPoints() - 2);
    double axis = Math.atan2(goal.y - before.y, goal.x - before.x);
    SpatialRules spatial =
        new SpatialRules(
            store.near(SpatialRules.expand(Geo.line(entry, goal).getEnvelopeInternal(), 1200)));
    LineString route = null;
    for (int mode = 0; mode < 3 && route == null; mode++)
      route =
          new RouteFinder(spatial, () -> false)
              .route(entry, goal, 125, "88", mode, true, 20, 100000, axis);
    assertNotNull(route, "A coarse grid must not make this fixed entrance unreachable");
    var gate = spatial.entryGate("88", entry, 125);
    assertNotNull(gate.wallFor(route));
    assertTrue(spatial.assess(route, 125, "88", goal, entry).valid());
    assertTrue(route.getCoordinateN(0).equals2D(entry));
    assertTrue(
        RoutingQuality.bends(route).turns45 > 0,
        "The narrow real pocket needs diagonal turns; their penalties must be visible");
  }
}
