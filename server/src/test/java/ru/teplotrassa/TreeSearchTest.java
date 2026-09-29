package ru.teplotrassa;

import static org.junit.jupiter.api.Assertions.*;
import static ru.teplotrassa.EngineTest.*;

import java.nio.file.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import ru.teplotrassa.api.ExperienceStore;
import ru.teplotrassa.data.*;
import ru.teplotrassa.engine.*;

class TreeSearchTest {
  @TempDir Path temp;

  static Planner.Options settings() {
    Planner.Options o = ReinforcementTest.settings();
    o.routingStrategy = "tree";
    o.treeBeamWidth = 3;
    o.treeExpansion = 3;
    o.treeRepairCandidates = 2;
    return o;
  }

  @Test
  void keepsAlternativeTreesAndReturnsOnlyExactCompleteNetworks() {
    Planner.Result r =
        new Planner(ReinforcementTest.district(), settings(), () -> false, (p, t) -> {})
            .calculate();
    assertFalse(r.variants.isEmpty(), r.diagnostics.toString());
    var info = JSON.valueToTree(r.search).path("treeSearch");
    assertTrue(info.path("levels").get(0).path("retainedStates").asInt() > 1);
    assertEquals(2, info.path("completedLevels").asInt());
    assertFalse(info.path("trainingDuringCalculation").asBoolean());
    assertFalse(info.path("diffusionModelUsed").asBoolean());
    assertTrue(
        info.path("graph").path("candidateEdges").asInt()
            > info.path("graph").path("skeletonEdges").asInt());
    for (Evaluation v : r.variants) {
      assertEquals(2, v.network.connected.size());
      for (String check :
          List.of(
              "originalEndpointsPreserved",
              "buildingEntriesValid",
              "topologyValid",
              "spatialRulesValid",
              "depthChecked")) assertEquals(true, v.checks.get(check), check);
      assertEquals("tree", v.summary.get("routing_strategy"));
    }
  }

  @Test
  void graphAccountsForObstacleDetoursAndHasBoundedSize() {
    Store store =
        new Store()
            .add(feature("wall", "restriction", box(40, -70, 20, 140), "restriction_type", "oks"));
    Planner.Options o = settings();
    o.gridM = 5;
    o.treeGraphMaxNodes = 500;
    CorridorGraph graph = new CorridorGraph(store, List.of(c(0, 0), c(100, 0)), o, () -> false);
    assertTrue(graph.distance(c(0, 0), c(100, 0)) > 150);
    assertTrue(((Number) graph.diagnostics().get("gridNodes")).intValue() <= 500);
  }

  @Test
  void cancellationDoesNotTurnPartialStatesIntoCompleteResults() {
    AtomicBoolean stop = new AtomicBoolean(false);
    Planner.Result r =
        new Planner(
                ReinforcementTest.district(),
                settings(),
                stop::get,
                (p, t) -> {
                  if (t.startsWith("Дерево:")) stop.set(true);
                })
            .calculate();
    assertEquals("CANCELLED", r.search.get("status"));
    assertTrue(r.variants.isEmpty());
  }

  @Test
  void branchReplacementIsTransactionalAndKeepsIncumbent() {
    Store store = ReinforcementTest.district();
    Planner.Options o = ReinforcementTest.settings();
    Planner.RlEpisode episode = new Planner(store, o, () -> false, (p, t) -> {}).newRlEpisode();
    while (!episode.actions().isEmpty()) {
      int choice = 0;
      for (int i = 0; i < episode.actions().size(); i++)
        if (episode.actions().get(i).targetKind.equals("existing")) {
          choice = i;
          break;
        }
      episode.step(choice);
    }
    Evaluation before = episode.evaluation();
    assertEquals(2, before.network.connected.size());
    String snapshot = JSON.valueToTree(NetworkSnapshot.write(before.network, JSON)).toString();
    Planner.Result r =
        new Planner(store, settings(), () -> false, (p, t) -> {})
            .withExperience(
                List.of(before.network),
                (state, best) -> {
                  assertNull(state);
                  for (Evaluation v : best) assertEquals(2, v.network.connected.size());
                })
            .calculate();
    assertTrue(r.variants.get(0).score <= before.score + 1e-8);
    assertEquals(
        snapshot, JSON.valueToTree(NetworkSnapshot.write(before.network, JSON)).toString());
    var info = JSON.valueToTree(r.search).path("treeSearch");
    assertTrue(info.path("resumedFromBestNetwork").asBoolean());
    assertFalse(info.path("sharedOptimization").path("groupTrials").isEmpty());
    for (var repair : info.path("repairs"))
      if (repair.path("accepted").asBoolean()) {
        assertTrue(repair.path("complete").asBoolean());
        assertTrue(
            repair.path("candidateScore").asDouble() < repair.path("beforeScore").asDouble());
      }
  }

  @Test
  void newSearchSettingsReuseExistingExperienceIdentity() throws Exception {
    ExperienceStore experience = new ExperienceStore(temp, JSON);
    Planner.Options o = new Planner.Options();
    o.dataMode = "scenario";
    o.existingLoadPercent = 50d;
    o.mode = "depth";
    o.gridM = 20;
    o.routingStrategy = "tree";
    assertEquals(
        "c5d4582c9243c08433b2b80130685f1e2df93ae32c9ec3a0303a1f84a5001464",
        experience.key("07921d7740c0297a63111846d4b77dfb6ccb33da65ffd7ccb14c5b2d786dd7d0", o));
    String before = experience.key("abc", o);
    o.treeBeamWidth = 9;
    o.treeRepairPasses = 3;
    assertEquals(before, experience.key("abc", o));
  }

  @Test
  void suppliedMapTreeSearch() throws Exception {
    org.junit.jupiter.api.Assumptions.assumeTrue(Boolean.getBoolean("treeSupplied"));
    Store raw = new Store();
    new GeoJsonInput(JSON).read(Path.of("../examples/supplied.geojson"), raw::add, false);
    Planner.Options o = new Planner.Options();
    o.routingStrategy = "tree";
    o.mode = "depth";
    o.gridM = 20;
    o.dataMode = "scenario";
    o.existingLoadPercent = 50d;
    FeatureStore store = InputData.scenario(raw, o);
    Planner.Result r =
        new Planner(store, o, () -> false, (p, t) -> System.out.println(p + "% " + t)).calculate();
    Path dir = Path.of("../validation/tree");
    Files.createDirectories(dir);
    Map<String, Object> report = new LinkedHashMap<>();
    report.put("options", o);
    report.put("elapsedMs", r.elapsedMs);
    report.put("scenarioChanges", InputData.scenarioChanges(store));
    report.put("search", r.search);
    report.put("variants", r.variants.stream().map(v -> v.summary).toArray());
    report.put("checks", r.variants.stream().map(v -> v.checks).toArray());
    report.put("diagnostics", r.diagnostics);
    JSON.writerWithDefaultPrettyPrinter()
        .writeValue(dir.resolve("supplied-tree-report.json").toFile(), report);
    GeoJsonOutput.write(dir.resolve("supplied-tree.geojson"), r, store, JSON);
    assertFalse(r.variants.isEmpty(), r.diagnostics.toString());
    for (Evaluation v : r.variants) assertEquals(17, v.network.connected.size());
  }
}
