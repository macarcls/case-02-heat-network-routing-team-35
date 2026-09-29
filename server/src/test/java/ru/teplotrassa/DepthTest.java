package ru.teplotrassa;

import static org.junit.jupiter.api.Assertions.*;
import static ru.teplotrassa.EngineTest.*;

import com.fasterxml.jackson.databind.JsonNode;
import java.nio.file.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import ru.teplotrassa.data.Feature;
import ru.teplotrassa.engine.*;

class DepthTest {
  @TempDir Path temp;

  static Planner.Options options() {
    Planner.Options o = new Planner.Options();
    o.mode = "depth";
    return o;
  }

  static Feature utility(String id, String type, double x) {
    return feature(id, "restriction", Geo.line(c(x, -100), c(x, 100)), "restriction_type", type);
  }

  static Network line(Feature... obstacles) {
    Network n = new Network();
    n.nodes.put("r", new Network.Node("r", c(0, 0), "tie"));
    n.nodes.put("a", new Network.Node("a", c(100, 0), "oks"));
    Network.Edge e = new Network.Edge("e", "r", "a", Geo.line(c(0, 0), c(100, 0)));
    e.dn = 100;
    n.edges.put("e", e);
    assess(n, Arrays.asList(obstacles));
    return n;
  }

  static void assess(Network n, List<Feature> obstacles) {
    for (Network.Edge e : n.edges.values()) {
      SpatialRules.Assessment a = new SpatialRules(obstacles).assess(e.geometry, e.dn, null, null);
      assertTrue(a.valid(), a.issues.toString());
      e.passages = a.passages;
    }
  }

  static double h(Network n, double distance) {
    return DepthPlanner.at(n.edges.get("e").sections, distance);
  }

  static void validateProfile(Network n, Planner.Options o) {
    for (Network.Edge e : n.edges.values()) {
      double at = 0, previous = n.nodes.get(e.start).depth;
      for (DepthPlanner.Section s : e.sections) {
        assertEquals(at, s.from, 1e-6);
        assertEquals(previous, s.h0, 1e-7);
        assertTrue(s.to > s.from);
        assertTrue(Math.min(s.h0, s.h1) >= o.minDepthM - 1e-7);
        assertTrue(Math.max(s.h0, s.h1) <= o.maxDepthM + 1e-7);
        assertTrue(Math.abs(s.h1 - s.h0) <= .1 * (s.to - s.from) + 1e-7);
        assertFalse((s.h0 - 3) * (s.h1 - 3) < -1e-7, "Cost section crosses Kh breakpoint");
        at = s.to;
        previous = s.h1;
      }
      assertEquals(e.geometry.getLength(), at, 1e-6);
      assertEquals(n.nodes.get(e.end).depth, previous, 1e-7);
    }
  }

  @Test
  void ordinaryDepthAndPlanCompatibility() {
    Network n = line();
    Planner.Options o = options();
    DepthPlanner.assign(n, o);
    assertEquals(1, n.edges.get("e").sections.size());
    assertEquals(3, h(n, 50));
    assertTrue(Double.isNaN(DepthPlanner.base(n.edges.get("e").geometry, List.of()).get(0).h0));
    validateProfile(n, o);
  }

  @Test
  void gasAboveHasFourMetrePlateauAndExactRamps() {
    Network n = line(utility("gas", "gas_pipeline", 50));
    Planner.Options o = options();
    Map<String, Object> report = DepthPlanner.assign(n, o);
    assertEquals(2.42, h(n, 48), 1e-7);
    assertEquals(2.42, h(n, 52), 1e-7);
    assertEquals(3, h(n, 42.2), 1e-7);
    assertEquals(3, h(n, 57.8), 1e-7);
    assertEquals(2.72, h(n, 45), 1e-7);
    assertEquals(0d, (double) report.get("additionalDepthCost"), 1e-5);
    validateProfile(n, o);
  }

  @Test
  void shortApproachForcesBelowAndExactExtraCost() {
    Network n = line(utility("gas", "gas_pipeline", 7));
    Planner.Options o = options();
    Map<String, Object> report = DepthPlanner.assign(n, o);
    assertEquals(3.4, h(n, 5), 1e-7);
    assertEquals(3.4, h(n, 9), 1e-7);
    assertEquals(3, h(n, 1), 1e-7);
    assertEquals(3, h(n, 13), 1e-7);
    // Two 4 m ramps, each average surcharge .02; 4 m plateau, surcharge .04 and gas K=1.25.
    assertEquals(
        89748 * (8 * .02 + 4 * 1.25 * .04), (double) report.get("additionalDepthCost"), .001);
    validateProfile(n, o);
  }

