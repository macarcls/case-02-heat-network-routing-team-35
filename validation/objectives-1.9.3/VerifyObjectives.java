package ru.teplotrassa.experiment;

import java.nio.file.*;
import java.util.*;
import com.fasterxml.jackson.databind.node.*;
import ru.teplotrassa.data.*;
import ru.teplotrassa.engine.*;

/** Offline reproduction on the 17-entry supplied territory. */
public final class VerifyObjectives {
  public static void main(String[] args) throws Exception {
    Path root = Path.of(args[0]), output = Path.of(args[1]);
    Files.createDirectories(output);
    NeuralExperiment.Store raw = new NeuralExperiment.Store();
    new GeoJsonInput(NeuralExperiment.JSON).read(root.resolve("examples/supplied.geojson"), raw::add, false);
    Planner.Options o = new Planner.Options();
    o.routingStrategy = "tree";
    o.treeSingleRootRequired = true;
    o.neuralGuidance = "off";
    o.mode = "depth";
    o.dataMode = "scenario";
    o.gridM = 20;
    o.existingLoadPercent = 50d;
    o.treeGroupRepair = false;
    FeatureStore store = InputData.scenario(raw, o);
    Network checkedSeed = NetworkSnapshot.read(NeuralExperiment.JSON.readTree(
        root.resolve("validation/geometry-final/checkpoint.json").toFile()));
    Planner.Result r = new Planner(store, o, () -> false,
        (p, message) -> System.err.println(p + "% " + message))
        .withExperience(List.of(checkedSeed), (state, saved) -> {}).calculate();
    Map<String, Object> report = new LinkedHashMap<>();
    report.put("variants", r.variants.stream().map(v -> v.summary).toArray());
    report.put("checks", r.variants.stream().map(v -> v.checks).toArray());
    report.put("search", r.search);
    report.put("elapsedMs", r.elapsedMs);
    report.put("options", o);
    report.put("scenarioChanges", InputData.scenarioChanges(store));
    NeuralExperiment.write(output.resolve("supplied-objectives-report.json"), report);
    if (!r.variants.isEmpty()) {
      r.metadata.put("rules_version", Rules.VERSION);
      GeoJsonOutput.write(output.resolve("supplied-objectives.geojson"), r, store, NeuralExperiment.JSON);
      ArrayNode saved = NeuralExperiment.JSON.createArrayNode();
      for (Evaluation v : r.variants) saved.add(NetworkSnapshot.write(v.network, NeuralExperiment.JSON));
      NeuralExperiment.JSON.writeValue(output.resolve("selected-networks.json").toFile(), saved);
    }
    System.out.println("Variants: " + r.variants.size() + "; elapsedMs: " + r.elapsedMs);
    for (Evaluation v : r.variants)
      System.out.println(v.summary.get("variant_role") + " score=" + v.score
          + " earth=" + v.summary.get("earthwork_index")
          + " installation=" + v.summary.get("installation_index")
          + " length=" + v.summary.get("new_network_length"));
  }
}
