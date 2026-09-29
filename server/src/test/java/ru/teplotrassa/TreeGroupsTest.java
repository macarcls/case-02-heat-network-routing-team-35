package ru.teplotrassa;

import static org.junit.jupiter.api.Assertions.*;
import static ru.teplotrassa.EngineTest.*;

import java.util.*;
import org.junit.jupiter.api.Test;
import ru.teplotrassa.engine.*;

class TreeGroupsTest {
  private Planner.Options settings() {
    Planner.Options o = TreeSearchTest.settings();
    o.treeRepairPasses = 4;
    return o;
  }

  @Test
  void groupsReuseSavedTreeAndCommonRootAlwaysConnectsAllEntries() {
    Store store = ReinforcementTest.district();
    Planner.Options base = settings();
    base.treeGroupRepair = false;
    base.treeSingleRootTrial = false;
    Planner.Result before = new Planner(store, base, () -> false, (p, t) -> {}).calculate();
    Network restored =
        NetworkSnapshot.read(NetworkSnapshot.write(before.variants.get(0).network, JSON));
    String snapshot = NetworkSnapshot.write(restored, JSON).toString();
    Planner.Result result =
        new Planner(store, settings(), () -> false, (p, t) -> {})
            .withExperience(List.of(restored), null)
            .calculate();
    assertEquals(snapshot, NetworkSnapshot.write(restored, JSON).toString());
    assertTrue(result.variants.get(0).score <= before.variants.get(0).score + 1e-8);
    var tree = JSON.valueToTree(result.search).path("treeSearch");
    assertTrue(tree.path("completedRepairPasses").asInt() < base.treeRepairPasses);
    var shared = tree.path("sharedOptimization");
    assertTrue(shared.path("groupTrials").size() > 0);
    assertTrue(shared.path("singleRootTrials").size() > 0);
    int full = 0;
    for (var trial : shared.path("singleRootTrials"))
      if (trial.path("complete").asBoolean()) {
        full++;
        assertEquals(2, trial.path("connected").asInt());
        assertEquals(1, trial.path("tieIns").asInt());
      }
    assertTrue(full > 0, shared.toString());
    for (Evaluation v : result.variants) {
      assertEquals(2, v.network.connected.size());
      assertEquals(true, v.checks.get("buildingEntriesValid"));
      assertEquals(true, v.checks.get("originalEndpointsPreserved"));
      assertEquals(true, v.checks.get("topologyValid"));
    }
  }

  @Test
  void commonRootComparisonCannotDisplaceCheaperIndependentConnections() {
    Store store = basic();
    store.map.remove("a");
    store.map.remove("entry_a");
    store.add(
        feature(
            "old",
            "heat_network",
            Geo.line(c(0, 0), c(0, 500)),
            "diameter",
            200,
            "flow_tph",
            2,
            "upstream_object_id",
            "source"));
    for (int i = 0; i < 2; i++) {
      double y = 50 + i * 400;
      store.add(feature("d" + i, "oks_future", box(10, y - 5, 10, 10), "flow_tph", 2));
      store.add(
          feature("entry_d" + i, "oks_connection_point", Geo.point(c(10, y)), "oks_id", "d" + i));
    }
    Planner.Options plain = settings();
    plain.treeSingleRootTrial = false;
    plain.treeGroupRepair = false;
    Planner.Result before = new Planner(store, plain, () -> false, (p, t) -> {}).calculate();
    assertFalse(before.variants.isEmpty());
    Planner.Options o = settings();
    o.treeGroupRepair = false;
    Planner.Result result =
        new Planner(store, o, () -> false, (p, t) -> {})
            .withExperience(List.of(before.variants.get(0).network), null)
            .calculate();
    assertTrue(result.variants.get(0).score <= before.variants.get(0).score + 1e-8);
    assertEquals(2, result.variants.get(0).network.roots().size());
    var shared = JSON.valueToTree(result.search).path("treeSearch").path("sharedOptimization");
    for (var trial : shared.path("singleRootTrials"))
      if (trial.path("complete").asBoolean())
        assertTrue(trial.path("candidateScore").asDouble() >= result.variants.get(0).score - 1e-8);
  }

  @Test
  void farEntryIsRetriedAfterNearbyBranchesExtendTheSingleRootTree() {
    Store store = basic();
    store.map.remove("a");
    store.map.remove("entry_a");
    for (int i = 0; i < 3; i++) {
      double x = 100 + i * 120, y = 40 + i * 20;
      store.add(feature("d" + i, "oks_future", box(x, y - 5, 8, 10), "flow_tph", 2));
      store.add(
          feature("entry_d" + i, "oks_connection_point", Geo.point(c(x, y)), "oks_id", "d" + i));
    }
    Planner.Options o = settings();
    o.treeGroupRepair = false;
    o.treeRepairPasses = 0;
    Planner.Result result = new Planner(store, o, () -> false, (p, t) -> {}).calculate();
    var trials =
        JSON.valueToTree(result.search)
            .path("treeSearch")
            .path("sharedOptimization")
            .path("singleRootTrials");
    boolean completedFarFirst = false;
    for (var t : trials)
      if (t.path("order").asText().equals("far_to_near") && t.path("complete").asBoolean()) {
        completedFarFirst = true;
        assertEquals(3, t.path("connected").asInt());
        assertEquals(1, t.path("tieIns").asInt());
      }
    assertTrue(completedFarFirst, trials.toString());
  }

  @Test
  void rejectedTrainingShrinksBothStepAndMovementAndPersistsAcrossRestart() throws Exception {
    TreePolicyTraining state = new TreePolicyTraining(ReinforcementPolicy.bundled());
    state.remember(TreeLearningTest.examples());
    state.recordValidation(false);
    assertEquals(.5, state.rateScale);
    TreePolicyTraining restored = TreePolicyTraining.restore(state.snapshot());
    assertEquals(.5, restored.rateScale);
    String frozen = restored.policy().sha256;
    var proposal =
        restored.propose(16, .001 * restored.rateScale, .25 * restored.rateScale, () -> false);
    assertNotNull(proposal);
    var change = LearningDiagnostics.weights(state.policy(), proposal.candidate.policy());
    assertTrue(((Number) change.get("weightDeltaL2")).doubleValue() <= .125 + 1e-9);
    assertEquals(frozen, restored.policy().sha256);
    for (int i = 0; i < 20; i++) restored.recordValidation(false);
    assertEquals(1.0 / 32, restored.rateScale);
  }

  @Test
  void cachedTransitionsPreservePreferenceLabelsAcrossValidationPasses() {
    Planner.Options o = settings();
    o.treeLearning = true;
    o.treeLearningRounds = 4;
    o.treeGroupRepair = false;
    o.treeSingleRootTrial = false;
    Planner.Result result =
        new Planner(ReinforcementTest.district(), o, () -> false, (p, t) -> {}).calculate();
    var search = JSON.valueToTree(result.search);
    assertTrue(search.path("treeSearch").path("transitionCacheHits").asLong() > 0);
    var passes = search.path("treeSearch").path("passes");
    assertTrue(passes.size() >= 2);
    for (var pass : passes) assertTrue(pass.path("preferencePairs").asInt() > 0);
    assertTrue(
        result.variants.get(0).score
            <= search.path("treeLearning").path("baselineFreshScore").asDouble() + 1e-8);
  }
}
