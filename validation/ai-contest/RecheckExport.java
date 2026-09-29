package ru.teplotrassa.experiment;

import com.fasterxml.jackson.databind.JsonNode;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import ru.teplotrassa.data.FeatureStore;
import ru.teplotrassa.data.GeoJsonInput;
import ru.teplotrassa.engine.Evaluation;
import ru.teplotrassa.engine.GeoJsonOutput;
import ru.teplotrassa.engine.InputData;
import ru.teplotrassa.engine.NetworkSnapshot;
import ru.teplotrassa.engine.Planner;
import ru.teplotrassa.engine.Rules;

/** Verify saved complete networks using the current code and regenerate the contest GeoJSON. */
public final class RecheckExport {
  public static void main(String[] args) throws Exception {
    if (args.length != 3 && (args.length != 4 || !args[3].equals("depth")))
      throw new IllegalArgumentException(
          "Expected: earlier benchmark directory, input GeoJSON, verified output directory [depth]");
    Path before = Path.of(args[0]), input = Path.of(args[1]), output = Path.of(args[2]);
    Files.createDirectories(output);
    JsonNode prior = NeuralExperiment.JSON.readTree(before.resolve("benchmark-report.json").toFile());
    JsonNode snapshots = NeuralExperiment.JSON.readTree(before.resolve("benchmark-networks.json").toFile());
    Planner.Options options = NeuralExperiment.JSON.treeToValue(prior.path("options"), Planner.Options.class);
    if (args.length == 4) options.mode = "depth";
    NeuralExperiment.Store raw = new NeuralExperiment.Store();
    new GeoJsonInput(NeuralExperiment.JSON).read(input, raw::add, false);
    FeatureStore store = InputData.scenario(raw, options);
    Planner verifier = new Planner(store, options, () -> false, (p, m) -> {});
    Planner.Result result = new Planner.Result();
    List<Map<String, Object>> summaries = new ArrayList<>();
    List<Map<String, Object>> checks = new ArrayList<>();
    for (int i = 0; i < snapshots.size(); i++) {
      Evaluation candidate = verifier.validateIncumbent(NetworkSnapshot.read(snapshots.get(i)));
      if (!Boolean.TRUE.equals(candidate.checks.get("allConnectionsConnected")))
        throw new IllegalStateException("A stored candidate is no longer complete");
      JsonNode previous = prior.path("variants").get(i);
      if (args.length == 3
          && Math.abs(candidate.score - previous.path("contest_score_raw").asDouble()) > 1e-6)
        throw new IllegalStateException("Candidate score changed during revalidation");
      candidate.summary.put("variant_role", previous.path("variant_role").asText());
      candidate.summary.put("variant_name", previous.path("variant_name").asText());
      candidate.summary.put("rank", i + 1);
      result.variants.add(candidate);
      summaries.add(candidate.summary);
      checks.add(candidate.checks);
    }
    List<Evaluation> order = new ArrayList<>(result.variants);
    order.sort(java.util.Comparator.comparingDouble(v -> v.score));
    for (Evaluation candidate : result.variants)
      candidate.summary.put("rank", order.indexOf(candidate) + 1);
    result.metadata.put("rules_version", Rules.VERSION);
    result.metadata.put("ranking_profile", options.rankingProfile);
    GeoJsonOutput.write(output.resolve("benchmark-result.geojson"), result, store,
        NeuralExperiment.JSON);
    Map<String, Object> report = new LinkedHashMap<>();
    report.put("input", input.toString());
    report.put("rulesVersion", Rules.VERSION);
    report.put("elapsedMs", prior.path("elapsedMs").asLong());
    report.put("variants", summaries);
    report.put("checks", checks);
    report.put("search", prior.path("search"));
    report.put("options", options);
    NeuralExperiment.write(output.resolve("benchmark-report.json"), report);
    System.out.println("Revalidated and exported " + summaries.size() + " full networks");
  }
}
