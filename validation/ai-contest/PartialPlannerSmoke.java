package ru.teplotrassa.experiment;

import java.util.List;
import ru.teplotrassa.data.FeatureStore;
import ru.teplotrassa.engine.*;

/** The public planner must export its best checked partial network when full routing fails. */
public final class PartialPlannerSmoke {
  public static void main(String[] ignored) throws Exception {
    NeuralExperiment.Store raw = new NeuralExperiment.Store();
    raw.add(NeuralExperiment.feature("source", "source", Geo.point(NeuralExperiment.p(0, -20))));
    raw.add(NeuralExperiment.feature("old", "heat_network",
        Geo.line(NeuralExperiment.p(0, -20), NeuralExperiment.p(0, 20)), "diameter", 100));
    raw.add(NeuralExperiment.feature("camera", "heat_chamber", Geo.point(NeuralExperiment.p(0, 20))));
    raw.add(NeuralExperiment.feature("one", "oks_connection_point",
        Geo.point(NeuralExperiment.p(60, 20)), "flow_tph", 2));
    raw.add(NeuralExperiment.feature("two", "oks_connection_point",
        Geo.point(NeuralExperiment.p(100, -20)), "flow_tph", 3));
    raw.add(NeuralExperiment.feature("island", "restriction",
        NeuralExperiment.box(95, -25, 10, 10), "restriction_type", "park"));
    Planner.Options options = new Planner.Options();
    options.routingStrategy = "classic";
    options.neuralGuidance = "off";
    options.variantLimit = 1;
    options.candidateLimit = 2;
    options.gridM = 20;
    FeatureStore store = InputData.scenario(raw, options);
    Planner.Result result = new Planner(store, options, () -> false, (p, m) -> {}).calculate();
    if (!"PARTIAL_SOLUTION".equals(result.search.get("status"))
        || result.variants.size() != 1
        || result.variants.get(0).network.connected.size() != 1
        || !result.variants.get(0).summary.get("unconnected_oks_ids").equals(java.util.Set.of("two")))
      throw new AssertionError("Partial result or penalty missing: " + result.search);
    System.out.println("planner partial variant and missing target: OK");
  }
}
