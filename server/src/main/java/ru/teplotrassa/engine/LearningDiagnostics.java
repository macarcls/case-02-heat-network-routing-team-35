package ru.teplotrassa.engine;

import java.util.*;

/** Observations only: these metrics never influence actions, rewards or saved weights. */
public final class LearningDiagnostics {
  private LearningDiagnostics() {}

  public static Map<String, Object> weights(ReinforcementPolicy before, ReinforcementPolicy after) {
    double[] a = new PolicyTraining(before).parameters();
    double[] b = new PolicyTraining(after).parameters();
    if (a.length != b.length) throw new IllegalArgumentException("Model dimensions changed");
    double norm = 0, maximum = 0;
    int changed = 0;
    for (int i = 0; i < a.length; i++) {
      double delta = Math.abs(b[i] - a[i]);
      if (delta > 0) changed++;
      norm = Math.hypot(norm, delta);
      maximum = Math.max(maximum, delta);
    }
    return Map.of(
        "weightsChanged", changed > 0,
        "changedWeightCount", changed,
        "weightCount", a.length,
        "weightDeltaL2", norm,
        "maximumWeightDelta", maximum);
  }

  /** Compare finalized full networks, including the revalidated incumbent at episode zero. */
  public static Map<String, Object> quality(
      Double before, List<Map<String, Object>> attempts, Double after) {
    Double first = null, fresh = null, best = before;
    Integer firstAttempt = null;
    int full = 0, lastImprovement = 0;
    for (Map<String, Object> attempt : attempts) {
      int episode = ((Number) attempt.get("attempt")).intValue();
      Object value = attempt.get("scoreAfterSmoothing");
      if (Boolean.TRUE.equals(attempt.get("complete")) && value instanceof Number) {
        double score = ((Number) value).doubleValue();
        if (Double.isFinite(score)) {
          full++;
          if (first == null) {
            first = score;
            firstAttempt = episode;
          }
          fresh = fresh == null ? score : Math.min(fresh, score);
          if (best == null || score < best - 1e-9) lastImprovement = episode;
          best = best == null ? score : Math.min(best, score);
        }
      }
      attempt.put("bestScoreSoFar", best);
    }
    Double baseline = before != null ? before : first;
    Map<String, Object> result = new LinkedHashMap<>();
    result.put("scoreDirection", "lower_is_better");
    result.put("bestBeforeRun", before);
    result.put("firstCompleteScore", first);
    result.put("firstCompleteAttempt", firstAttempt);
    result.put("bestFreshScore", fresh);
    result.put("bestAfterRun", after);
    result.put("baselineScore", baseline);
    result.put(
        "baselineSource",
        before != null ? "saved_network" : first != null ? "first_complete_episode" : "none");
    result.put(
        "improvementPercent",
        baseline != null && baseline > 0 && after != null
            ? 100 * (baseline - after) / baseline
            : null);
    result.put("improved", baseline != null && after != null && after < baseline - 1e-9);
    result.put("completeEpisodes", full);
    result.put("completedEpisodes", attempts.size());
    result.put(
        "episodesSinceLastImprovement", best == null ? null : attempts.size() - lastImprovement);
    return result;
  }
}
