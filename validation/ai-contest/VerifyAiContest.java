package ru.teplotrassa.experiment;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
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
import ru.teplotrassa.engine.Network;
import ru.teplotrassa.engine.NetworkSnapshot;
import ru.teplotrassa.engine.Planner;
import ru.teplotrassa.engine.Rules;

/** Reproducible 17-entry check on a supplied GeoJSON; never loads the rival's code. */
public final class VerifyAiContest {
  public static void main(String[] args) throws Exception {
    if (args.length < 3) throw new IllegalArgumentException(
        "Expected: project root, input GeoJSON, output directory [saved networks]");
    Path root = Path.of(args[0]), input = Path.of(args[1]), out = Path.of(args[2]);
    Files.createDirectories(out);
    NeuralExperiment.Store raw = new NeuralExperiment.Store();
    new GeoJsonInput(NeuralExperiment.JSON).read(input, raw::add, false);
    Planner.Options options = new Planner.Options();
    options.routingStrategy = "tree";
    options.treeSingleRootRequired = true;
    options.neuralGuidance = "off";
    options.rankingProfile = "contest";
    options.dataMode = "scenario";
    options.mode = "plan";
    options.gridM = 20;
    options.existingLoadPercent = 50d;
    options.treeGroupRepair = false;
    FeatureStore store = InputData.scenario(raw, options);
    List<Network> seeds = new ArrayList<>();
    if (args.length > 3)
      for (JsonNode node : NeuralExperiment.JSON.readTree(Path.of(args[3]).toFile()))
        seeds.add(NetworkSnapshot.read(node));
    long[] lastUpdate = {0};
    Planner planner = new Planner(store, options, () -> false, (p, message) -> {
      long now = System.nanoTime();
      if (now - lastUpdate[0] > 25_000_000_000L) {
        System.err.println(p + "% " + message);
        lastUpdate[0] = now;
      }
    });
    if (!seeds.isEmpty()) planner.withExperience(seeds, (state, saved) -> {});
    Planner.Result result = planner.calculate();
    Map<String, Object> report = new LinkedHashMap<>();
    report.put("input", input.toString());
    report.put("rulesVersion", Rules.VERSION);
    report.put("elapsedMs", result.elapsedMs);
    report.put("variants", result.variants.stream().map(v -> v.summary).toArray());
    report.put("checks", result.variants.stream().map(v -> v.checks).toArray());
    report.put("search", result.search);
    report.put("options", options);
    NeuralExperiment.write(out.resolve("benchmark-report.json"), report);
    result.metadata.put("rules_version", Rules.VERSION);
    if (!result.variants.isEmpty()) {
      GeoJsonOutput.write(out.resolve("benchmark-result.geojson"), result, store,
          NeuralExperiment.JSON);
      ArrayNode snapshots = NeuralExperiment.JSON.createArrayNode();
      for (Evaluation variant : result.variants)
        snapshots.add(NetworkSnapshot.write(variant.network, NeuralExperiment.JSON));
      NeuralExperiment.JSON.writeValue(out.resolve("benchmark-networks.json").toFile(), snapshots);
    }
    System.out.println("full=" + result.variants.size() + " elapsedMs=" + result.elapsedMs);
    for (Evaluation v : result.variants)
      System.out.printf("%s 17=%s S=%.4f C=%.0f L=%.1f%n", v.summary.get("variant_role"),
          v.summary.get("connected_connection_count"), v.score,
          v.summary.get("contest_cost"), v.summary.get("contest_length"));
  }
}
