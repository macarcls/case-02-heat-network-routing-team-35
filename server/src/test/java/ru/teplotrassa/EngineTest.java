package ru.teplotrassa;

import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.*;
import java.nio.file.*;
import java.util.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.locationtech.jts.geom.*;
import ru.teplotrassa.data.*;
import ru.teplotrassa.engine.*;

public class EngineTest {
  static final ObjectMapper JSON = new ObjectMapper();
  static final Coordinate ORIGIN = Geo.xy(37.6, 55.75);
  @TempDir Path temp;

  static Coordinate c(double x, double y) {
    return new Coordinate(ORIGIN.x + x, ORIGIN.y + y);
  }

  static Polygon box(double x, double y, double w, double h) {
    return Geo.GF.createPolygon(
        new Coordinate[] {c(x, y), c(x + w, y), c(x + w, y + h), c(x, y + h), c(x, y)});
  }

  static Feature feature(String id, String type, Geometry g, Object... fields) {
    ObjectNode p = JSON.createObjectNode();
    p.put("id", id);
    p.put("object_type", type);
    for (int i = 0; i < fields.length; i += 2)
      p.set(fields[i].toString(), JSON.valueToTree(fields[i + 1]));
    return new Feature(id, type, p, g);
  }

  static class Store implements FeatureStore {
    Map<String, Feature> map = new LinkedHashMap<>();

    Store add(Feature f) {
      if (f.type.equals("oks_connection_point") && !f.properties.path("flow_tph").isNumber()) {
        Feature building = map.get(f.text("oks_id"));
        if (building != null && building.properties.path("flow_tph").isNumber()) {
          ObjectNode props = f.properties.deepCopy();
          props.set("flow_tph", building.properties.path("flow_tph"));
          f = new Feature(f.id, f.type, props, f.geometry);
        }
      }
      map.put(f.id, f);
      return this;
    }

    public Feature get(String id) {
      return map.get(id);
    }

    public Iterable<String> ids(String type) {
      List<String> ids = new ArrayList<>();
      for (Feature f : map.values()) if (f.type.equals(type)) ids.add(f.id);
      return ids;
    }

    public List<Feature> near(Envelope box) {
      List<Feature> fs = new ArrayList<>();
      for (Feature f : map.values())
        if (f.geometry.getEnvelopeInternal().intersects(box)) fs.add(f);
      return fs;
    }
  }

  static Store basic() {
    return new Store()
        .add(feature("source", "source", Geo.point(c(0, 0))))
        .add(
            feature(
                "old",
                "heat_network",
                Geo.line(c(0, 0), c(0, 200)),
                "diameter",
                50,
                "flow_tph",
                2,
                "upstream_object_id",
                "source"))
        .add(feature("a", "oks_future", box(100, 90, 20, 20), "flow_tph", 10, "heat_load", .5))
        .add(feature("entry_a", "oks_connection_point", Geo.point(c(100, 100)), "oks_id", "a"));
  }

  static Network one(Store store, double y, String id, double flow) {
    Network n = new Network();
    Network.Node root = new Network.Node("r", c(0, y), "tie");
    root.existingId = "old";
    n.nodes.put(root.id, root);
    Network.Node a = new Network.Node("a", c(100, y), "oks");
    a.oksId = id;
    a.entryId = "entry_" + id;
    a.demand = flow;
    n.nodes.put(a.id, a);
    Network.Edge e = new Network.Edge("e", "r", "a", Geo.line(root.point, a.point));
    n.edges.put(e.id, e);
    n.connected.add(id);
    return n;
  }

  @Test
  void appendixValues() {
    assertEquals(200, Rules.diameter(80));
    assertEquals(100, Rules.diameter(22.3));
    assertEquals(125, Rules.diameter(22.3001));
    assertEquals(.88, Rules.width(200), 1e-12);
    assertEquals(1.2, Rules.depthFactor(5), 1e-12);
    assertEquals(2, Rules.score(50000000, 200), 1e-12);
  }

  @Test
  void utmRoundTrip() {
    Coordinate p = Geo.ll(Geo.xy(37.6, 55.75));
    assertEquals(37.6, p.x, 1e-8);
    assertEquals(55.75, p.y, 1e-8);
    assertEquals(412125.4591875144, ORIGIN.x, 1e-4);
    assertEquals(6179143.32361831, ORIGIN.y, 1e-4);
  }

