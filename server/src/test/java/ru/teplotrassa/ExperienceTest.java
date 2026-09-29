package ru.teplotrassa;

import static org.junit.jupiter.api.Assertions.*;
import static ru.teplotrassa.EngineTest.*;

import com.fasterxml.jackson.databind.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import ru.teplotrassa.api.*;
import ru.teplotrassa.engine.*;

class ExperienceTest {
  @TempDir Path dir;
  static final String OWNER = "a".repeat(64), OTHER = "b".repeat(64), SHA = "31".repeat(32);

  @Test
  void singleRootExperienceDoesNotOverwritePreviousMode() throws Exception {
    ExperienceStore memory = new ExperienceStore(dir, JSON);
    Planner.Options o = new Planner.Options();
    o.routingStrategy = "tree";
    String oldKey = memory.key(SHA, o);
    var previous = memory.open(OWNER, SHA, UUID.randomUUID(), o);
    complete(previous.training, 0);
    previous.save(previous.training, List.of());
    o.treeSingleRootRequired = true;
    assertNotEquals(oldKey, memory.key(SHA, o));
    var strict = memory.open(OWNER, SHA, UUID.randomUUID(), o);
    assertFalse(strict.resumed);
    assertEquals(0, strict.training.rollouts);
    o.treeSingleRootRequired = false;
    assertEquals(oldKey, memory.key(SHA, o));
    var restored = new ExperienceStore(dir, JSON).open(OWNER, SHA, UUID.randomUUID(), o);
    assertTrue(restored.resumed);
    assertEquals(1, restored.training.pendingCount());
    o.routingStrategy = "classic";
    o.treeSingleRootRequired = true;
    assertEquals(oldKey, memory.key(SHA, o));
  }

  @Test
  void junctionSearchUsesTheSameExperienceKeyAsReleasedVersion() throws Exception {
    ExperienceStore memory = new ExperienceStore(dir, JSON);
    Planner.Options o = new Planner.Options();
    o.routingStrategy = "tree";
    o.treeSingleRootRequired = true;
    String enabled = memory.key(SHA, o);
    o.treeJunctionRepair = false;
    assertEquals(enabled, memory.key(SHA, o));
    o.treeGeometricSearch = false;
    assertEquals(enabled, memory.key(SHA, o));
    o.treeGroupRepair = !o.treeGroupRepair;
    assertEquals(enabled, memory.key(SHA, o));
  }

  @Test
  void gradientsIncludingTemperatureAgreeWithFiniteDifferences() throws Exception {
    ReinforcementPolicy base = ReinforcementPolicy.bundled();
    PolicyTraining t = new PolicyTraining(base);
    t.configure(4, .7, .003);
    double[][] x = new double[3][ReinforcementFeatures.NAMES.size()];
    Random rng = new Random(17);
    for (double[] row : x) for (int i = 0; i < row.length; i++) row[i] = rng.nextGaussian() * 2;
    x[0][0] = 15; // Input clipping is part of both forward and backward passes.
    PolicyTraining.Sample s = t.newSample();
    t.observe(s, x, 1);
    double[] weights = t.parameters();
    double eps = 1e-5;
    for (int i = 0; i < weights.length; i += 7) {
      double[] plus = weights.clone(), minus = weights.clone();
      plus[i] += eps;
      minus[i] -= eps;
      double[] a = objective(PolicyTraining.withParameters(base, plus, "plus"), x, .7);
      double[] b = objective(PolicyTraining.withParameters(base, minus, "minus"), x, .7);
      assertEquals((a[0] - b[0]) / (2 * eps), s.score[i], 2e-8, "log pi parameter " + i);
      assertEquals((a[1] - b[1]) / (2 * eps), s.entropy[i], 2e-8, "entropy parameter " + i);
    }
  }

  static double[] objective(ReinforcementPolicy p, double[][] x, double temp) {
    double[] logits = Arrays.stream(x).mapToDouble(p::logit).toArray();
    double max = Arrays.stream(logits).max().orElseThrow(), sum = 0, entropy = 0;
    for (int i = 0; i < logits.length; i++) {
      logits[i] = Math.exp((logits[i] - max) / temp);
      sum += logits[i];
    }
    for (int i = 0; i < logits.length; i++) {
      logits[i] /= sum;
      entropy -= logits[i] * Math.log(logits[i]);
    }
    return new double[] {Math.log(logits[1]), entropy};
  }

