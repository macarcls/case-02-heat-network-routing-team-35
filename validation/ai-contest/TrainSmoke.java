package ru.teplotrassa.experiment;

import java.util.Map;
import ru.teplotrassa.data.FeatureStore;
import ru.teplotrassa.engine.InputData;
import ru.teplotrassa.engine.Planner;

/** Real search and guarded on-map training on a small reproducible territory. */
public final class TrainSmoke {
  public static void main(String[] args) {
    Planner.Options options = new Planner.Options();
    options.routingStrategy = "tree";
    options.rankingProfile = "contest";
    options.treeSingleRootRequired = true;
    options.treeLearning = true;
    options.treeLearningRounds = 2;
    options.treeTrainingSteps = 2;
    options.treeBeamWidth = 2;
    options.treeExpansion = 1;
    options.treeRepairPasses = 0;
    options.treeGeometricSearch = false;
    options.treeJunctionRepair = false;
    options.treeGroupRepair = false;
    options.treeResumeFromBest = false;
    options.dataMode = "scenario";
    options.existingLoadPercent = 50d;
    options.gridM = 20;
    options.variantLimit = 1;
    FeatureStore store = InputData.scenario(NeuralExperiment.territory(1), options);
    Planner.Result result = new Planner(store, options, () -> false, (p, m) -> {}).calculate();
    if (result.variants.isEmpty()) throw new AssertionError("No complete route after learning");
    Map<?, ?> learning = (Map<?, ?>) result.search.get("treeLearning");
    if (learning == null
        || !"same_state_full_network_pairwise_preferences".equals(learning.get("method")))
      throw new AssertionError("Guarded pairwise learning was not run");
    double raw = ((Number) result.variants.get(0).summary.get("contest_score_raw")).doubleValue();
    if (Math.abs(raw - result.variants.get(0).score) > 1e-8)
      throw new AssertionError("AI decisions and accepted network use a different score");
    System.out.printf("AI search: complete=%d S=%.4f acceptedModels=%s rejected=%s%n",
        result.variants.size(), raw,
        learning.get("acceptedModelsThisRun"), learning.get("rejectedModelsThisRun"));
  }
}
