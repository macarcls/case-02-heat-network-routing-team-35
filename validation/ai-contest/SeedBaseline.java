package ru.teplotrassa.experiment;

import com.fasterxml.jackson.databind.JsonNode;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.Map;
import ru.teplotrassa.data.FeatureStore;
import ru.teplotrassa.data.GeoJsonInput;
import ru.teplotrassa.engine.Evaluation;
import ru.teplotrassa.engine.GeoJsonOutput;
import ru.teplotrassa.engine.InputData;
import ru.teplotrassa.engine.NetworkSnapshot;
import ru.teplotrassa.engine.Planner;
import ru.teplotrassa.engine.Rules;

/** Baseline on a new map: recheck older routes, export the least expensive full network. */
public final class SeedBaseline {
  public static void main(String[] args) throws Exception {
    Path input = Path.of(args[0]), seeds = Path.of(args[1]), out = Path.of(args[2]);
    Files.createDirectories(out);
    NeuralExperiment.Store raw = new NeuralExperiment.Store();
    new GeoJsonInput(NeuralExperiment.JSON).read(input, raw::add, false);
    Planner.Options options = new Planner.Options();
    options.routingStrategy = "tree";
    options.treeSingleRootRequired = true;
    options.dataMode = "scenario";
    options.existingLoadPercent = 50d;
    options.mode = "plan";
    FeatureStore store = InputData.scenario(raw, options);
    Planner verifier = new Planner(store, options, () -> false, (p, m) -> {});
    Planner.Result result = new Planner.Result();
    for (JsonNode n : NeuralExperiment.JSON.readTree(seeds.toFile()))
      try {
        Evaluation checked = verifier.validateIncumbent(NetworkSnapshot.read(n));
        if (Boolean.TRUE.equals(checked.checks.get("allConnectionsConnected")))
          result.variants.add(checked);
      } catch (IllegalArgumentException invalid) {
        System.err.println("Prior route rejected: " + invalid.getMessage());
      }
    result.variants.sort(Comparator.comparingDouble(v -> v.score));
    if (result.variants.isEmpty()) throw new AssertionError("No valid baseline route");
    Evaluation best = result.variants.get(0);
    result.variants.clear();
    result.variants.add(best);
    best.summary.put("variant_role", "balanced");
    best.summary.put("variant_name", "Лучший из повторно проверенных прежних маршрутов");
    best.summary.put("rank", 1);
    result.metadata.put("rules_version", Rules.VERSION);
    result.metadata.put("ranking_profile", "contest");
    GeoJsonOutput.write(out.resolve("benchmark-result.geojson"), result, store,
        NeuralExperiment.JSON);
    Map<String, Object> report = new LinkedHashMap<>();
    report.put("input", input.toString());
    report.put("rulesVersion", Rules.VERSION);
    report.put("variants", new Object[] {best.summary});
    report.put("checks", new Object[] {best.checks});
    report.put("options", options);
    report.put("description", "Prior networks revalidated: baseline only, no new search");
    NeuralExperiment.write(out.resolve("benchmark-report.json"), report);
    System.out.printf("Validated baseline S=%.4f C=%.0f L=%.1f%n", best.score,
        best.summary.get("contest_cost"), best.summary.get("contest_length"));
  }
}