  @Test
  void impossibleRampAndMaximumAreRejected() {
    Planner.Options o = options();
    o.maxDepthM = 3.2;
    assertTrue(
        assertThrows(
                IllegalArgumentException.class,
                () -> DepthPlanner.assign(line(utility("gas", "gas_pipeline", 7)), o))
            .getMessage()
            .contains("RAMP_OR_DEPTH_LIMIT"));
  }

  @Test
  void configuredMinimumForcesPassageBelow() {
    Network n = line(utility("gas", "gas_pipeline", 50));
    Planner.Options o = options();
    o.minDepthM = 2.6;
    DepthPlanner.assign(n, o);
    assertEquals(3.4, h(n, 50), 1e-7);
    validateProfile(n, o);
  }

  @Test
  void protocolClearanceIsExplicitAndIndependentOfRanking() {
    Network n = line(utility("gas", "gas_pipeline", 50));
    Planner.Options o = options();
    o.depthRuleProfile = "protocol";
    o.rankingProfile = "appendix";
    DepthPlanner.assign(n, o);
    assertEquals(1.92, h(n, 50), 1e-7);
    validateProfile(n, o);
  }

  @Test
  void existingAndNewOuterHeightsAffectClearance() {
    Feature old =
        feature("cross", "heat_network", Geo.line(c(50, -100), c(50, 100)), "diameter", 500);
    Network n = line(old);
    Planner.Options o = options();
    o.minDepthM = 2.5;
    DepthPlanner.assign(n, o);
    assertEquals(4.21, h(n, 50), 1e-7);
    Network large = line(utility("gas", "gas_pipeline", 50));
    large.edges.get("e").dn = 1400;
    DepthPlanner.assign(large, options());
    assertEquals(1, h(large, 50), 1e-7);
    validateProfile(n, o);
  }

  @Test
  void overlappingGasAndCableShareFlatProfileAndMaximumFactor() {
    Network n = line(utility("gas", "gas_pipeline", 50), utility("cable", "power_cable", 52));
    Planner.Options o = options();
    DepthPlanner.assign(n, o);
    for (double x = 48; x <= 54; x += .25) assertEquals(2.02, h(n, x), 1e-7);
    DepthPlanner.Section both =
        n.edges.get("e").sections.stream()
            .filter(s -> s.from <= 51 && s.to >= 51)
            .findFirst()
            .get();
    assertEquals(1.25, both.factor);
    validateProfile(n, o);
  }

  @Test
  void nearbyCrossingsDoNotRequireAnImpossibleReturnToThree() {
    Network n = line(utility("gas", "gas_pipeline", 45), utility("cable", "power_cable", 52));
    Planner.Options o = options();
    DepthPlanner.assign(n, o);
    assertEquals(2.32, h(n, 45), 1e-7);
    assertTrue(h(n, 48) < 3);
    validateProfile(n, o);
  }

  @Test
  void roadAndTramCoverInteractWithLargePipeAndGas() {
    Feature tram =
        feature("tram", "restriction", box(46, -100, 8, 200), "restriction_type", "tram_tracks");
    Network n = line(tram, utility("gas", "gas_pipeline", 50));
    n.edges.get("e").dn = 1400;
    Planner.Options o = options();
    DepthPlanner.assign(n, o);
    assertEquals(3.4, h(n, 40 + 10), 1e-7);
    assertEquals(3.4, h(n, 43), 1e-7);
    assertEquals(3.4, h(n, 57), 1e-7);
    assertEquals(
        1.75,
        n.edges.get("e").sections.stream()
            .filter(s -> s.from <= 50 && s.to >= 50)
            .findFirst()
            .get()
            .factor);
    validateProfile(n, o);
  }

