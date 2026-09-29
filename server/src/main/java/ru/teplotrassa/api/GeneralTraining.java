package ru.teplotrassa.api;

import com.fasterxml.jackson.databind.*;
import java.io.*;
import java.util.*;
import java.util.function.*;
import ru.teplotrassa.data.*;
import ru.teplotrassa.engine.*;

/** Fresh rollouts on stored territories, with map-level validation before promotion. */
public final class GeneralTraining {
  public interface Loader {
    FeatureStore load(UUID id, Planner.Options options) throws IOException;
  }

  private final ExperienceStore experience;
  private final ObjectMapper json;

  public GeneralTraining(ExperienceStore experience, ObjectMapper json) {
    this.experience = experience;
    this.json = json;
  }

  public Map<String, Object> run(
      String owner,
      int passes,
      Loader loader,
      BooleanSupplier cancelled,
      BiConsumer<Integer, String> progress)
      throws IOException {
    if (passes < 1 || passes > 20) throw new IllegalArgumentException("Число проходов: 1–20");
    Map<String, JsonNode> unique = new TreeMap<>();
    for (JsonNode n : experience.territories(owner))
      unique.putIfAbsent(n.path("inputSha256").asText(), n);
    List<JsonNode> train = new ArrayList<>(), validation = new ArrayList<>();
    for (JsonNode n : unique.values())
      (n.path("split").asText().equals("train") ? train : validation).add(n);
    if (train.size() < 2 || validation.size() < 2)
      throw new IllegalArgumentException(
          "Для общей модели нужны минимум 2 обучающих и 2 проверочных участка. Группа закрепляется"
              + " по SHA-256 файла; пока продолжайте расчёты на разных участках.");
    long start = System.nanoTime();
    ReinforcementPolicy baseline = experience.generalPolicy(owner);
    PolicyTraining candidate = new PolicyTraining(baseline);
    Map<String, Object> report = new LinkedHashMap<>();
    report.put("kind", "general_training");
    report.put("baselineModelSha256", baseline.sha256);
    report.put("passes", passes);
    report.put("freshOnPolicyEpisodes", true);
    report.put(
        "trainInputs",
        train.stream().map(n -> n.path("inputSha256").asText()).toArray(String[]::new));
    report.put(
        "validationInputs",
        validation.stream().map(n -> n.path("inputSha256").asText()).toArray(String[]::new));
    report.put("validationEpisodesPerModelAndMap", 3);
    report.put("validationSeed", 10271);
    List<Map<String, Object>> comparisons = new ArrayList<>();
    int completed = 0, total = train.size() * passes + validation.size() * 2;
    try {
      for (int pass = 0; pass < passes; pass++)
        for (JsonNode territory : train) {
          check(cancelled);
          final int step = completed;
          Planner.Options o = options(territory);
          o.rlLearning = true;
          o.rlEpisodes = 5;
          o.rlBatchSize = 4;
          o.rlTemperature = .7;
          o.rlLearningRate = .003;
          o.rlSeed = 71;
          new Planner(
                  loader.load(UUID.fromString(territory.path("dataset").asText()), o),
                  o,
                  cancelled,
                  (p, t) ->
                      progress.accept(
                          step * 95 / total,
                          "Общая модель: обучение "
                              + (step + 1)
                              + "/"
                              + (train.size() * passes)
                              + ". "
                              + t))
              .withLearning(candidate)
              .calculate();
          check(cancelled);
          completed++;
        }
      for (JsonNode territory : validation) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("inputSha256", territory.path("inputSha256").asText());
        for (boolean isCandidate : new boolean[] {false, true}) {
          check(cancelled);
          final int step = completed;
          Planner.Options o = options(territory);
          o.rlLearning = false;
          o.rlEpisodes = 3;
          o.rlSeed = 10271;
          o.rlTemperature = .7;
          Planner.Result r =
              new Planner(
                      loader.load(UUID.fromString(territory.path("dataset").asText()), o),
                      o,
                      cancelled,
                      (p, t) ->
                          progress.accept(
                              step * 95 / total,
                              "Общая модель: проверка "
                                  + (isCandidate ? "новых" : "прежних")
                                  + " весов. "
                                  + t))
                  .withReinforcementPolicy(isCandidate ? candidate.policy() : baseline)
                  .calculate();
          check(cancelled);
          completed++;
          String prefix = isCandidate ? "candidate" : "baseline";
          row.put(prefix + "Complete", !r.variants.isEmpty());
          int required = ((Number) r.search.get("requiredConnections")).intValue();
          int connected = ((Number) r.search.get("bestConnectedCount")).intValue();
          double reward =
              r.variants.isEmpty()
                  ? -2 - (required - connected) / (double) Math.max(1, required)
                  : Planner.rlReward(r.variants.get(0), required);
          row.put(prefix + "Reward", reward);
          row.put(prefix + "Score", r.variants.isEmpty() ? null : r.variants.get(0).score);
          row.put(prefix + "ElapsedMs", r.elapsedMs);
        }
        comparisons.add(row);
      }
      check(cancelled);
      boolean promote = passesGate(comparisons);
      report.put("status", promote ? "PROMOTED" : "RETAINED_BASELINE");
      report.put(
          "message",
          promote
              ? "Общая модель обновлена: улучшение на проверочных участках без ухудшения остальных."
              : "Прежняя общая модель сохранена: новая не прошла проверку улучшения без"
                    + " ухудшений.");
      report.put("candidateModelSha256", candidate.policy().sha256);
      report.put("updates", candidate.updates);
      report.put("trainingEpisodes", candidate.episodes);
      report.put("comparisons", comparisons);
      report.put("elapsedMs", (System.nanoTime() - start) / 1_000_000);
      if (promote) experience.promote(owner, candidate.policy(), json.valueToTree(report));
    } catch (java.util.concurrent.CancellationException e) {
      report.put("status", "CANCELLED");
      report.put("message", "Обучение отменено; общая модель не изменена.");
      report.put("comparisons", comparisons);
    }
    experience.generalReport(owner, json.valueToTree(report));
    return report;
  }

  public static boolean passesGate(List<Map<String, Object>> rows) {
    if (rows.size() < 2) return false;
    boolean improved = false;
    for (Map<String, Object> row : rows) {
      double before = ((Number) row.get("baselineReward")).doubleValue();
      double after = ((Number) row.get("candidateReward")).doubleValue();
      if (!Double.isFinite(before)
          || !Double.isFinite(after)
          || after + 1e-10 < before
          || Boolean.TRUE.equals(row.get("baselineComplete"))
              && !Boolean.TRUE.equals(row.get("candidateComplete"))) return false;
      improved |= after > before + 1e-9;
    }
    return improved;
  }

  private Planner.Options options(JsonNode n) throws IOException {
    Planner.Options o = json.treeToValue(n.path("options"), Planner.Options.class);
    o.routingStrategy = "reinforcement";
    o.rlRemember = false;
    o.variantLimit = 1;
    return o;
  }

  private static void check(BooleanSupplier cancelled) {
    if (cancelled.getAsBoolean() || Thread.currentThread().isInterrupted())
      throw new java.util.concurrent.CancellationException();
  }
}