  @Test
  void unfinishedBatchAndAdamResumeExactlyWithoutReplayingActions() throws Exception {
    PolicyTraining t = new PolicyTraining(ReinforcementPolicy.bundled());
    for (int episode = 0; episode < 2; episode++) complete(t, episode);
    assertEquals(0, t.updates);
    assertEquals(2, t.pendingCount());
    PolicyTraining restored =
        PolicyTraining.restore(JSON.readTree(JSON.writeValueAsBytes(t.snapshot())));
    for (int i = 2; i < 12; i++) {
      complete(t, i);
      complete(restored, i);
    }
    assertEquals(t.snapshot(), restored.snapshot());
    assertEquals(3, t.updates);
    assertNotEquals(ReinforcementPolicy.bundled().sha256, t.policy().sha256);
    complete(t, 13);
    t.configure(4, 1.0, .003);
    assertEquals(0, t.pendingCount());
    assertEquals(1, t.discardedPending);
  }

  static void complete(PolicyTraining t, int episode) {
    double[][] x = new double[3][ReinforcementFeatures.NAMES.size()];
    Random random = new Random(episode);
    for (double[] row : x) for (int i = 0; i < row.length; i++) row[i] = random.nextDouble();
    var sample = t.newSample();
    t.observe(sample, x, episode % 3);
    t.complete(sample, -.1 * (episode % 5));
  }

  @Test
  void plannerChangesWeightsResumesAndNeverLosesBestCompleteNetwork() throws Exception {
    ExperienceStore memory = new ExperienceStore(dir, JSON);
    Planner.Options o = ReinforcementTest.settings();
    o.rlLearning = true;
    o.rlEpisodes = 6;
    Planner.Result first;
    String initialHash = ReinforcementPolicy.bundled().sha256;
    try (var lease = memory.lock(OWNER, () -> false)) {
      var s = memory.open(OWNER, SHA, UUID.randomUUID(), o);
      first =
          new Planner(ReinforcementTest.district(), o, () -> false, (p, t) -> {})
              .withLearning(s.training)
              .withExperience(s.networks, s::save)
              .calculate();
      assertFalse(first.variants.isEmpty());
      assertEquals(1, s.training.updates);
      assertEquals(1, s.training.pendingCount());
      assertNotEquals(initialHash, s.training.policy().sha256);
      var learning = JSON.valueToTree(first.search.get("reinforcement"));
      assertTrue(learning.path("weightsChanged").asBoolean());
      assertTrue(learning.path("weightDeltaL2").asDouble() > 0);
      assertTrue(learning.path("changedWeightCount").asInt() > 0);
      assertEquals(0, learning.path("initialUpdates").asInt());
    }
    // New store instance is equivalent to restarting the server and uploading identical bytes
    // again.
    memory = new ExperienceStore(dir, JSON);
    o.rlEpisodes = 4;
    try (var lease = memory.lock(OWNER, () -> false)) {
      var s = memory.open(OWNER, SHA, UUID.randomUUID(), o);
      assertTrue(s.resumed);
      assertEquals(6, s.training.rollouts);
      Planner.Result next =
          new Planner(ReinforcementTest.district(), o, () -> false, (p, t) -> {})
              .withLearning(s.training)
              .withExperience(s.networks, s::save)
              .calculate();
      assertEquals(2, s.training.updates);
      assertEquals(10, s.training.rollouts);
      assertEquals(0, s.training.pendingCount());
      assertTrue(((Number) next.search.get("restoredNetworks")).intValue() > 0);
      assertTrue(next.variants.get(0).score <= first.variants.get(0).score + 1e-10);
      var progress = JSON.valueToTree(next.search.get("qualityProgress"));
      assertEquals(first.variants.get(0).score, progress.path("bestBeforeRun").asDouble(), 1e-8);
      assertEquals(next.variants.get(0).score, progress.path("bestAfterRun").asDouble(), 1e-8);
      assertEquals("saved_network", progress.path("baselineSource").asText());
      assertEquals(
          1, JSON.valueToTree(next.search.get("reinforcement")).path("initialUpdates").asInt());
      assertEquals(2, next.variants.get(0).network.connected.size());
      assertEquals(true, next.variants.get(0).checks.get("buildingEntriesValid"));
      assertEquals(true, next.variants.get(0).checks.get("depthChecked"));
      String frozen = s.training.snapshot().toString();
      new Planner(ReinforcementTest.district(), o, () -> true, (p, t) -> {})
          .withLearning(s.training)
          .withExperience(s.networks, s::save)
          .calculate();
      assertEquals(frozen, s.training.snapshot().toString());
    }
    var outsider = memory.open(OTHER, SHA, UUID.randomUUID(), o);
    assertFalse(outsider.resumed);
    assertTrue(outsider.networks.isEmpty());
    String key = memory.key(SHA, o);
    o.routingStrategy = "classic";
    assertEquals(key, memory.key(SHA, o));
    o.maxDepthM = 7;
    assertNotEquals(key, memory.key(SHA, o));
    assertNotEquals(key, memory.key("20".repeat(32), o));
  }

