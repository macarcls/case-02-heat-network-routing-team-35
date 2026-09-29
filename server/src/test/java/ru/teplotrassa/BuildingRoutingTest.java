package ru.teplotrassa;

import static org.junit.jupiter.api.Assertions.*;
import static ru.teplotrassa.EngineTest.*;

import com.fasterxml.jackson.databind.JsonNode;
import java.nio.file.Path;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.locationtech.jts.geom.*;
import ru.teplotrassa.data.*;
import ru.teplotrassa.engine.*;

class BuildingRoutingTest {
  @TempDir Path temp;

  private Feature building(String type) {
    return feature(
        "building",
        type,
        box(40, 80, 20, 40),
        "restriction_type",
        "oks",
        "_tt_future_footprint",
        true);
  }

  @ParameterizedTest
  @ValueSource(strings = {"oks_existing", "oks_future", "restriction"})
  void allBuildingTypesBlockStraightRoutesEvenWithLegacyExemption(String type) {
    Feature f = building(type);
    SpatialRules spatial = new SpatialRules(List.of(f));
    LineString straight = Geo.line(c(0, 100), c(100, 100));
    assertFalse(spatial.hardClear(straight, 100, f.id, c(100, 100)));
    SpatialRules.Assessment check = spatial.assess(straight, 100, f.id, null, c(100, 100));
    assertFalse(check.valid());
    assertTrue(check.issues.stream().anyMatch(i -> i.contains("BUILDING_INTERSECTION")));
  }

  @ParameterizedTest
  @ValueSource(strings = {"oks_existing", "oks_future", "restriction"})
  void aStarFindsAnActualDetourAroundEveryBuildingType(String type) {
    Feature f = building(type);
    SpatialRules spatial = new SpatialRules(List.of(f));
    LineString route =
        new RouteFinder(spatial, () -> false)
            .route(c(0, 100), c(100, 100), 100, null, 0, false, 5, 100000);
    assertNotNull(route);
    assertTrue(route.getLength() > 100);
    assertFalse(route.intersects(f.geometry));
    assertTrue(route.distance(f.geometry) >= 5 + Rules.width(100) / 2 - 1e-5);
  }

  @Test
  void facadeTerminalIsReachableButItsOwnBuildingCannotBeUsedAsTransit() {
    Feature f = basic().get("a");
    SpatialRules spatial = new SpatialRules(List.of(f));
    LineString route =
        new RouteFinder(spatial, () -> false)
            .route(c(100, 100), c(0, 100), 100, "a", 0, true, 20, 100000);
    assertNotNull(route);
    assertEquals(100, route.getLength(), 1e-6);
    assertTrue(spatial.assess(route, 100, "a", null, c(100, 100)).valid());
    LineString through = Geo.line(c(0, 100), c(140, 100));
    assertFalse(spatial.hardClear(through, 100, "a", c(140, 100)));
    assertFalse(spatial.assess(through, 100, "a", null, c(140, 100)).valid());
  }

  @Test
  void approachExemptionCannotFollowTheWallOrExemptAnotherBuilding() {
    Feature own = feature("own", "oks_future", box(100, 0, 20, 200));
    SpatialRules spatial = new SpatialRules(List.of(own));
    Coordinate entry = c(100, 100);
    LineString alongWall = Geo.line(entry, c(98, 100), c(98, -20), c(0, -20));
    assertFalse(spatial.hardClear(alongWall, 100, "own", entry));
    assertFalse(spatial.assess(alongWall, 100, "own", null, entry).valid());
    Feature neighbour = feature("other", "oks_future", box(88, 90, 10, 20));
    spatial = new SpatialRules(List.of(own, neighbour));
    assertFalse(spatial.hardClear(Geo.line(c(0, 100), entry), 100, "own", entry));
    assertFalse(spatial.assess(Geo.line(c(0, 100), entry), 100, "own", null, entry).valid());
  }

