package ru.teplotrassa;

import static org.junit.jupiter.api.Assertions.*;
import static ru.teplotrassa.EngineTest.*;

import com.fasterxml.jackson.databind.*;
import java.nio.file.*;
import java.util.*;
import org.junit.jupiter.api.*;
import org.locationtech.jts.geom.*;
import ru.teplotrassa.api.BuildInfo;
import ru.teplotrassa.data.*;
import ru.teplotrassa.engine.*;

class JunctionSuppliedTest {
  @Test
  void improvesReleasedNetworkWithEveryOriginalEndpoint() throws Exception {
    Assumptions.assumeTrue(Boolean.getBoolean("junctionSupplied"));
    Store raw = new Store();
    new GeoJsonInput(JSON).read(Path.of("../examples/supplied.geojson"), raw::add, false);
    Planner.Options o = new Planner.Options();
    o.routingStrategy = "tree";
    o.treeSingleRootRequired = true;
    o.treeGroupRepair = false;
    o.dataMode = "scenario";
    o.existingLoadPercent = 50d;
    o.mode = "depth";
    o.gridM = 20;
    o.treeRepairCandidates = Integer.getInteger("junctionCandidates", 8);
    o.treeRepairPasses = Integer.getInteger("junctionPasses", 2);
    FeatureStore store = InputData.scenario(raw, o);
    Planner planner = new Planner(store, o, () -> false, (p, m) -> System.out.println(m));
    Network original = readReleased(store);
    Evaluation before = planner.validateIncumbent(original);
    assertEquals(2150.9262171442206, (double) before.summary.get("new_network_length"), 1e-4);
    Path out = Path.of("../validation/junctions");
    Files.createDirectories(out);
    JSON.writerWithDefaultPrettyPrinter()
        .writeValue(
            out.resolve("baseline-snapshot.json").toFile(),
            NetworkSnapshot.write(before.network, JSON));
    JunctionOptimizer optimizer =
        new JunctionOptimizer(
            store, o, () -> false, planner::validateIncumbent, (p, m) -> System.out.println(m));
    Evaluation after =
        optimizer.improve(
            before,
            v -> {
              System.out.println("IMPROVEMENT " + v.summary);
              try {
                JSON.writerWithDefaultPrettyPrinter()
                    .writeValue(
                        out.resolve("checkpoint.json").toFile(),
                        NetworkSnapshot.write(v.network, JSON));
              } catch (Exception e) {
                throw new RuntimeException(e);
              }
            });
    after.summary.put("rank", 1);
    after.summary.put("rating", 100d);
    after.summary.put("source", "joint_junction_replacement");
    Planner.Result result = new Planner.Result();
    result.variants.add(after);
    result.metadata.put("application", BuildInfo.details());
    result.search.put("junctionOptimization", optimizer.diagnostics);
    GeoJsonOutput.write(out.resolve("supplied-junctions.geojson"), result, store, JSON);
    Map<String, Object> report = new LinkedHashMap<>();
    report.put("application", BuildInfo.details());
    report.put("options", o);
    report.put("before", before.summary);
    report.put("variants", List.of(after.summary));
    report.put("checks", List.of(after.checks));
    report.put("search", result.search);
    report.put("scenarioChanges", InputData.scenarioChanges(store));
    JSON.writerWithDefaultPrettyPrinter()
        .writeValue(out.resolve("supplied-junctions-report.json").toFile(), report);
    System.out.println("JUNCTION_RESULT " + JSON.writeValueAsString(after.summary));
    assertTrue(after.score < before.score);
    assertEquals(17, after.network.connected.size());
    assertEquals(1, after.network.roots().size());
    assertEquals(true, after.checks.get("originalEndpointsPreserved"));
  }

  static Network readReleased(FeatureStore store) throws Exception {
    return readReleased(store, Path.of("../validation/tree-trunk/supplied-trunk.geojson"));
  }

  static Network readReleased(FeatureStore store, Path file) throws Exception {
    JsonNode data =
        JSON.readTree(file.toFile());
    Network n = new Network();
    Map<String, List<JsonNode>> segments = new LinkedHashMap<>();
    for (JsonNode f : data.path("features")) {
      JsonNode p = f.path("properties");
      if (!p.path("variant_id").asText().equals("v1")) continue;
      String type = p.path("object_type").asText(), id = p.path("id").asText();
      if (type.equals("heat_chamber") || type.equals("tie_in")) {
        JsonNode xy = f.path("geometry").path("coordinates");
        Network.Node node =
            new Network.Node(
                id,
                Geo.xy(xy.get(0).asDouble(), xy.get(1).asDouble()),
                type.equals("tie_in") ? "tie" : "chamber");
        if (type.equals("tie_in")) node.existingId = p.path("existing_object_id").asText();
        n.nodes.put(id, node);
      } else if (type.equals("heat_network")) {
        String edgeId = id.substring(0, id.lastIndexOf(':'));
        segments.computeIfAbsent(edgeId, k -> new ArrayList<>()).add(f);
      }
    }
    for (Map.Entry<String, List<JsonNode>> entry : segments.entrySet()) {
      List<JsonNode> parts = entry.getValue();
      parts.sort(
          Comparator.comparingInt(
              f -> {
                String id = f.path("properties").path("id").asText();
                return Integer.parseInt(id.substring(id.lastIndexOf(':') + 1));
              }));
      String a = parts.get(0).path("properties").path("start_node_id").asText();
      String b = parts.get(parts.size() - 1).path("properties").path("end_node_id").asText();
      List<Coordinate> coords = new ArrayList<>();
      for (JsonNode part : parts)
        for (JsonNode xy : part.path("geometry").path("coordinates")) {
          Coordinate q = Geo.xy(xy.get(0).asDouble(), xy.get(1).asDouble());
          if (coords.isEmpty() || coords.get(coords.size() - 1).distance(q) > 1e-5) coords.add(q);
        }
      if (!n.nodes.containsKey(b)) {
        Feature f = store.get(b);
        assertNotNull(f, "Missing original input " + b);
        Network.Node node = new Network.Node(b, f.geometry.getCoordinate(), "oks");
        node.entryId = b;
        n.nodes.put(b, node);
        coords.set(coords.size() - 1, node.point.copy());
      }
      coords.set(0, n.nodes.get(a).point.copy());
      if (n.nodes.containsKey(b)) coords.set(coords.size() - 1, n.nodes.get(b).point.copy());
      n.edges.put(
          entry.getKey(),
          new Network.Edge(entry.getKey(), a, b, Geo.line(coords.toArray(new Coordinate[0]))));
    }
    Set<String> used = new HashSet<>();
    for (Network.Edge e : n.edges.values()) {
      used.add(e.start);
      used.add(e.end);
    }
    n.nodes.keySet().retainAll(used);
    return n;
  }
}
