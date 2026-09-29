package ru.teplotrassa;

import static org.junit.jupiter.api.Assertions.*;
import static ru.teplotrassa.EngineTest.*;

import java.nio.file.*;
import java.util.*;
import org.junit.jupiter.api.*;
import ru.teplotrassa.data.*;
import ru.teplotrassa.engine.*;

class GeometrySuppliedTest {
  @Test
  void improvesArchived180AndExportsAuditableResult() throws Exception {
    Assumptions.assumeTrue(Boolean.getBoolean("geometrySupplied"));
    Store raw = new Store();
    new GeoJsonInput(JSON).read(Path.of("../examples/supplied.geojson"), raw::add, false);
    Planner.Options o = new Planner.Options();
    o.routingStrategy = "tree";
    o.treeSingleRootRequired = true;
    o.dataMode = "scenario";
    o.existingLoadPercent = 50d;
    o.mode = "depth";
    o.gridM = 20;
    o.treeLearning = false;
    o.treeGroupRepair = false;
    o.treeRepairPasses = Integer.getInteger("geometryPasses", 2);
    FeatureStore store = InputData.scenario(raw, o);
    Planner planner = new Planner(store, o, () -> false, (p, m) -> {});
    Network input =
        JunctionSuppliedTest.readReleased(
            store,
            Path.of(
                System.getProperty(
                    "geometryInput", "../validation/junctions-full/supplied-trunk.geojson")));
    Evaluation before = planner.validateIncumbent(input);
    Path dir = Path.of(System.getProperty("geometryOutput", "../validation/geometry"));
    Files.createDirectories(dir);
    JSON.writerWithDefaultPrettyPrinter()
        .writeValue(
            dir.resolve("baseline-snapshot.json").toFile(),
            NetworkSnapshot.write(before.network, JSON));
    GeometricOptimizer optimizer =
        new GeometricOptimizer(
            store, o, () -> false, planner::validateIncumbent, (p, m) -> System.out.println(m));
    Evaluation after =
        optimizer.improve(
            before,
            v -> {
              System.out.println("GEOMETRY_IMPROVEMENT " + v.summary);
              try {
                JSON.writerWithDefaultPrettyPrinter()
                    .writeValue(
                        dir.resolve("checkpoint.json").toFile(),
                        NetworkSnapshot.write(v.network, JSON));
              } catch (Exception e) {
                throw new RuntimeException(e);
              }
            });
    after.summary.put("rank", 1);
    after.summary.put("rating", 100d);
    Planner.Result result = new Planner.Result();
    result.variants.add(after);
    result.search.put("geometry", optimizer.diagnostics);
    GeoJsonOutput.write(dir.resolve("supplied-geometry.geojson"), result, store, JSON);
    Map<String, Object> report = new LinkedHashMap<>();
    report.put("options", o);
    report.put("before", before.summary);
    report.put("variants", List.of(after.summary));
    report.put("checks", List.of(after.checks));
    report.put("search", result.search);
    report.put("scenarioChanges", InputData.scenarioChanges(store));
    JSON.writerWithDefaultPrettyPrinter()
        .writeValue(dir.resolve("supplied-geometry-report.json").toFile(), report);
    System.out.println("GEOMETRY_FINAL " + after.summary);
    assertTrue(after.score < before.score);
    assertEquals(17, after.network.connected.size());
    assertEquals(1, after.network.roots().size());
    assertEquals(true, after.checks.get("originalEndpointsPreserved"));
  }
}
