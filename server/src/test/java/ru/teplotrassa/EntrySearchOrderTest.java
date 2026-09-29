package ru.teplotrassa;

import static org.junit.jupiter.api.Assertions.*;
import static ru.teplotrassa.EngineTest.*;

import java.util.*;
import org.junit.jupiter.api.Test;
import org.locationtech.jts.geom.*;
import ru.teplotrassa.engine.*;

class EntrySearchOrderTest {
  @Test
  void probingAnotherWallExitCannotChangeTheSearchEnvelope() {
    var building = feature("a", "oks_future", box(100, 70, 40, 60));
    Coordinate entry = c(102, 100);
    var gate = BuildingAccess.gate(building, entry).forDiameter(100);
    gate.prepareSearch(100, c(0, 100), 0);
    Geometry before = gate.searchApproach(100).copy();
    assertTrue(
        gate.allowsInterior(
            Geo.line(entry, c(90, 160)), building.geometry.buffer(-BuildingAccess.EPS)));
    assertTrue(
        before.equalsExact(gate.searchApproach(100)),
        "A* edge validity must stay fixed while searching");
    gate.prepareSearch(100, c(0, 180), .3);
    gate.prepareSearch(100, c(0, 100), 0);
    assertTrue(
        before.equalsExact(gate.searchApproach(100)),
        "Query reset must forget earlier target directions");
  }

  @Test
  void fullRouteValidationAndSimplificationIgnorePreviousSearchEnvelopes() {
    var building = feature("a", "oks_future", box(100, 70, 40, 60));
    Coordinate entry = c(102, 100), goal = c(0, 100);
    SpatialRules spatial = new SpatialRules(List.of(building));
    var gate = spatial.entryGate("a", entry, 100);
    LineString direct = Geo.line(entry, goal);
    // Deliberately initialise only rays that hit the forbidden right wall.
    gate.prepareSearch(100, c(200, 100));
    RouteFinder finder = new RouteFinder(spatial, () -> false);
    assertTrue(finder.canReuse(direct, 100, "a", entry, goal, Math.PI / 2));
    assertTrue(finder.simplify(direct, 100, "a", entry, goal, Math.PI / 2).equalsExact(direct));
    LineString along = Geo.line(entry, c(96, 100), c(96, 125), c(0, 125));
    assertFalse(
        finder.canReuse(along, 100, "a", entry, c(0, 125), Math.PI / 2),
        "A permissive search window must not waive the exact terminal clearance length");
  }

  @Test
  void routeOrderDoesNotChangeAPathPastAnObstacle() {
    var building = feature("a", "oks_future", box(100, 70, 40, 60));
    var obstacle = feature("block", "oks_existing", box(40, 85, 25, 30));
    Coordinate entry = c(102, 100), first = c(0, 100), second = c(0, 40);
    SpatialRules spatial = new SpatialRules(List.of(building, obstacle));
    RouteFinder finder = new RouteFinder(spatial, () -> false);
    LineString a = finder.route(entry, first, 100, "a", 0, false, 10, 10000, Math.PI / 2);
    assertNotNull(a);
    finder.route(entry, second, 100, "a", 2, false, 10, 10000, Math.PI / 2);
    LineString repeated = finder.route(entry, first, 100, "a", 0, false, 10, 10000, Math.PI / 2);
    assertNotNull(repeated);
    assertTrue(a.equalsExact(repeated));
  }
}
