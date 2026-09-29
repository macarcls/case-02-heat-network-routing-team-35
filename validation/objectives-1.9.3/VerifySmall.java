package ru.teplotrassa.experiment;

import java.nio.file.*;
import ru.teplotrassa.data.*;
import ru.teplotrassa.engine.*;

/** Short integration run with the bundled three-entry example. */
public final class VerifySmall {
  public static void main(String[] args) throws Exception {
    Path project = Path.of(args[0]);
    NeuralExperiment.Store store = new NeuralExperiment.Store();
    new GeoJsonInput(NeuralExperiment.JSON).read(project.resolve("examples/complete.geojson"), store::add);
    InputValidator.validate(store);
    Planner.Options options = new Planner.Options();
    options.routingStrategy = "tree";
    options.mode = "depth";
    options.neuralGuidance = "off";
    options.gridM = 20;
    options.treeBeamWidth = 2;
    options.treeExpansion = 2;
    options.treeRepairPasses = 1;
    options.treeRepairCandidates = 3;
    options.treeGroupRepair = false;
    Planner.Result result = new Planner(store, options, () -> false, (pct, msg) ->
        System.err.println(pct + "% " + msg)).calculate();
    if (result.variants.isEmpty()) throw new AssertionError("No complete variant");
    for (Evaluation candidate : result.variants) {
      if (candidate.network.connected.size() != 3 || candidate.score !=
          ((Number) candidate.summary.get("balanced_score")).doubleValue())
        throw new AssertionError("Incomplete or incomparable variant: " + candidate.summary);
      System.out.println(candidate.summary.get("variant_role") + " score=" + candidate.score
          + " earth=" + candidate.summary.get("earthwork_index")
          + " laying=" + candidate.summary.get("installation_index"));
    }
    System.out.println("Search status: " + result.search.get("status"));
  }
}