  @ParameterizedTest
  @ValueSource(strings = {"plan", "depth"})
  void internalDemandEndsAtExactOriginalAndExportUsesSourceIdentity(String mode) throws Exception {
    Store raw = basic();
    Coordinate original = c(110, 100);
    raw.add(feature("entry_a", "oks_connection_point", Geo.point(original), "oks_id", "a"));
    Planner.Options options = new Planner.Options();
    options.mode = mode;
    options.gridM = 20;
    options.candidateLimit = 1;
    FeatureStore store = InputData.scenario(raw, options);
    Planner.Result result = new Planner(store, options, () -> false, (p, t) -> {}).calculate();
    Evaluation best = result.variants.get(0);
    assertEquals(Set.of("a"), best.network.connected, result.diagnostics.toString());
    assertEquals(original, raw.get("entry_a").geometry.getCoordinate());
    assertEquals(original, store.get("entry_a").geometry.getCoordinate());
    assertFalse(store.get("a").properties.path("_tt_future_footprint").asBoolean());
    Network.Node entry =
        best.network.nodes.values().stream()
            .filter(n -> n.type.equals("oks"))
            .findFirst()
            .orElseThrow();
    assertEquals("a", entry.buildingId);
    assertEquals(original, entry.point);
    assertEquals(110, ((Number) best.summary.get("new_network_length")).doubleValue(), 1e-5);
    assertEquals(10, ((Number) best.summary.get("indoor_connection_length")).doubleValue(), 1e-5);
    assertEquals(
        110 * Rules.NEW[Rules.index(80)],
        ((Number) best.summary.get("construction_cost")).doubleValue(),
        .01);
    assertEquals(1, ((List<?>) best.checks.get("permittedBuildingEntries")).size());
    assertEquals(true, best.checks.get("originalEndpointsPreserved"));
    assertEquals(true, best.checks.get("buildingEntriesValid"));
    Path output = temp.resolve("original-endpoint-" + mode + ".geojson");
    GeoJsonOutput.write(output, result, store, JSON);
    JsonNode features = JSON.readTree(output.toFile()).path("features");
    boolean referenced = false;
    for (JsonNode feature : features) {
      JsonNode properties = feature.path("properties");
      assertNotEquals("oks_connection_point", properties.path("object_type").asText());
      assertFalse(properties.has("proposed_wall_entry"));
      if (properties.path("end_node_id").asText().equals("entry_a")) {
        referenced = true;
        JsonNode coordinates = feature.path("geometry").path("coordinates");
        JsonNode end = coordinates.get(coordinates.size() - 1);
        assertEquals(Geo.ll(original).x, end.get(0).asDouble(), 1e-12);
        assertEquals(Geo.ll(original).y, end.get(1).asDouble(), 1e-12);
        if (mode.equals("depth")) assertEquals(-3, end.get(2).asDouble(), 1e-7);
      }
    }
    assertTrue(referenced);
  }

  @Test
  void assignedWallPermissionIsOnlyForTheStraightTerminalConnection() {
    Feature building = basic().get("a");
    SpatialRules spatial = new SpatialRules(List.of(building));
    Coordinate endpoint = c(102, 100);
    assertTrue(spatial.assess(Geo.line(c(0, 100), endpoint), 100, "a", null, endpoint).valid());
    assertFalse(
        spatial
            .assess(
                Geo.line(c(150, 100), endpoint),
                100,
                "a",
                null,
                endpoint,
                Geo.line(c(100, 90), c(100, 110)))
            .valid());
    assertFalse(spatial.assess(Geo.line(c(0, 100), c(140, 100)), 100, "a", null, endpoint).valid());
    assertFalse(
        spatial.assess(Geo.line(c(0, 100), endpoint), 100, "other", null, endpoint).valid());
    assertFalse(
        spatial
            .assess(
                Geo.line(c(0, 100), c(101, 100), c(101, 101), c(102, 101), endpoint),
                100,
                "a",
                null,
                endpoint)
            .valid());
    assertFalse(spatial.assess(Geo.line(c(0, 100), c(110, 100)), 100, "a", null, endpoint).valid());
  }