  @Test
  void validatesReferencesAndCycles() {
    Store s = basic();
    assertDoesNotThrow(() -> InputValidator.validate(s));
    ((ObjectNode) s.get("old").properties).put("upstream_object_id", "old");
    assertThrows(IllegalArgumentException.class, () -> InputValidator.validate(s));
  }

  @Test
  void preservesExplicitPointInsideFutureFootprint() {
    Store s = basic();
    s.add(feature("entry_a", "oks_connection_point", Geo.point(c(110, 100)), "oks_id", "a"));
    assertDoesNotThrow(() -> InputValidator.validate(s));
    assertEquals(c(110, 100), InputData.portal(s.get("entry_a")));
  }

  @Test
  void reverseExistingGeometryDoesNotReverseReconstruction() {
    Store s = basic();
    Network n = one(s, 100, "a", 10);
    n.flows();
    Reconstruction a = Reconstruction.calculate(n, s);
    assertEquals(1, a.pieces.size());
    assertEquals(100, a.pieces.get(0).geometry.getLength(), 1e-7);
    assertEquals(80, a.pieces.get(0).required);
    assertEquals(11758200, a.pieces.get(0).cost(), .01);
    s.add(
        feature(
            "old",
            "heat_network",
            Geo.line(c(0, 200), c(0, 0)),
            "diameter",
            50,
            "flow_tph",
            2,
            "upstream_object_id",
            "source"));
    Reconstruction b = Reconstruction.calculate(n, s);
    assertTrue(a.pieces.get(0).geometry.equalsTopo(b.pieces.get(0).geometry));
  }

  @Test
  void overlappingTieFlowsCountOnlyUpstreamParts() {
    Store s = basic();
    Network n = one(s, 100, "a", 10);
    Network.Node r = new Network.Node("r2", c(0, 150), "tie");
    r.existingId = "old";
    n.nodes.put(r.id, r);
    Network.Node end = new Network.Node("b", c(100, 150), "oks");
    end.demand = 20;
    n.nodes.put(end.id, end);
    n.edges.put("b", new Network.Edge("b", r.id, end.id, Geo.line(r.point, end.point)));
    n.flows();
    Reconstruction out = Reconstruction.calculate(n, s);
    assertEquals(2, out.pieces.size());
    assertEquals(30, out.pieces.get(0).added);
    assertEquals(125, out.pieces.get(0).required);
    assertEquals(100, out.pieces.get(0).geometry.getLength(), 1e-6);
    assertEquals(20, out.pieces.get(1).added);
    assertEquals(100, out.pieces.get(1).required);
    assertEquals(50, out.pieces.get(1).geometry.getLength(), 1e-6);
  }

  @Test
  void chamberDoesNotResetSameDnLength() {
    Network n = new Network();
    Network.Node r = new Network.Node("r", c(0, 0), "tie"),
        b = new Network.Node("ch", c(250, 0), "chamber"),
        a = new Network.Node("a", c(450, 0), "oks");
    a.demand = 20;
    n.nodes.put("r", r);
    n.nodes.put("ch", b);
    n.nodes.put("a", a);
    n.edges.put("x", new Network.Edge("x", "r", "ch", Geo.line(r.point, b.point)));
    n.edges.put("y", new Network.Edge("y", "ch", "a", Geo.line(b.point, a.point)));
    n.flows();
    assertDoesNotThrow(n::lengths);
    assertEquals(125, n.edges.get("x").dn);
    assertEquals(125, n.edges.get("y").dn);
  }

  @Test
  void forksSumDemandWithoutDoublingPipes() {
    Network n = one(basic(), 100, "a", 10);
    String parent = n.split("e", c(50, 100));
    Network.Node b = new Network.Node("b", c(50, 130), "oks");
    b.demand = 20;
    n.nodes.put(b.id, b);
    n.edges.put("branch", new Network.Edge("branch", parent, b.id, Geo.line(c(50, 100), b.point)));
    n.flows();
    assertEquals(30, n.rootFlow(n.roots().get(0)));
    assertEquals(125, n.children("r").get(0).dn);
  }

