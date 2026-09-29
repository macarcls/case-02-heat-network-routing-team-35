package ru.teplotrassa;

import static org.junit.jupiter.api.Assertions.*;
import static ru.teplotrassa.EngineTest.*;

import java.nio.file.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import ru.teplotrassa.api.BuildInfo;
import ru.teplotrassa.data.*;
import ru.teplotrassa.engine.*;

class TreeTrunkSuppliedTest {
  @Test
  void allOriginalEntriesConnectedToOneRoot() throws Exception {
    org.junit.jupiter.api.Assumptions.assumeTrue(Boolean.getBoolean("treeTrunkSupplied"));
    Store raw = new Store();
    new GeoJsonInput(JSON).read(Path.of("../examples/supplied.geojson"), raw::add, false);
    Planner.Options o = new Planner.Options();
    o.routingStrategy = "tree";
    o.mode = "depth";
    o.gridM = 20;
    o.dataMode = "scenario";
    o.existingLoadPercent = 50d;
    o.treeSingleRootRequired = true;
    o.treeBeamWidth = Integer.getInteger("trunkRoots", 5);
    o.treeGroupRepair = !Boolean.getBoolean("skipTrunkRepair");
    o.treeRepairPasses = Integer.getInteger("trunkRepairPasses", 2);
    FeatureStore store = InputData.scenario(raw, o);
    Planner p =
        new Planner(
            store,
            o,
            () -> false,
            (percent, message) -> System.out.println(percent + "% " + message));
    Path dir = Path.of(System.getProperty("trunkOutput", "../validation/junctions-full"));
    Files.createDirectories(dir);
    String seed = System.getProperty("trunkSeedSnapshot");
    List<Network> seeds =
        seed == null
            ? List.of()
            : List.of(NetworkSnapshot.read(JSON.readTree(Path.of(seed).toFile())));
    Planner.Result result =
        p.withExperience(
                seeds,
                (state, best) -> {
                  if (best.isEmpty()) return;
                  try {
                    JSON.writerWithDefaultPrettyPrinter()
                        .writeValue(
                            dir.resolve("checkpoint.json").toFile(),
                            NetworkSnapshot.write(best.get(0).network, JSON));
                  } catch (Exception e) {
                    throw new RuntimeException(e);
                  }
                })
            .calculate();
    result.metadata.put("application", BuildInfo.details());
    Map<String, Object> report = new LinkedHashMap<>();
    report.put("application", BuildInfo.details());
    report.put("options", o);
    report.put("elapsedMs", result.elapsedMs);
    report.put("inputSha256", "07921d7740c0297a63111846d4b77dfb6ccb33da65ffd7ccb14c5b2d786dd7d0");
    report.put("scenarioChanges", InputData.scenarioChanges(store));
    report.put("search", result.search);
    report.put("variants", result.variants.stream().map(v -> v.summary).toArray());
    report.put("checks", result.variants.stream().map(v -> v.checks).toArray());
    report.put("diagnostics", result.diagnostics);
    JSON.writerWithDefaultPrettyPrinter()
        .writeValue(dir.resolve("supplied-trunk-report.json").toFile(), report);
    if (!result.variants.isEmpty())
      GeoJsonOutput.write(dir.resolve("supplied-trunk.geojson"), result, store, JSON);
    System.out.println("TRUNK_RESULT " + JSON.writeValueAsString(report.get("variants")));
    assertFalse(result.variants.isEmpty());
    for (Evaluation v : result.variants) {
      assertEquals(17, v.network.connected.size());
      assertEquals(1, v.network.roots().size());
      for (String check :
          List.of(
              "topologyValid",
              "spatialRulesValid",
              "buildingEntriesValid",
              "originalEndpointsPreserved",
              "allConnectionsConnected")) assertEquals(true, v.checks.get(check), check);
    }
  }
}