  @Test
  void aBlockedPointNearestSideDoesNotForbidTheNetworkFacingWall() {
    Store store = basic();
    Polygon body =
        Geo.GF.createPolygon(
            new Coordinate[] {
              c(100, 80),
              c(140, 80),
              c(140, 280),
              c(120, 280),
              c(120, 90),
              c(117, 90),
              c(117, 280),
              c(100, 280),
              c(100, 80)
            });
    store.add(feature("a", "oks_future", body, "flow_tph", 10, "heat_load", .5));
    Coordinate endpoint = c(115, 100);
    store.add(feature("entry_a", "oks_connection_point", Geo.point(endpoint), "oks_id", "a"));
    SpatialRules spatial = new SpatialRules(store.near(body.getEnvelopeInternal()));
    BuildingAccess.Gate gate = spatial.entryGate("a", endpoint, 80);
    assertEquals(2, gate.nearestWallDistanceM, 1e-6);

    LineString other = Geo.line(c(0, 100), endpoint);
    assertNotNull(gate.wallFor(other));
    assertTrue(spatial.assess(other, 80, "a", null, endpoint).valid());
    assertEquals(endpoint, store.get("entry_a").geometry.getCoordinate());
  }

  @Test
  void aNetworkOnTheOppositeSideSelectsItsNearestExteriorWall() {
    Feature body = feature("b", "oks_future", box(100, 80, 20, 40));
    Coordinate marker = c(102, 100), networkPoint = c(170, 100);
    BuildingAccess.Gate gate = BuildingAccess.gate(body, marker).forDiameter(80);
    assertEquals(2, gate.nearestWallDistanceM, 1e-6);
    LineString route = new RouteFinder(new SpatialRules(List.of(body)), () -> false)
        .route(marker, networkPoint, 80, "b", 0, false, 20, 100000);
    assertNotNull(route);
    LineString selected = gate.wallFor(route);
    assertNotNull(selected);
    assertEquals(gate.nearestWallDistance(networkPoint),
        selected.distance(Geo.point(networkPoint)), 1e-5);
    assertEquals(Geo.point(c(120, 100)).distance(selected), 0, 1e-5);
    assertTrue(new SpatialRules(List.of(body))
        .assess(route, 80, "b", networkPoint, marker, selected).valid());
  }

  @Test
  void gridFragmentsMustRemainOnTheirOwnTerminalRay() {
    Feature building = basic().get("a");
    SpatialRules spatial = new SpatialRules(List.of(building));
    Coordinate endpoint = c(102, 100);
    assertTrue(spatial.hardClear(Geo.line(c(101, 100), endpoint), 100, "a", endpoint));
    assertFalse(spatial.hardClear(Geo.line(c(101, 100), c(101, 101)), 100, "a", endpoint));
    assertFalse(spatial.hardClear(Geo.line(c(90, 100), c(130, 100)), 100, "a", endpoint));
  }

  @Test
  void internalEntryStillKeepsNeighbouringBuildingsBlocked() {
    Feature own = basic().get("a");
    Feature neighbour = feature("other", "oks_existing", box(75, 90, 10, 20));
    SpatialRules spatial = new SpatialRules(List.of(own, neighbour));
    Coordinate endpoint = c(102, 100);
    assertFalse(spatial.hardClear(Geo.line(c(0, 100), endpoint), 100, "a", endpoint));
    assertFalse(spatial.assess(Geo.line(c(0, 100), endpoint), 100, "a", null, endpoint).valid());
  }

  @Test
  void courtyardDoesNotBecomeAnAuthorizedEntrance() {
    Polygon body =
        Geo.GF.createPolygon(
            (LinearRing) box(100, 80, 40, 40).getExteriorRing(),
            new LinearRing[] {(LinearRing) box(130, 95, 5, 10).getExteriorRing()});
    Feature building = feature("own", "oks_future", body);
    Coordinate endpoint = c(125, 100);
    BuildingAccess.Gate gate = BuildingAccess.gate(building, endpoint);
    assertNotNull(gate);
    assertNull(gate.crossingToward(c(150, 100)));
    assertFalse(
        new SpatialRules(List.of(building))
            .assess(Geo.line(c(150, 100), endpoint), 100, "own", null, endpoint)
            .valid());
  }

