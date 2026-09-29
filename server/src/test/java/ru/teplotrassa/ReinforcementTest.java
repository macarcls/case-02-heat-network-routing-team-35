package ru.teplotrassa;

import static org.junit.jupiter.api.Assertions.*;
import static ru.teplotrassa.EngineTest.*;

import com.fasterxml.jackson.databind.node.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;
import ru.teplotrassa.engine.*;

class ReinforcementTest {
  static Planner.Options settings() {
    Planner.Options o = new Planner.Options();
    o.routingStrategy = "reinforcement";
    o.rlEpisodes = 4;
    o.candidateLimit = 3;
    o.variantLimit = 1;
    o.mode = "depth";
    o.gridM = 20;
    return o;
  }

  static Store district() {
    return basic()
        .add(feature("b", "oks_future", box(60, 140, 20, 20), "flow_tph", 10, "heat_load", .5))
        .add(feature("entry_b", "oks_connection_point", Geo.point(c(61, 150)), "oks_id", "b"));
  }

  @Test
  void exportedPolicyMatchesNumpyAndRejectsIncompatibleWeights() throws Exception {
    ReinforcementPolicy policy = ReinforcementPolicy.bundled();
    assertTrue(policy.available(), policy.unavailableReason);
    var fixtures = JSON.readTree(Path.of("../tools/rl/inference-fixtures.json").toFile());
    assertTrue(fixtures.size() >= 20);
    for (var fixture : fixtures)
      assertEquals(
          fixture.path("logit").asDouble(),
          policy.logit(JSON.convertValue(fixture.path("features"), double[].class)),
          1e-9);
    ObjectNode bad =
        (ObjectNode)
            JSON.readTree(Path.of("src/main/resources/models/reinforcement-policy.json").toFile());
    bad.put("reward", "different-environment");
    assertThrows(
        java.io.IOException.class, () -> new ReinforcementPolicy(JSON.writeValueAsBytes(bad)));
    double[] invalid = new double[ReinforcementFeatures.NAMES.size()];
    invalid[0] = Double.NaN;
    assertThrows(IllegalArgumentException.class, () -> policy.logit(invalid));
    assertEquals(64, policy.sha256.length());
  }

  @Test
  void agentCanChooseSharedBranchesOrSeparateTieIns() {
    Store store = district();
    Planner planner = new Planner(store, settings(), () -> false, (p, t) -> {});
    List<Integer> roots = new ArrayList<>();
    for (String kind : List.of("branch", "existing")) {
      Planner.RlEpisode e = planner.newRlEpisode();
      int first = -1;
      double closest = Double.POSITIVE_INFINITY;
      for (int i = 0; i < e.actions().size(); i++) {
        var a = e.actions().get(i);
        if (a.entryId.equals("entry_a") && a.features[0] < closest) {
          first = i;
          closest = a.features[0];
        }
      }
      assertTrue(first >= 0 && e.step(first));
      boolean added = false;
      while (!e.actions().isEmpty()) {
        int next = -1;
        for (int i = 0; i < e.actions().size(); i++)
          if (e.actions().get(i).targetKind.equals(kind)) {
            next = i;
            break;
          }
        if (next < 0) break;
        if (e.step(next)) {
          added = true;
          break;
        }
      }
      assertTrue(added, "Expected a valid " + kind + " action");
      assertEquals(2, e.connected());
      Evaluation v = e.evaluation();
      roots.add(v.network.roots().size());
      assertEquals(true, v.checks.get("originalEndpointsPreserved"));
      assertEquals(true, v.checks.get("buildingEntriesValid"));
      assertEquals(true, v.checks.get("depthChecked"));
      assertTrue(e.reward() > -1);
    }
    assertEquals(List.of(1, 2), roots);
  }

  @Test
  void seededEpisodesAreRepeatableAndReturnOnlyCompleteValidatedNetworks() {
    List<Object> expected = null;
    for (int repeat = 0; repeat < 2; repeat++) {
      Planner.Result r = new Planner(district(), settings(), () -> false, (p, t) -> {}).calculate();
      assertFalse(r.variants.isEmpty());
      assertEquals(4, ((List<?>) r.search.get("attempts")).size());
      var info = JSON.valueToTree(r.search).path("reinforcement");
      assertEquals(4, info.path("completedEpisodes").asInt());
      assertTrue(info.path("decisions").asInt() >= 8);
      assertFalse(info.path("trainingDuringCalculation").asBoolean());
      Evaluation best = r.variants.get(0);
      assertEquals(2, best.network.connected.size());
      assertEquals("reinforcement", best.summary.get("routing_strategy"));
      List<Object> signature =
          List.of(
              r.search.get("attempts"),
              best.score,
              best.network.edges.values().stream()
                  .map(e -> e.geometry.toText())
                  .toArray(String[]::new));
      if (expected != null) assertEquals(JSON.valueToTree(expected), JSON.valueToTree(signature));
      expected = signature;
    }
  }

  @Test
  void incompleteNetworksNeverReceiveARewardBetterThanCompleteNetworks() {
    Store store = district();
    Planner p = new Planner(store, settings(), () -> false, (a, b) -> {});
    var e = p.newRlEpisode();
    assertEquals(-3, e.reward());
    while (!e.actions().isEmpty()) e.step(0);
    assertEquals(2, e.connected());
    assertTrue(e.reward() > -1 && e.reward() <= 0);
  }

  @Test
  void cancellationIsHonouredInsideAnEpisodeAndOptionBoundsAreValidated() {
    AtomicBoolean stop = new AtomicBoolean(false);
    Planner p = new Planner(district(), settings(), stop::get, (a, b) -> {});
    var e = p.newRlEpisode();
    assertFalse(e.actions().isEmpty());
    stop.set(true);
    assertFalse(e.step(0));
    assertEquals(0, e.connected());
    Planner.Result cancelled = p.calculate();
    assertEquals("CANCELLED", cancelled.search.get("status"));
    assertTrue(cancelled.variants.isEmpty());
    Planner.Options o = settings();
    o.rlEpisodes = 0;
    assertThrows(IllegalArgumentException.class, o::validate);
    o.rlEpisodes = 1;
    o.rlTemperature = Double.NaN;
    assertThrows(IllegalArgumentException.class, o::validate);
  }
}
