package ru.teplotrassa;

import static org.junit.jupiter.api.Assertions.*;
import static ru.teplotrassa.EngineTest.*;

import java.nio.file.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import ru.teplotrassa.api.*;
import ru.teplotrassa.data.*;
import ru.teplotrassa.engine.*;

class TreeLearningTest {
  @TempDir Path temp;

  static List<TreePolicyTraining.Preference> examples() {
    List<TreePolicyTraining.Preference> pairs = new ArrayList<>();
    Random random = new Random(52);
    for (int k = 0; k < 20; k++) {
      double[] a = new double[ReinforcementFeatures.NAMES.size()], b = new double[a.length];
      for (int j = 0; j < a.length; j++) {
        a[j] = random.nextDouble();
        b[j] = random.nextDouble();
      }
      pairs.add(
          new TreePolicyTraining.Preference(TreePolicyTraining.digest("state" + k), a, b, 10, 12));
    }
    return pairs;
  }

  @Test
  void learnsPreferencesOnACopyAndResumesAdamExactly() throws Exception {
    TreePolicyTraining state = new TreePolicyTraining(ReinforcementPolicy.bundled());
    assertEquals(20, state.remember(examples()));
    assertEquals(0, state.remember(examples()));
    String original = state.policy().sha256;
    var proposal = state.propose(8, .001, () -> false);
    assertNotNull(proposal);
    assertTrue(proposal.lossAfter < proposal.lossBefore);
    assertEquals(original, state.policy().sha256, "A proposal is isolated until exact validation");
    assertEquals(0, state.updates);
    state.accept(proposal);
    assertTrue(
        (Boolean)
            LearningDiagnostics.weights(ReinforcementPolicy.bundled(), state.policy())
                .get("weightsChanged"));
    TreePolicyTraining restored =
        TreePolicyTraining.restore(JSON.readTree(JSON.writeValueAsBytes(state.snapshot())));
    var a = state.propose(4, .001, () -> false);
    var b = restored.propose(4, .001, () -> false);
    assertEquals(a.candidate.snapshot(), b.candidate.snapshot());
    String frozen = state.snapshot().toString();
    assertNull(state.propose(4, .001, () -> true));
    assertEquals(frozen, state.snapshot().toString());
    assertFalse(TreePolicyTraining.passesNetworkCheck(10d, 11d));
    assertFalse(TreePolicyTraining.passesNetworkCheck(10d, null));
    assertTrue(TreePolicyTraining.passesNetworkCheck(10d, 10d));
    assertTrue(TreePolicyTraining.passesNetworkCheck(null, 10d));
  }

  @Test
  void labelsOnlyComparableFullOutcomesAndRejectsTiesOrUnknowns() {
    TreeTrainingData data = new TreeTrainingData();
    double[] a = examples().get(0).preferred, b = examples().get(0).other;
    data.observe("same", "a", a, 10);
    data.observe("same", "b", b, 12);
    data.observe("different", "b", b, 2);
    data.observe("same", "failed", b, Double.POSITIVE_INFINITY);
    assertEquals(1, data.preferences().size());
    var label = data.preferences().get(0);
    assertEquals(10, label.preferredScore);
    assertEquals(12, label.otherScore);
    data.observe("same", "b", b, 10);
    assertTrue(data.preferences().isEmpty(), "Equal finalized scores supply no preference");
  }

  @Test
  void treeLearningKeepsBestNetworkAndSurvivesRestartWithoutTouchingRlState() throws Exception {
    Planner.Options o = TreeSearchTest.settings();
    o.treeRepairPasses = 1;
    o.treeLearning = true;
    o.treeLearningRounds = 2;
    o.treeTrainingSteps = 8;
    ExperienceStore memory = new ExperienceStore(temp, JSON);
    var session = memory.open(ExperienceTest.OWNER, ExperienceTest.SHA, UUID.randomUUID(), o);
    // Preserve an actual pending legacy RL batch, not merely an empty placeholder.
    ExperienceTest.complete(session.training, 1);
    session.save(session.training, List.of());
    String legacy = Files.readString(session.directory.resolve("training.json"));
    Planner.Result result =
        new Planner(ReinforcementTest.district(), o, () -> false, (p, t) -> {})
            .withTreeLearning(session.treeTraining, session::saveTree)
            .withExperience(session.networks, session::save)
            .calculate();
    var info = JSON.valueToTree(result.search).path("treeLearning");
    assertEquals(2, info.path("completedSearchPasses").asInt());
    assertTrue(info.path("observedPreferencePairs").asInt() > 0);
    assertTrue(info.path("gradientStepsThisRun").asInt() > 0);
    assertFalse(result.variants.isEmpty());
    assertTrue(result.variants.get(0).score <= info.path("baselineFreshScore").asDouble() + 1e-8);
    for (Evaluation v : result.variants) {
      assertEquals(2, v.network.connected.size());
      assertEquals(true, v.checks.get("buildingIntersectionsValid"));
      assertEquals(true, v.checks.get("originalEndpointsPreserved"));
    }
    for (var update : info.path("updateHistory"))
      if (update.path("accepted").asBoolean())
        assertTrue(
            update.path("validationScore").asDouble()
                <= update.path("referenceScore").asDouble() + 1e-8);
    assertEquals(legacy, Files.readString(session.directory.resolve("training.json")));
    var restored =
        new ExperienceStore(temp, JSON)
            .open(ExperienceTest.OWNER, ExperienceTest.SHA, UUID.randomUUID(), o);
    assertTrue(restored.treeResumed);
    assertEquals(session.treeTraining.snapshot(), restored.treeTraining.snapshot());
    assertTrue(restored.treeTraining.examples() > 0);
    assertFalse(restored.networks.isEmpty());
    o.treeLearning = false;
    String before = restored.treeTraining.snapshot().toString();
    Planner.Result next =
        new Planner(ReinforcementTest.district(), o, () -> false, (p, t) -> {})
            .withTreeLearning(restored.treeTraining, null)
            .withExperience(restored.networks, restored::save)
            .calculate();
    assertTrue(next.variants.get(0).score <= result.variants.get(0).score + 1e-8);
    assertEquals(before, restored.treeTraining.snapshot().toString());
    assertEquals(legacy, Files.readString(session.directory.resolve("training.json")));
  }