  @Test
  void finalEvaluationRejectsMovingTheOriginalEndpointBackToFacade() {
    Store store = basic();
    store.add(feature("entry_a", "oks_connection_point", Geo.point(c(110, 100)), "oks_id", "a"));
    IllegalArgumentException error =
        assertThrows(
            IllegalArgumentException.class,
            () ->
                new Evaluation(
                    one(store, 100, "a", 10), store, InputData.demands(store), false, 6));
    assertTrue(error.getMessage().contains("INPUT_ENDPOINT_MOVED"));
  }

  @Test
  void interiorConnectionCannotBecomeAnUpstreamBranch() {
    Store store = basic();
    store.add(feature("entry_a", "oks_connection_point", Geo.point(c(102, 100)), "oks_id", "a"));
    Network net = one(store, 100, "a", 10);
    Network.Node entry = new Network.Node("a", c(102, 100), "oks");
    entry.oksId = "a";
    entry.entryId = "entry_a";
    entry.buildingId = "a";
    entry.demand = 10;
    net.nodes.put("a", entry);
    net.edges.put("e", new Network.Edge("e", "r", "a", Geo.line(c(0, 100), entry.point)));
    net.split("e", c(101, 100));
    IllegalArgumentException error =
        assertThrows(
            IllegalArgumentException.class,
            () -> new Evaluation(net, store, InputData.demands(store), false, 6));
    assertTrue(error.getMessage().contains("BUILDING_INTERSECTION"));
  }

  @Test
  void finalNetworkEvaluationRejectsABypassedRoutingCheck() {
    Store s = basic();
    s.add(building("oks_future"));
    IllegalArgumentException error =
        assertThrows(
            IllegalArgumentException.class,
            () -> new Evaluation(one(s, 100, "a", 10), s, InputData.demands(s), false, 6));
    assertTrue(error.getMessage().contains("BUILDING_INTERSECTION"));
  }

  @Test
  void multiPolygonPermissionCannotCrossAnotherComponent() {
    Store s = basic();
    s.add(
        feature(
            "a",
            "oks_future",
            Geo.GF.createMultiPolygon(new Polygon[] {box(40, 90, 20, 20), box(100, 90, 20, 20)}),
            "flow_tph",
            10,
            "heat_load",
            .5));
    s.add(feature("entry_a", "oks_connection_point", Geo.point(c(110, 100)), "oks_id", "a"));
    BuildingAccess access = BuildingAccess.resolve(s, s.get("entry_a"));
    assertNotNull(access.gate);
    assertEquals(List.of(c(110, 100)), access.candidates(c(0, 100)));
    assertTrue(
        access.gate.walls.stream().allMatch(w -> w.getEnvelopeInternal().getMinX() >= c(100, 0).x));
    assertFalse(
        new SpatialRules(s.near(new Envelope(c(0, 0), c(200, 200))))
            .hardClear(Geo.line(c(0, 100), c(100, 100)), 100, "a", c(100, 100)));
  }

  @Test
  void differentConsumersInsideOneFootprintKeepDifferentSourceReferences() {
    Store s = basic();
    s.map.remove("entry_a");
    s.add(feature("one", "oks_connection_point", Geo.point(c(110, 95)), "flow_tph", 10));
    s.add(feature("two", "oks_connection_point", Geo.point(c(110, 105)), "flow_tph", 10));
    Planner.Options options = new Planner.Options();
    options.candidateLimit = 3;
    Planner.Result result = new Planner(s, options, () -> false, (p, t) -> {}).calculate();
    assertEquals(
        Set.of("one", "two"),
        result.variants.get(0).network.connected,
        result.diagnostics.toString());
    Set<String> ids = new HashSet<>();
    for (Network.Node n : result.variants.get(0).network.nodes.values())
      if (n.type.equals("oks")) {
        assertEquals(s.get(n.entryId).geometry.getCoordinate(), n.point);
        ids.add(n.entryId);
      }
    assertEquals(Set.of("one", "two"), ids);
  }
}