  @Test
  void sharedChamberHasOneDepthOnAllBranches() {
    Network n = line();
    n.edges.clear();
    n.nodes.put("ch", new Network.Node("ch", c(50, 0), "chamber"));
    n.nodes.put("b", new Network.Node("b", c(50, 30), "oks"));
    n.edges.put("e", new Network.Edge("e", "r", "ch", Geo.line(c(0, 0), c(50, 0))));
    n.edges.put("ea", new Network.Edge("ea", "ch", "a", Geo.line(c(50, 0), c(100, 0))));
    n.edges.put("eb", new Network.Edge("eb", "ch", "b", Geo.line(c(50, 0), c(50, 30))));
    for (Network.Edge e : n.edges.values()) e.dn = 100;
    // Cross only the stem; the other branches remain away from the finite gas segment.
    assess(
        n,
        List.of(
            feature(
                "gas",
                "restriction",
                Geo.line(c(47, -1), c(47, 1)),
                "restriction_type",
                "gas_pipeline")));
    Planner.Options o = options();
    DepthPlanner.assign(n, o);
    assertEquals(2.52, n.nodes.get("ch").depth, 1e-7);
    validateProfile(n, o);
  }

  @Test
  void transitionThroughThreeIsSplitForExactPrice() {
    Network n = line(utility("first", "gas_pipeline", 83), utility("last", "gas_pipeline", 93));
    Planner.Options o = options();
    DepthPlanner.assign(n, o);
    assertEquals(3.4, h(n, 93), 1e-7);
    validateProfile(n, o);
    // The 6 m gap cannot connect an upper pass at 2.42 to the mandatory lower pass at 3.4.
    assertEquals(3.4, h(n, 83), 1e-7);
    Network transition =
        line(utility("first", "gas_pipeline", 75), utility("last", "gas_pipeline", 93));
    DepthPlanner.assign(transition, o);
    assertEquals(2.42, h(transition, 75), 1e-7);
    assertEquals(3.4, h(transition, 93), 1e-7);
    validateProfile(transition, o);
    assertTrue(
        transition.edges.get("e").sections.stream()
            .anyMatch(s -> s.h0 < 3 && Math.abs(s.h1 - 3) < 1e-7));
  }

  @Test
  void truncatedPlateauIsRejectedWithSpecificReason() {
    assertTrue(
        assertThrows(
                IllegalArgumentException.class,
                () -> DepthPlanner.assign(line(utility("gas", "gas_pipeline", 1.5)), options()))
            .getMessage()
            .contains("PLATEAU_TOO_SHORT"));
  }

  @Test
  void exportAndCostUseDepthButReconstructionRetainsItsTariff() throws Exception {
    Store s = basic();
    s.add(
        feature(
            "gas",
            "restriction",
            Geo.line(c(7, 50), c(7, 150)),
            "restriction_type",
            "gas_pipeline"));
    Planner.Options o = options();
    Network n = one(s, 100, "a", 10);
    Evaluation depth = new Evaluation(n, s, s.all("oks_future"), true, 6, o);
    Evaluation plan = new Evaluation(one(s, 100, "a", 10), s, s.all("oks_future"), false, 6);
    assertEquals(plan.summary.get("reconstruction_cost"), depth.summary.get("reconstruction_cost"));
    assertTrue(
        (double) depth.summary.get("construction_cost")
            > (double) plan.summary.get("construction_cost"));
    Planner.Result r = new Planner.Result();
    r.variants.add(depth);
    Path output = temp.resolve("depth.geojson");
    GeoJsonOutput.write(output, r, s, JSON);
    JsonNode data = JSON.readTree(output.toFile());
    double cost = 0;
    for (JsonNode f : data.path("features")) {
      JsonNode p = f.path("properties"), geometry = f.path("geometry");
      if (geometry.isNull()) continue;
      if (geometry.path("type").asText().equals("Point"))
        assertEquals(3, geometry.path("coordinates").size());
      if (p.path("object_type").asText().equals("heat_network")) {
        JsonNode coords = geometry.path("coordinates");
        assertEquals(-p.path("depth_start").asDouble(), coords.get(0).get(2).asDouble(), 1e-7);
        assertEquals(
            -p.path("depth_end").asDouble(), coords.get(coords.size() - 1).get(2).asDouble(), 1e-7);
        cost += p.path("cost").asDouble();
      }
    }
    assertEquals((double) depth.summary.get("construction_cost"), cost, .001);
    validateProfile(n, o);
  }

  @Test
  void invalidDepthOptionsFailBeforeSearch() {
    Planner.Options o = options();
    assertDoesNotThrow(o::validate);
    o.minDepthM = .6;
    assertThrows(IllegalArgumentException.class, o::validate);
    o.minDepthM = .7;
    o.maxDepthM = 2.9;
    assertThrows(IllegalArgumentException.class, o::validate);
    o.maxDepthM = 6;
    o.depthRuleProfile = "unknown";
    assertThrows(IllegalArgumentException.class, o::validate);
  }
}