  @Test
  void crossingOutsideNodeRejected() {
    Network n = one(basic(), 100, "a", 10);
    n.edges.put("bad", new Network.Edge("bad", "s", "t", Geo.line(c(50, 80), c(50, 120))));
    assertThrows(IllegalArgumentException.class, n::noCrossings);
  }

  @Test
  void roadSpecialIncludesThreeMetresBothSides() {
    Feature road = feature("road", "restriction", box(40, 0, 20, 200), "restriction_type", "road");
    LineString line = Geo.line(c(0, 100), c(100, 100));
    SpatialRules.Assessment a = new SpatialRules(List.of(road)).assess(line, 100, null, null);
    assertTrue(a.valid(), a.issues.toString());
    assertEquals(1, a.passages.size());
    assertEquals(37, a.passages.get(0).from, 1e-7);
    assertEquals(63, a.passages.get(0).to, 1e-7);
    List<DepthPlanner.Section> sections = DepthPlanner.plan(line, 100, a.passages, false, 6);
    assertEquals(3, sections.size());
    assertEquals(1.6, sections.get(1).factor);
    assertEquals(
        74 * 89748 + 26 * 89748 * 1.6,
        sections.stream().mapToDouble(s -> (s.to - s.from) * 89748 * s.factor).sum(),
        .001);
  }

  @Test
  void acuteRoadCrossingRejected() {
    Feature road =
        feature("road", "restriction", box(40, -100, 20, 400), "restriction_type", "road");
    SpatialRules.Assessment a =
        new SpatialRules(List.of(road)).assess(Geo.line(c(0, 0), c(100, 200)), 100, null, null);
    assertFalse(a.valid());
    assertTrue(a.issues.stream().anyMatch(x -> x.contains("45")));
  }

  @Test
  void utilityCrossingIsAllowedInPlan() {
    Feature gas =
        feature(
            "gas",
            "restriction",
            Geo.line(c(50, 0), c(50, 200)),
            "restriction_type",
            "gas_pipeline");
    SpatialRules.Assessment a =
        new SpatialRules(List.of(gas)).assess(Geo.line(c(0, 100), c(100, 100)), 100, null, null);
    assertTrue(a.valid(), a.issues.toString());
    assertEquals(48, a.passages.get(0).from, 1e-7);
    assertEquals(52, a.passages.get(0).to, 1e-7);
  }

  @Test
  void oldChamberReconstructionCountedOnce() {
    Store s = basic();
    s.add(
        feature(
            "up",
            "heat_network",
            Geo.line(c(0, 0), c(0, 100)),
            "diameter",
            50,
            "flow_tph",
            2,
            "upstream_object_id",
            "source"));
    s.add(
        feature(
            "camera",
            "heat_chamber",
            Geo.point(c(0, 100)),
            "diameter",
            50,
            "upstream_object_id",
            "up"));
    s.add(
        feature(
            "old",
            "heat_network",
            Geo.line(c(0, 100), c(0, 200)),
            "diameter",
            50,
            "flow_tph",
            2,
            "upstream_object_id",
            "camera"));
    Network n = one(s, 100, "a", 80);
    n.nodes.get("r").existingId = "camera";
    n.flows();
    Reconstruction out = Reconstruction.calculate(n, s);
    assertEquals(1, out.chambers.size());
    assertEquals(200, out.chambers.get("camera"));
    assertEquals(1, out.pieces.size());
  }

  @Test
  void completeSingleConsumerMatchesAppendixPrice() throws Exception {
    Store s = basic();
    Network n = one(s, 100, "a", 10);
    Evaluation e = new Evaluation(n, s, s.all("oks_future"), false, 6);
    assertEquals(
        100 * 83530 + 3000000 + 5000000 + 100 * 117582,
        (double) e.summary.get("calculated_cost"),
        .01);
    assertEquals(200, (double) e.summary.get("length"), 1e-5);
  }

