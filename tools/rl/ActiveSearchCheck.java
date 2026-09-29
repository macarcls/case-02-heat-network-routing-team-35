package ru.teplotrassa.experiment;

import java.nio.file.*;
import java.util.*;
import ru.teplotrassa.api.*;
import ru.teplotrassa.data.*;
import ru.teplotrassa.engine.*;

/**
 * Reproducible two-run check on the supplied map, including a fresh persistence-service instance.
 */
public final class ActiveSearchCheck {
  public static void main(String[] args) throws Exception {
    Path output = Path.of(args[0]);
    Files.createDirectories(output);
    int episodes = args.length > 1 ? Integer.parseInt(args[1]) : 5;
    Path input = Path.of("examples/supplied.geojson");
    String sha = ExperienceStore.hash(Files.readAllBytes(input)), owner = "c".repeat(64);
    List<Object> runs = new ArrayList<>();
    double best = Double.POSITIVE_INFINITY;
    for (int run = 1; run <= 2; run++) {
      NeuralExperiment.Store raw = new NeuralExperiment.Store();
      new GeoJsonInput(NeuralExperiment.JSON).read(input, raw::add, false);
      Planner.Options o = new Planner.Options();
      o.routingStrategy = "reinforcement";
      o.neuralGuidance = "off";
      o.mode = "depth";
      o.dataMode = "scenario";
      o.gridM = 20;
      o.existingLoadPercent = 50d;
      o.rlEpisodes = episodes;
      o.rlLearning = true;
      FeatureStore store = InputData.scenario(raw, o);
      ExperienceStore memory =
          new ExperienceStore(output.resolve("checkpoints"), NeuralExperiment.JSON);
      final int number = run;
      try (var lease = memory.lock(owner, () -> false)) {
        var session =
            memory.open(owner, sha, UUID.nameUUIDFromBytes(input.toString().getBytes()), o);
        Planner.Result r =
            new Planner(
                    store,
                    o,
                    () -> Files.exists(output.resolve("STOP")),
                    (p, t) -> System.err.println("run " + number + ": " + t))
                .withLearning(session.training)
                .withExperience(session.networks, session::save)
                .calculate();
        if (r.variants.isEmpty()) throw new IllegalStateException("No complete network");
        if (r.variants.get(0).score > best + 1e-9)
          throw new IllegalStateException("Lost incumbent");
        best = r.variants.get(0).score;
        r.metadata.put("data_mode", "scenario");
        r.metadata.put("existing_load_percent", 50);
        r.metadata.put("rules_version", Rules.VERSION);
        Map<String, Object> report = new LinkedHashMap<>();
        report.put("options", o);
        report.put("inputSha256", sha);
        report.put("search", r.search);
        report.put("elapsedMs", r.elapsedMs);
        report.put("variants", r.variants.stream().map(v -> v.summary).toArray());
        report.put("checks", r.variants.stream().map(v -> v.checks).toArray());
        report.put("diagnostics", r.diagnostics);
        report.put("scenarioChanges", InputData.scenarioChanges(store));
        String name = "supplied-learning-run" + run;
        NeuralExperiment.write(output.resolve(name + "-report.json"), report);
        GeoJsonOutput.write(output.resolve(name + ".geojson"), r, store, NeuralExperiment.JSON);
        runs.add(
            Map.of(
                "run",
                run,
                "bestScore",
                best,
                "elapsedMs",
                r.elapsedMs,
                "resumed",
                session.resumed,
                "learning",
                r.search.get("reinforcement")));
        NeuralExperiment.write(output.resolve("two-runs.json"), runs);
        System.err.println("FINISHED run=" + run + " score=" + best + " ms=" + r.elapsedMs);
      }
    }
  }
}