  @Test
  void bestNetworksAreRevalidatedAndCanBeReusedFromClassicSearch() throws Exception {
    Planner.Options o = ReinforcementTest.settings();
    o.routingStrategy = "classic";
    var r = new Planner(ReinforcementTest.district(), o, () -> false, (p, t) -> {}).calculate();
    assertFalse(r.variants.isEmpty());
    var encoded = NetworkSnapshot.write(r.variants.get(0).network, JSON);
    Network restored = NetworkSnapshot.read(encoded);
    o.routingStrategy = "reinforcement";
    o.rlEpisodes = 1;
    Planner p = new Planner(ReinforcementTest.district(), o, () -> false, (a, b) -> {});
    Evaluation validated = p.validateIncumbent(restored);
    assertEquals(r.variants.get(0).score, validated.score, 1e-8);
    var next = p.withExperience(List.of(restored), null).calculate();
    assertTrue(next.variants.get(0).score <= validated.score + 1e-10);
    restored.nodes.values().stream()
            .filter(n -> n.type.equals("oks"))
            .findFirst()
            .orElseThrow()
            .point
            .x +=
        10;
    assertThrows(IllegalArgumentException.class, () -> p.validateIncumbent(restored));
  }

  @Test
  void checksumFallbackAndCancellableLockProtectCheckpoints() throws Exception {
    ExperienceStore store = new ExperienceStore(dir, JSON);
    Path file = store.ownerDirectory(OWNER).resolve("test.json");
    store.write(file, JSON.createObjectNode().put("version", 1));
    store.write(file, JSON.createObjectNode().put("version", 2));
    Files.writeString(file, "broken");
    assertEquals(1, store.read(file).path("version").asInt());
    store.write(file, JSON.createObjectNode().put("version", 3));
    Files.writeString(file, "broken again");
    assertEquals(1, store.read(file).path("version").asInt());
    Files.writeString(file.resolveSibling("test.json.bak"), "broken");
    assertThrows(java.io.IOException.class, () -> store.read(file));
    ExecutorService executor = Executors.newSingleThreadExecutor();
    AtomicBoolean stop = new AtomicBoolean();
    try (var lease = store.lock(OWNER, () -> false)) {
      Future<?> waiting =
          executor.submit(
              () -> {
                assertThrows(CancellationException.class, () -> store.lock(OWNER, stop::get));
              });
      stop.set(true);
      waiting.get(2, TimeUnit.SECONDS);
      try (var other = store.lock(OTHER, () -> false)) {
        assertNotNull(other);
      }
    } finally {
      executor.shutdownNow();
    }
  }

  @Test
  void generalPromotionRequiresImprovementWithoutRegressionAndHonoursFixedHoldout()
      throws Exception {
    Map<String, Object> improved =
        Map.of(
            "baselineReward",
            -.6,
            "candidateReward",
            -.5,
            "baselineComplete",
            true,
            "candidateComplete",
            true);
    Map<String, Object> equal =
        Map.of(
            "baselineReward",
            -.6,
            "candidateReward",
            -.6,
            "baselineComplete",
            true,
            "candidateComplete",
            true);
    Map<String, Object> worse =
        Map.of(
            "baselineReward",
            -.5,
            "candidateReward",
            -.6,
            "baselineComplete",
            true,
            "candidateComplete",
            true);
    assertTrue(GeneralTraining.passesGate(List.of(improved, equal)));
    assertFalse(GeneralTraining.passesGate(List.of(improved, worse)));
    assertFalse(GeneralTraining.passesGate(List.of(equal, equal)));
    ExperienceStore store = new ExperienceStore(dir, JSON);
    Planner.Options o = ReinforcementTest.settings();
    for (String sha : List.of("01", "02", "05", "0a"))
      store.open(OWNER, sha.repeat(32), UUID.randomUUID(), o);
    assertEquals(true, store.status(OWNER).get("canTrainGeneral"));
    assertEquals(false, store.status(OTHER).get("canTrainGeneral"));
    Map<String, Object> report =
        new GeneralTraining(store, JSON)
            .run(
                OWNER, 1, (id, options) -> ReinforcementTest.district(), () -> false, (p, t) -> {});
    assertEquals(2L, report.get("updates"));
    assertEquals(8L, report.get("trainingEpisodes"));
    assertEquals(2, ((List<?>) report.get("comparisons")).size());
    assertTrue(Set.of("PROMOTED", "RETAINED_BASELINE").contains(report.get("status")));
    String before = store.generalPolicy(OWNER).sha256;
    var cancelled =
        new GeneralTraining(store, JSON)
            .run(OWNER, 1, (id, options) -> ReinforcementTest.district(), () -> true, (p, t) -> {});
    assertEquals("CANCELLED", cancelled.get("status"));
    assertEquals(before, store.generalPolicy(OWNER).sha256);
  }
}
