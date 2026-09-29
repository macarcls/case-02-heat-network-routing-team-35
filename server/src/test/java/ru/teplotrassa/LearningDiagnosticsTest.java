package ru.teplotrassa;

import static org.junit.jupiter.api.Assertions.*;

import java.util.*;
import org.junit.jupiter.api.Test;
import ru.teplotrassa.engine.*;

class LearningDiagnosticsTest {
  @Test
  void renamedModelIsNotReportedAsChangedWeights() throws Exception {
    ReinforcementPolicy base = ReinforcementPolicy.bundled();
    double[] weights = new PolicyTraining(base).parameters();
    ReinforcementPolicy renamed = PolicyTraining.withParameters(base, weights, "new-record-only");
    assertNotEquals(base.sha256, renamed.sha256);
    var unchanged = LearningDiagnostics.weights(base, renamed);
    assertEquals(false, unchanged.get("weightsChanged"));
    assertEquals(0, unchanged.get("changedWeightCount"));
    assertEquals(0d, unchanged.get("weightDeltaL2"));
    weights[0] += .01;
    weights[1] -= .02;
    var changed =
        LearningDiagnostics.weights(base, PolicyTraining.withParameters(base, weights, "changed"));
    assertEquals(true, changed.get("weightsChanged"));
    assertEquals(2, changed.get("changedWeightCount"));
    assertEquals(Math.hypot(.01, .02), (double) changed.get("weightDeltaL2"), 1e-12);
  }

  static Map<String, Object> attempt(int n, boolean full, double score) {
    Map<String, Object> result = new LinkedHashMap<>();
    result.put("attempt", n);
    result.put("complete", full);
    result.put("scoreAfterSmoothing", score);
    return result;
  }

  @Test
  void improvementUsesSavedAbsoluteScoreAndIgnoresIncompleteNetworks() {
    var attempts =
        List.of(
            attempt(1, true, 30), attempt(2, false, 1), attempt(3, true, 24), attempt(4, true, 25));
    var q = LearningDiagnostics.quality(26d, attempts, 24d);
    assertEquals("saved_network", q.get("baselineSource"));
    assertEquals(26d, q.get("baselineScore"));
    assertEquals(24d, q.get("bestFreshScore"));
    assertEquals(3, q.get("completeEpisodes"));
    assertEquals(1, q.get("episodesSinceLastImprovement"));
    assertEquals(true, q.get("improved"));
    assertEquals(100 * 2d / 26, (double) q.get("improvementPercent"), 1e-10);
    assertEquals(26d, attempts.get(1).get("bestScoreSoFar"));
    assertEquals(24d, attempts.get(3).get("bestScoreSoFar"));
  }

  @Test
  void unchangedBestIsDistinctFromNewSamplesAndWeightUpdates() {
    var q =
        LearningDiagnostics.quality(20d, List.of(attempt(1, true, 25), attempt(2, true, 21)), 20d);
    assertEquals(false, q.get("improved"));
    assertEquals(0d, q.get("improvementPercent"));
    assertEquals(21d, q.get("bestFreshScore"));
    assertEquals(2, q.get("episodesSinceLastImprovement"));
  }

  @Test
  void newAndUnsolvedTerritoriesHaveNoInventedPreviousBest() {
    var first =
        LearningDiagnostics.quality(
            null, List.of(attempt(1, false, 1), attempt(2, true, 25), attempt(3, true, 20)), 20d);
    assertNull(first.get("bestBeforeRun"));
    assertEquals("first_complete_episode", first.get("baselineSource"));
    assertEquals(2, first.get("firstCompleteAttempt"));
    assertEquals(20d, first.get("improvementPercent"));
    var none = LearningDiagnostics.quality(null, List.of(attempt(1, false, 1)), null);
    assertNull(none.get("baselineScore"));
    assertNull(none.get("improvementPercent"));
    assertNull(none.get("episodesSinceLastImprovement"));
    var zero = LearningDiagnostics.quality(0d, List.of(attempt(1, true, 0)), 0d);
    assertNull(zero.get("improvementPercent"));
    assertEquals(false, zero.get("improved"));
  }
}
