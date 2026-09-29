package ru.teplotrassa.experiment;

import com.fasterxml.jackson.databind.*;
import java.io.*;
import java.nio.file.*;
import java.util.*;
import ru.teplotrassa.data.*;
import ru.teplotrassa.engine.*;

/** Line-delimited JSON transport to the SAME Java environment used by the web application. */
public final class RlBridge {
  static final ObjectMapper JSON = new ObjectMapper();
  static final Map<Integer, Planner> PLANNERS = new LinkedHashMap<>();
  static Planner.RlEpisode episode;

  static Planner.Options options(int id) {
    Planner.Options o = new Planner.Options();
    o.routingStrategy = "reinforcement";
    o.neuralGuidance = "off";
    o.gridM = 20;
    o.candidateLimit = 2;
    o.variantLimit = 1;
    o.mode = id % 3 == 0 ? "depth" : "plan";
    return o;
  }

  static Map<String, Object> state() {
    List<Object> actions = new ArrayList<>();
    for (Planner.RlAction a : episode.actions())
      actions.add(Map.of("id", a.id, "features", a.features, "targetKind", a.targetKind));
    Map<String, Object> out = new LinkedHashMap<>();
    out.put("actions", actions);
    out.put("connected", episode.connected());
    out.put("required", episode.required());
    out.put("done", actions.isEmpty());
    if (actions.isEmpty()) {
      Evaluation value = episode.evaluation();
      out.put("reward", Planner.rlReward(value, episode.required()));
      out.put("summary", value.summary);
      out.put("checks", value.checks);
    }
    return out;
  }

  static void serve() throws Exception {
    BufferedReader input =
        new BufferedReader(
            new InputStreamReader(System.in, java.nio.charset.StandardCharsets.UTF_8));
    String line;
    while ((line = input.readLine()) != null) {
      Map<String, Object> out;
      try {
        JsonNode request = JSON.readTree(line);
        switch (request.path("command").asText()) {
          case "schema":
            out =
                Map.of(
                    "features", ReinforcementFeatures.NAMES, "environment", "exact-java-engine-v1");
            break;
          case "reset":
            int id = request.path("territory").asInt();
            if (id < 0 || id > 999) throw new IllegalArgumentException("Use territory 0..999");
            Planner planner =
                PLANNERS.computeIfAbsent(
                    id,
                    n ->
                        new Planner(
                            NeuralExperiment.territory(1000 + n),
                            options(n),
                            () -> false,
                            (p, t) -> {}));
            episode = planner.newRlEpisode();
            out = state();
            break;
          case "step":
            if (episode == null) throw new IllegalArgumentException("reset required");
            boolean accepted = episode.step(request.path("action").asInt(-1));
            out = state();
            out.put("accepted", accepted);
            break;
          default:
            throw new IllegalArgumentException("Unknown bridge command");
        }
      } catch (Exception e) {
        out = Map.of("error", e.toString());
      }
      System.out.println(JSON.writeValueAsString(out));
      System.out.flush();
    }
  }

  static void benchmark(Path output, int first, int count) throws Exception {
    List<Object> results = new ArrayList<>();
    ReinforcementPolicy initial =
        new ReinforcementPolicy(
            Files.readAllBytes(Path.of("validation/rl/training/initial-policy.json")));
    for (int id = first; id < first + count; id++) {
      List<String> modes = new ArrayList<>(List.of("classic", "reinforcement", "untrained"));
      Collections.rotate(modes, id % 3);
      for (String mode : modes) {
        Planner.Options o = options(id);
        o.routingStrategy = mode.equals("classic") ? "classic" : "reinforcement";
        o.rlEpisodes = 6;
        o.rlSeed = 71;
        o.rlTemperature = .7;
        Planner planner =
            new Planner(NeuralExperiment.territory(1000 + id), o, () -> false, (p, t) -> {});
        if (mode.equals("untrained")) planner.withReinforcementPolicy(initial);
        Planner.Result r = planner.calculate();
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("territory", id);
        row.put("strategy", mode);
        row.put("options", o);
        row.put("elapsedMs", r.elapsedMs);
        row.put("search", r.search);
        row.put("variants", r.variants.stream().map(v -> v.summary).toArray());
        row.put("checks", r.variants.stream().map(v -> v.checks).toArray());
        results.add(row);
        NeuralExperiment.write(output.resolve("benchmark.json"), results);
        GeoJsonOutput.write(
            output.resolve("territory-" + id + "-" + mode + ".geojson"),
            r,
            NeuralExperiment.territory(1000 + id),
            JSON);
        System.err.println(
            "benchmark "
                + id
                + " "
                + mode
                + " connected="
                + r.search.get("bestConnectedCount")
                + " ms="
                + r.elapsedMs
                + " score="
                + (r.variants.isEmpty() ? "none" : r.variants.get(0).score));
      }
    }
  }

  static void supplied(Path output, int episodes) throws Exception {
    NeuralExperiment.Store raw = new NeuralExperiment.Store();
    new GeoJsonInput(JSON).read(Path.of("examples/supplied.geojson"), raw::add, false);
    Planner.Options o = new Planner.Options();
    o.routingStrategy = "reinforcement";
    o.neuralGuidance = "off";
    o.mode = "depth";
    o.dataMode = "scenario";
    o.gridM = 20;
    o.existingLoadPercent = 50d;
    o.rlEpisodes = episodes;
    FeatureStore store = InputData.scenario(raw, o);
    Planner.Result r =
        new Planner(store, o, () -> false, (p, t) -> System.err.println(t)).calculate();
    Map<String, Object> row = new LinkedHashMap<>();
    row.put("options", o);
    row.put("elapsedMs", r.elapsedMs);
    row.put("search", r.search);
    row.put("variants", r.variants.stream().map(v -> v.summary).toArray());
    row.put("checks", r.variants.stream().map(v -> v.checks).toArray());
    row.put("diagnostics", r.diagnostics);
    row.put("rulesVersion", Rules.VERSION);
    List<Object> changes = new ArrayList<>();
    for (String type : List.of("heat_network", "heat_chamber", "oks_future"))
      for (Feature f : store.all(type))
        if (!f.properties.equals(raw.get(f.id).properties)) changes.add(f.properties);
    row.put("scenarioChanges", changes);
    r.metadata.put("rules_version", Rules.VERSION);
    r.metadata.put("data_mode", "scenario");
    r.metadata.put("existing_load_percent", 50);
    NeuralExperiment.write(output.resolve("supplied-rl-report.json"), row);
    GeoJsonOutput.write(output.resolve("supplied-rl.geojson"), r, store, JSON);
    System.err.println("supplied completed ms=" + r.elapsedMs);
  }

  public static void main(String[] args) throws Exception {
    if (args.length == 0 || args[0].equals("serve")) serve();
    else if (args[0].equals("benchmark"))
      benchmark(Path.of(args[1]), Integer.parseInt(args[2]), Integer.parseInt(args[3]));
    else if (args[0].equals("supplied")) supplied(Path.of(args[1]), Integer.parseInt(args[2]));
    else throw new IllegalArgumentException("serve, benchmark or supplied required");
  }
}