  @Test
  void cancellationBetweenPassesDoesNotPromoteUnvalidatedWeights() {
    Planner.Options o = TreeSearchTest.settings();
    o.treeLearning = true;
    TreePolicyTraining training = new TreePolicyTraining(ReinforcementPolicy.bundled());
    AtomicBoolean stop = new AtomicBoolean();
    Planner.Result result =
        new Planner(
                ReinforcementTest.district(),
                o,
                stop::get,
                (p, text) -> {
                  if (text.contains("Дообучение")) stop.set(true);
                })
            .withTreeLearning(training, null)
            .calculate();
    assertEquals("CANCELLED", result.search.get("status"));
    assertEquals(ReinforcementPolicy.bundled().sha256, training.policy().sha256);
    assertEquals(0, training.updates);
    for (Evaluation v : result.variants) assertEquals(2, v.network.connected.size());
  }

  @Test
  void learningSettingsDoNotInvalidateExistingTerritoryExperience() throws Exception {
    ExperienceStore memory = new ExperienceStore(temp, JSON);
    Planner.Options o = new Planner.Options();
    o.dataMode = "scenario";
    o.existingLoadPercent = 50d;
    o.mode = "depth";
    o.gridM = 20;
    assertEquals(
        "c5d4582c9243c08433b2b80130685f1e2df93ae32c9ec3a0303a1f84a5001464",
        memory.key("07921d7740c0297a63111846d4b77dfb6ccb33da65ffd7ccb14c5b2d786dd7d0", o));
    String key = memory.key("input", o);
    o.treeLearning = true;
    o.treeLearningRounds = 3;
    o.treeTrainingSteps = 4;
    o.treeTrainingRate = .0002;
    assertEquals(key, memory.key("input", o));
  }

  @Test
  void suppliedMapLearnsAndValidatesNextPass() throws Exception {
    org.junit.jupiter.api.Assumptions.assumeTrue(Boolean.getBoolean("treeLearningSupplied"));
    Store raw = new Store();
    new GeoJsonInput(JSON).read(Path.of("../examples/supplied.geojson"), raw::add, false);
    Planner.Options o = new Planner.Options();
    o.routingStrategy = "tree";
    o.treeLearning = true;
    o.mode = "depth";
    o.gridM = 20;
    o.dataMode = "scenario";
    o.existingLoadPercent = 50d;
    FeatureStore store = InputData.scenario(raw, o);
    ExperienceStore memory = new ExperienceStore(temp, JSON);
    var session =
        memory.open(
            ExperienceTest.OWNER,
            "07921d7740c0297a63111846d4b77dfb6ccb33da65ffd7ccb14c5b2d786dd7d0",
            UUID.randomUUID(),
            o);
    java.util.concurrent.atomic.AtomicInteger last =
        new java.util.concurrent.atomic.AtomicInteger(-1);
    Planner.Result result =
        new Planner(
                store,
                o,
                () -> false,
                (percent, text) -> {
                  if (last.getAndSet(percent) != percent || text.contains("Дообучение"))
                    System.out.println(percent + "% " + text);
                })
            .withTreeLearning(session.treeTraining, session::saveTree)
            .withExperience(session.networks, session::save)
            .calculate();
    result.metadata.put("application", BuildInfo.details());
    Map<String, Object> report = new LinkedHashMap<>();
    report.put("application", BuildInfo.details());
    report.put("options", o);
    report.put("inputSha256", "07921d7740c0297a63111846d4b77dfb6ccb33da65ffd7ccb14c5b2d786dd7d0");
    report.put("search", result.search);
    report.put("elapsedMs", result.elapsedMs);
    report.put("variants", result.variants.stream().map(v -> v.summary).toArray());
    report.put("checks", result.variants.stream().map(v -> v.checks).toArray());
    report.put("diagnostics", result.diagnostics);
    report.put("scenarioChanges", InputData.scenarioChanges(store));
    Path output = Path.of("../validation/tree-learning");
    Files.createDirectories(output);
    JSON.writerWithDefaultPrettyPrinter()
        .writeValue(output.resolve("supplied-learning-report.json").toFile(), report);
    JSON.writerWithDefaultPrettyPrinter()
        .writeValue(
            output.resolve("supplied-learning-state.json").toFile(),
            session.treeTraining.snapshot());
    GeoJsonOutput.write(output.resolve("supplied-learning.geojson"), result, store, JSON);
    var learning = JSON.valueToTree(result.search).path("treeLearning");
    System.out.println("LEARNING_RESULT " + learning);
    assertEquals(2, learning.path("completedSearchPasses").asInt());
    assertTrue(learning.path("gradientStepsThisRun").asInt() > 0);
    assertTrue(result.variants.size() >= 1 && result.variants.size() <= 3);
    assertTrue(
        result.variants.get(0).score <= learning.path("baselineFreshScore").asDouble() + 1e-8);
    for (Evaluation v : result.variants) assertEquals(17, v.network.connected.size());
  }
}
