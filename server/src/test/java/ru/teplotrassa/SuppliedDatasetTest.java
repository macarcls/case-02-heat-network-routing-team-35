package ru.teplotrassa;

import static org.junit.jupiter.api.Assertions.*;
import static ru.teplotrassa.EngineTest.*;

import java.nio.file.*;
import java.util.*;
import org.junit.jupiter.api.*;
import ru.teplotrassa.data.*;
import ru.teplotrassa.engine.*;

/** Opt-in reproducible scenario; never labels inferred loads as measured inputs. */
class SuppliedDatasetTest {
  @Test
  void calculateSuppliedScenario() throws Exception {
    Assumptions.assumeTrue(
        Boolean.getBoolean("suppliedDataset") || Boolean.getBoolean("suppliedDepthDataset"));
    Store raw = new Store();
    new GeoJsonInput(JSON).read(Path.of("../examples/supplied.geojson"), raw::add, false);
    Planner.Options options = new Planner.Options();
    options.mode = Boolean.getBoolean("suppliedDepthDataset") ? "depth" : "plan";
    options.dataMode = "scenario";
    options.existingLoadPercent = 50d;
    options.candidateLimit = 5;
    FeatureStore prepared = InputData.scenario(raw, options);
    Planner.Result r =
        new Planner(prepared, options, () -> false, (p, t) -> System.out.println(p + "% " + t))
            .calculate();
    assertFalse(r.variants.isEmpty());
    Path out = Path.of("../validation");
    Files.createDirectories(out);
    r.metadata.put("data_mode", "scenario");
    r.metadata.put("input_complete", false);
    r.metadata.put("existing_load_percent", 50);
    r.metadata.put("ranking_profile", options.rankingProfile);
    r.metadata.put("rules_version", Rules.VERSION);
    String name =
        options.mode.equals("depth") ? "supplied-depth-scenario-50" : "supplied-scenario-50";
    GeoJsonOutput.write(out.resolve(name + ".geojson"), r, prepared, JSON);
    Map<String, Object> report = new LinkedHashMap<>();
    report.put("options", options);
    report.put("elapsedMs", r.elapsedMs);
    report.put("inputDiagnostics", InputDiagnostics.inspect(raw));
    report.put("variants", r.variants.stream().map(v -> v.summary).toArray());
    report.put("checks", r.variants.stream().map(v -> v.checks).toArray());
    report.put("diagnostics", r.diagnostics);
    JSON.writerWithDefaultPrettyPrinter()
        .writeValue(out.resolve(name + "-report.json").toFile(), report);
  }
}
