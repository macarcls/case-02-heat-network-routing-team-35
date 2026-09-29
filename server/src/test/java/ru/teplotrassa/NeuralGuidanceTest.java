package ru.teplotrassa;

import static org.junit.jupiter.api.Assertions.*;
import static ru.teplotrassa.EngineTest.*;

import com.fasterxml.jackson.databind.node.*;
import java.nio.file.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import ru.teplotrassa.data.*;
import ru.teplotrassa.engine.*;

class NeuralGuidanceTest {
  @Test
  void experimentalModelDoesNotReplaceTheReleasedDefaultWithoutAQualityGain() {
    Planner.Options options = new Planner.Options();
    assertEquals("baseline", options.neuralGuidance);
    options.variantLimit = 1;
    Planner.Result result = new Planner(basic(), options, () -> false, (p, t) -> {}).calculate();
    var guidance = JSON.valueToTree(result.search).path("neuralGuidance");
    assertEquals("baseline", guidance.path("effectivePolicy").asText());
    assertEquals(0, guidance.path("neuralPredictions").asLong());
    assertEquals(0, guidance.path("prunedByAnalyticalBound").asLong());
    assertEquals(1, result.search.get("bestConnectedCount"));
  }

  @Test
  void exportedModelMatchesSklearnOnUnseenTerritories() throws Exception {
    NeuralRanker model = NeuralRanker.bundled();
    assertTrue(model.available(), model.unavailableReason);
    var exported =
        JSON.readTree(Path.of("src/main/resources/models/connection-ranker.json").toFile());
    for (var scale : exported.path("scale")) assertTrue(scale.asDouble() >= 1e-6);
    int n = 0;
    for (var item : JSON.readTree(Path.of("../tools/neural/inference-fixtures.json").toFile())) {
      double[] features = JSON.convertValue(item.path("features"), double[].class);
      assertEquals(item.path("prediction").asDouble(), model.predictLogResidual(features), 1e-9);
      double before = model.predictLogResidual(features);
      features[10] += 1e-10; // round-off in wall distance must not become a signal
      assertEquals(before, model.predictLogResidual(features), 1e-6);
      n++;
    }
    assertTrue(n >= 20);
    assertEquals(64, model.sha256.length());
  }

  @Test
  void missingMalformedAndIncompatibleModelsFallBackToAnAnalyticalPriority() throws Exception {
    byte[] valid = Files.readAllBytes(Path.of("src/main/resources/models/connection-ranker.json"));
    ObjectNode root = (ObjectNode) JSON.readTree(valid);
    ((ArrayNode) root.get("scale")).set(0, DoubleNode.valueOf(-1));
    ObjectNode incompatible = (ObjectNode) JSON.readTree(valid);
    incompatible.put("target", "unknown-target");
    for (byte[] broken :
        new byte[][] {
          null,
          new byte[] {0, 1, 2},
          JSON.writeValueAsBytes(root),
          JSON.writeValueAsBytes(incompatible),
          new byte[2_000_001]
        }) {
      NeuralRanker model = NeuralRanker.fromBytesOrFallback(broken);
      assertFalse(model.available());
      assertFalse(model.unavailableReason.isBlank());
      assertEquals(4.2, model.priority(4.2, new double[CandidateFeatures.NAMES.size()]));
    }
    double[] bad = new double[CandidateFeatures.NAMES.size()];
    bad[0] = Double.NaN;
    assertEquals(4.2, NeuralRanker.bundled().priority(4.2, bad));
    assertEquals(4.2, NeuralRanker.bundled().priority(4.2, null));
  }

  @Test
  void policiesPreserveGeometryScoresAndEveryEntryWithSharedBranchesAndObstacles() {
    Store store =
        basic()
            .add(
                feature("b", "oks_future", box(130, 155, 30, 25), "flow_tph", 30, "heat_load", 1.5))
            .add(feature("entry_b", "oks_connection_point", Geo.point(c(131, 167)), "oks_id", "b"))
            .add(feature("obstacle", "oks_existing", box(40, 110, 30, 30)))
            .add(feature("road", "restriction", box(15, 10, 9, 180), "restriction_type", "road"));
    List<String> expected = null;
    double score = 0;
    int labels = 0;
    for (String profile : List.of("appendix", "protocol")) {
      expected = null;
      for (String policy : List.of("off", "bounds", "on")) {
        Planner.Options options = new Planner.Options();
        options.neuralGuidance = policy;
        options.mode = "depth";
        options.rankingProfile = profile;
        options.variantLimit = 1;
        options.candidateLimit = 4;
        options.gridM = 10;
        List<Map<String, Object>> observed = new ArrayList<>();
        Planner planner = new Planner(store, options, () -> false, (p, t) -> {});
        if (policy.equals("off")) planner.observeCandidates(observed::add);
        Planner.Result result = planner.calculate();
        assertFalse(result.variants.isEmpty());
        Evaluation best = result.variants.get(0);
        assertEquals(2, best.network.connected.size());
        List<String> geometry = new ArrayList<>();
        for (Network.Edge e : best.network.edges.values())
          geometry.add(e.geometry.toText() + ":" + e.dn);
        Collections.sort(geometry);
        if (expected != null) {
          assertEquals(expected, geometry);
          assertEquals(score, best.score, 1e-8);
        }
        expected = geometry;
        score = best.score;
        for (var row : observed)
          if (Boolean.TRUE.equals(row.get("feasible"))) {
            assertTrue(
                (double) row.get("lowerBound") <= (double) row.get("score") + 1e-7, row.toString());
            labels++;
          }
        if (policy.equals("on"))
          assertTrue(
              JSON.valueToTree(result.search)
                      .path("neuralGuidance")
                      .path("neuralPredictions")
                      .asLong()
                  > 0);
      }
    }
    assertTrue(labels > 10);
  }

  @Test
  void lengthPromotionAndRoadDepthCannotMakeTheBoundTooLarge() {
    Store store =
        basic()
            .add(feature("a", "oks_future", box(290, 90, 20, 20), "flow_tph", 8, "heat_load", .4))
            .add(feature("entry_a", "oks_connection_point", Geo.point(c(290, 100)), "oks_id", "a"))
            .add(feature("road", "restriction", box(70, 20, 15, 170), "restriction_type", "road"));
    Network network = one(store, 100, "a", 8);
    network.nodes.get("a").point.x = c(290, 100).x;
    network.edges.put("e", new Network.Edge("e", "r", "a", Geo.line(c(0, 100), c(290, 100))));
    Planner.Options options = new Planner.Options();
    options.mode = "depth";
    CandidateBound.Value bound = CandidateBound.calculate(network, "e", store, options);
    Evaluation actual = new Evaluation(network, store, InputData.demands(store), true, 6, options);
    assertTrue(actual.network.edges.get("e").dn > Rules.diameter(8));
    assertTrue(bound.score < actual.score);
    assertTrue(bound.cost < (double) actual.summary.get("calculated_cost"));
  }

  @Test
  void anOldBranchWhoseClearanceFailsAtAPromotedDiameterIsNotAssumedStationary() {
    Store store =
        basic()
            .add(feature("a", "oks_future", box(100, 90, 20, 20), "flow_tph", 900, "heat_load", 45))
            .add(feature("near", "oks_existing", box(40, 107, 20, 20)));
    Network network = one(store, 100, "a", 900);
    // DN400 clears this body; DN500 would need 7 m plus half the pipe envelope.
    CandidateBound.Value value =
        CandidateBound.calculate(network, "new-edge-not-present", store, new Planner.Options());
    assertEquals(0, value.fixedEdges);
    assertTrue(value.score > 0);
  }
}
