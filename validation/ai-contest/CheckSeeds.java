package ru.teplotrassa.experiment;

import com.fasterxml.jackson.databind.JsonNode;
import java.nio.file.Path;
import ru.teplotrassa.data.FeatureStore;
import ru.teplotrassa.data.GeoJsonInput;
import ru.teplotrassa.engine.Evaluation;
import ru.teplotrassa.engine.InputData;
import ru.teplotrassa.engine.NetworkSnapshot;
import ru.teplotrassa.engine.Planner;

/** Check old full-network snapshots against a newly supplied territory without any search. */
public final class CheckSeeds {
  public static void main(String[] args) throws Exception {
    NeuralExperiment.Store raw = new NeuralExperiment.Store();
    new GeoJsonInput(NeuralExperiment.JSON).read(Path.of(args[0]), raw::add, false);
    Planner.Options options = new Planner.Options();
    options.routingStrategy = "tree";
    options.treeSingleRootRequired = true;
    options.rankingProfile = "contest";
    options.dataMode = "scenario";
    options.existingLoadPercent = 50d;
    options.mode = "plan";
    FeatureStore store = InputData.scenario(raw, options);
    Planner planner = new Planner(store, options, () -> false, (p, m) -> {});
    int count = 0;
    for (JsonNode n : NeuralExperiment.JSON.readTree(Path.of(args[1]).toFile())) {
      try {
        Evaluation v = planner.validateIncumbent(NetworkSnapshot.read(n));
        count++;
        System.out.printf("seed %d: valid 17/17 S=%.4f C=%.0f L=%.1f%n", count, v.score,
            v.summary.get("contest_cost"), v.summary.get("contest_length"));
      } catch (IllegalArgumentException e) {
        System.out.println("seed invalid: " + e.getMessage());
      }
    }
    System.out.println("valid seeds=" + count);
  }
}
