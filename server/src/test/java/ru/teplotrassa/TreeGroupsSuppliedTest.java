package ru.teplotrassa;

import static org.junit.jupiter.api.Assertions.*;
import static ru.teplotrassa.EngineTest.*;

import java.nio.file.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.locationtech.jts.geom.Coordinate;
import ru.teplotrassa.api.BuildInfo;
import ru.teplotrassa.data.*;
import ru.teplotrassa.engine.*;

class TreeGroupsSuppliedTest {
  @Test
  void suppliedMapPreservesSavedWinnerAndComparesGroupsAndSingleRoot() throws Exception {
    org.junit.jupiter.api.Assumptions.assumeTrue(Boolean.getBoolean("treeGroupsSupplied"));
    Store raw = new Store();
    new GeoJsonInput(JSON).read(Path.of("../examples/supplied.geojson"), raw::add, false);
    Planner.Options o = new Planner.Options();
    o.routingStrategy = "tree";
    o.mode = "depth";
    o.gridM = 20;
    o.dataMode = "scenario";
    o.existingLoadPercent = 50d;
    FeatureStore store = InputData.scenario(raw, o);
    Network seed = readSeed(Path.of(System.getProperty("treeSeedGeo")), store);
    Planner planner =
        new Planner(store, o, () -> false, (p, t) -> System.out.println(p + "% " + t));
    Evaluation before = planner.validateIncumbent(seed);
    System.out.println("SAVED_BASELINE " + before.score);
    assertEquals(23.61847781406965, before.score, 1e-6);
    Path checkpoint = Path.of("../validation/tree-groups");
    Files.createDirectories(checkpoint);
    Planner.Result result =
        planner
            .withExperience(
                List.of(seed),
                (state, best) -> {
                  if (best.isEmpty()) return;
                  try {
                    JSON.writerWithDefaultPrettyPrinter()
                        .writeValue(
                            checkpoint.resolve("last-best-network.json").toFile(),
                            Map.of(
                                "score",
                                best.get(0).score,
                                "network",
                                NetworkSnapshot.write(best.get(0).network, JSON)));
                  } catch (java.io.IOException e) {
                    throw new java.io.UncheckedIOException(e);
                  }
                })
            .calculate();
    result.metadata.put("application", BuildInfo.details());
    Map<String, Object> report = new LinkedHashMap<>();
    report.put("application", BuildInfo.details());
    report.put("options", o);
    report.put("scenarioChanges", InputData.scenarioChanges(store));
    report.put("inputSha256", "07921d7740c0297a63111846d4b77dfb6ccb33da65ffd7ccb14c5b2d786dd7d0");
    report.put("savedBaselineScore", before.score);
    report.put("elapsedMs", result.elapsedMs);
    report.put("search", result.search);
    report.put("variants", result.variants.stream().map(v -> v.summary).toArray());
    report.put("checks", result.variants.stream().map(v -> v.checks).toArray());
    report.put("diagnostics", result.diagnostics);
    Path dir = Path.of("../validation/tree-groups");
    Files.createDirectories(dir);
    JSON.writerWithDefaultPrettyPrinter()
        .writeValue(dir.resolve("supplied-groups-report.json").toFile(), report);
    GeoJsonOutput.write(dir.resolve("supplied-groups.geojson"), result, store, JSON);
    System.out.println("GROUPS_RESULT " + JSON.writeValueAsString(report.get("variants")));
    assertFalse(result.variants.isEmpty());
    assertTrue(result.variants.get(0).score <= before.score + 1e-8);
    for (Evaluation v : result.variants) {
      assertEquals(17, v.network.connected.size());
      for (String check :
          List.of(
              "topologyValid",
              "spatialRulesValid",
              "buildingEntriesValid",
              "originalEndpointsPreserved",
              "allConnectionsConnected")) assertEquals(true, v.checks.get(check), check);
    }
  }

  private Network readSeed(Path path, FeatureStore store) throws Exception {
    Network n = new Network();
    var features = JSON.readTree(path.toFile()).path("features");
    for (var f : features) {
      var p = f.path("properties");
      if (!p.path("variant_id").asText().equals("v1")) continue;
      String type = p.path("object_type").asText();
      if (!List.of("tie_in", "heat_chamber").contains(type)) continue;
      var xy = f.path("geometry").path("coordinates");
      Network.Node node =
          new Network.Node(
              p.path("id").asText(),
              Geo.xy(xy.get(0).asDouble(), xy.get(1).asDouble()),
              type.equals("tie_in") ? "tie" : "chamber");
      if (type.equals("tie_in")) node.existingId = p.path("existing_object_id").asText();
      n.nodes.put(node.id, node);
    }
    for (var f : features) {
      var p = f.path("properties");
      if (!p.path("variant_id").asText().equals("v1")
          || !p.path("object_type").asText().equals("heat_network")) continue;
      String start = p.path("start_node_id").asText(), end = p.path("end_node_id").asText();
      List<Coordinate> coords = new ArrayList<>();
      for (var xy : f.path("geometry").path("coordinates"))
        coords.add(Geo.xy(xy.get(0).asDouble(), xy.get(1).asDouble()));
      if (!n.nodes.containsKey(end)) {
        assertEquals("oks_connection_point", store.get(end).type);
        Network.Node leaf = new Network.Node(end, InputData.portal(store.get(end)), "oks");
        leaf.entryId = end;
        n.nodes.put(end, leaf);
        coords.set(coords.size() - 1, leaf.point);
      }
      Network.Edge edge =
          new Network.Edge(
              p.path("id").asText(), start, end, Geo.line(coords.toArray(new Coordinate[0])));
      n.edges.put(edge.id, edge);
    }
    // Export includes priced bend chambers along polylines; they are not topology vertices.
    n.nodes.values().removeIf(node -> n.incident(node.id).isEmpty());
    return n;
  }
}