  @Test
  void automaticPlannerAndExactExportSchema() throws Exception {
    Store s = basic();
    InputValidator.validate(s);
    Planner.Options options = new Planner.Options();
    options.candidateLimit = 2;
    Planner.Result r = new Planner(s, options, () -> false, (p, t) -> {}).calculate();
    assertFalse(r.variants.isEmpty());
    assertTrue(r.variants.get(0).network.connected.contains("a"), r.diagnostics.toString());
    Path output = temp.resolve("result.geojson");
    GeoJsonOutput.write(output, r, s, JSON);
    JsonNode root = JSON.readTree(output.toFile());
    assertEquals("FeatureCollection", root.path("type").asText());
    Set<String> ids = new HashSet<>();
    int summaries = 0;
    for (JsonNode f : root.path("features")) {
      JsonNode p = f.path("properties");
      assertTrue(ids.add(p.path("id").asText()));
      assertFalse(p.has("kind"));
      if (p.path("object_type").asText().equals("variant_summary")) {
        summaries++;
        assertTrue(f.path("geometry").isNull());
      }
      if (p.path("object_type").asText().equals("heat_network")) {
        assertTrue(p.has("flow_tph"));
        assertTrue(p.path("depth_start").isNull());
        assertTrue(p.path("depth_end").isNull());
      }
    }
    assertEquals(r.variants.size(), summaries);
  }

  @Test
  void streamedInputDoesNotNeedPropertyOrder() throws Exception {
    Path file = temp.resolve("data.geojson");
    Files.writeString(
        file,
        "{\"features\":[{\"properties\":{\"object_type\":\"source\",\"id\":\"s\"},\"geometry\":{\"type\":\"Point\",\"coordinates\":[37.6,55.75]},\"type\":\"Feature\"}],\"type\":\"FeatureCollection\"}");
    List<Feature> fs = new ArrayList<>();
    assertEquals(1, new GeoJsonInput(JSON).read(file, fs::add));
    assertEquals("s", fs.get(0).id);
  }

  @Test
  void duplicateKeysRejected() throws Exception {
    Path file = temp.resolve("bad.geojson");
    Files.writeString(
        file, "{\"type\":\"FeatureCollection\",\"type\":\"FeatureCollection\",\"features\":[]}");
    assertThrows(Exception.class, () -> new GeoJsonInput(JSON).read(file, f -> {}));
  }

  @Test
  void threeBuildingsExampleConnectsAllAndReconstructs() throws Exception {
    Store s = new Store();
    new GeoJsonInput(JSON).read(Path.of("../examples/complete.geojson"), s::add);
    InputValidator.validate(s);
    Planner.Options options = new Planner.Options();
    options.candidateLimit = 3;
    Planner.Result result = new Planner(s, options, () -> false, (p, t) -> {}).calculate();
    assertFalse(result.variants.isEmpty());
    Evaluation best = result.variants.get(0);
    assertEquals(
        Set.of("oks_A", "oks_B", "oks_C"), best.network.connected, result.diagnostics.toString());
    assertTrue((double) best.summary.get("reconstruction_cost") > 0);
    assertTrue(
        best.network.edges.values().stream().anyMatch(e -> e.flow > 35),
        "Ожидалась общая магистраль с суммой расходов");
    Path out = Path.of("target/example-result.geojson");
    GeoJsonOutput.write(out, result, s, JSON);
    JSON.writeValue(
        Path.of("target/example-summary.json").toFile(),
        result.variants.stream().map(v -> v.summary).toArray());
  }

  @Test
  void expensiveConnectionIsStillMandatory() {
    Store s = basic();
    s.add(feature("source", "source", Geo.point(c(0, -10000))));
    s.add(
        feature(
            "old",
            "heat_network",
            Geo.line(c(0, -10000), c(0, 200)),
            "diameter",
            50,
            "flow_tph",
            2,
            "upstream_object_id",
            "source"));
    InputValidator.validate(s);
    Planner.Options options = new Planner.Options();
    options.candidateLimit = 2;
    Planner.Result result = new Planner(s, options, () -> false, (p, t) -> {}).calculate();
    assertFalse(result.variants.isEmpty(), result.diagnostics.toString());
    assertTrue(result.variants.stream().allMatch(v -> v.network.connected.contains("a")));
    assertTrue((double) result.variants.get(0).summary.get("calculated_cost") > 105e6);
    assertFalse(result.variants.get(0).summary.containsKey("unconnected_penalty"));
  }
}
