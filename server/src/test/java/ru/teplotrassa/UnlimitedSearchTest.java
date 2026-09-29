package ru.teplotrassa;

import static org.junit.jupiter.api.Assertions.*;
import static ru.teplotrassa.EngineTest.*;

import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import ru.teplotrassa.data.GeoJsonInput;
import ru.teplotrassa.engine.*;

class UnlimitedSearchTest {
  @Test
  @Timeout(60)
  void allStrategiesFinishBeyondLegacyDeadlineWithTheSameResults() throws Exception {
    // Ten seconds was the old minimum accepted budget. Pause actual work past it,
    // then require every strategy and the same costs/routes as an unstalled run.
    Planner.Options options =
        JSON.readValue(
            "{\"timeLimitSeconds\":10,\"candidateLimit\":3,\"variantLimit\":3}",
            Planner.Options.class);
    Store store = new Store();
    new GeoJsonInput(JSON).read(Path.of("../examples/complete.geojson"), store::add);
    List<String> baselineSteps = new ArrayList<>(), delayedSteps = new ArrayList<>();
    Planner.Result baseline =
        new Planner(store, options, () -> false, (p, text) -> baselineSteps.add(text)).calculate();
    AtomicBoolean paused = new AtomicBoolean();
    Planner.Result delayed =
        new Planner(
                store,
                options,
                () -> false,
                (p, text) -> {
                  delayedSteps.add(text);
                  if (paused.compareAndSet(false, true)) {
                    try {
                      Thread.sleep(11_000);
                    } catch (InterruptedException e) {
                      Thread.currentThread().interrupt();
                      throw new IllegalStateException(e);
                    }
                  }
                })
            .calculate();
    assertTrue(delayed.elapsedMs >= 11_000);
    assertTrue(delayedSteps.stream().filter(s -> s.contains(": ОКС ")).count() >= 9);
    assertEquals(baselineSteps, delayedSteps);
    assertEquals(Set.of("oks_A", "oks_B", "oks_C"), delayed.variants.get(0).network.connected);
    assertEquals(
        JSON.valueToTree(
            baseline.variants.stream().map(v -> v.summary).collect(Collectors.toList())),
        JSON.valueToTree(
            delayed.variants.stream().map(v -> v.summary).collect(Collectors.toList())));
    assertEquals(baseline.diagnostics, delayed.diagnostics);
    assertFalse(JSON.valueToTree(options).has("timeLimitSeconds"));
  }

  @ParameterizedTest
  @ValueSource(booleans = {false, true})
  void explicitCancellationAndThreadInterruptionStillStopSearch(boolean interrupt) {
    AtomicBoolean cancelled = new AtomicBoolean();
    Planner.Options options = new Planner.Options();
    List<String> steps = new ArrayList<>();
    try {
      Planner.Result result =
          new Planner(
                  basic(),
                  options,
                  cancelled::get,
                  (p, text) -> {
                    steps.add(text);
                    if (interrupt) Thread.currentThread().interrupt();
                    else cancelled.set(true);
                  })
              .calculate();
      assertEquals(1, steps.stream().filter(s -> s.contains(": ОКС ")).count());
      assertEquals("CANCELLED", result.search.get("status"));
      assertTrue(result.variants.stream().allMatch(v -> v.network.connected.isEmpty()));
      assertFalse(result.diagnostics.isEmpty());
      assertTrue(result.diagnostics.values().stream().allMatch(s -> s.contains("отменён")));
    } finally {
      if (interrupt) Thread.interrupted();
    }
  }
}
