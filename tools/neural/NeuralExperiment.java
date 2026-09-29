package ru.teplotrassa.experiment;

import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.*;
import java.nio.file.*;
import java.util.*;
import org.locationtech.jts.geom.*;
import ru.teplotrassa.data.*;
import ru.teplotrassa.engine.*;

/** Reproducible offline supervision and held-out experiments. No production search deadline. */
public final class NeuralExperiment {
  static final ObjectMapper JSON = new ObjectMapper();
  static final Coordinate ORIGIN = Geo.xy(37.6, 55.75);

  public static final class Store implements FeatureStore {
    final Map<String, Feature> map = new LinkedHashMap<>();

    public Store add(Feature f) {
      if (f.type.equals("oks_connection_point") && !f.properties.path("flow_tph").isNumber()) {
        Feature building = map.get(f.text("oks_id"));
        if (building != null && building.properties.path("flow_tph").isNumber()) {
          ObjectNode props = f.properties.deepCopy();
          props.set("flow_tph", building.properties.path("flow_tph"));
          f = new Feature(f.id, f.type, props, f.geometry);
        }
      }
      map.put(f.id, f);
      return this;
    }

    public Feature get(String id) {
      return map.get(id);
    }

    public Iterable<String> ids(String type) {
      List<String> out = new ArrayList<>();
      for (Feature f : map.values()) if (f.type.equals(type)) out.add(f.id);
      return out;
    }

    public List<Feature> near(Envelope area) {
      List<Feature> out = new ArrayList<>();
      for (Feature f : map.values())
        if (f.geometry.getEnvelopeInternal().intersects(area)) out.add(f);
      return out;
    }
  }

  static Coordinate p(double x, double y) {
    return new Coordinate(ORIGIN.x + x, ORIGIN.y + y);
  }

  static Polygon box(double x, double y, double w, double h) {
    return Geo.GF.createPolygon(
        new Coordinate[] {p(x, y), p(x + w, y), p(x + w, y + h), p(x, y + h), p(x, y)});
  }

  static Feature feature(String id, String type, Geometry geometry, Object... fields) {
    ObjectNode props = JSON.createObjectNode();
    props.put("id", id);
    props.put("object_type", type);
    for (int i = 0; i < fields.length; i += 2)
      props.set(fields[i].toString(), JSON.valueToTree(fields[i + 1]));
    return new Feature(id, type, props, geometry);
  }

  public static Store territory(int id) {
    Random r = new Random(2026092000L + 1000003L * id);
    Store store = new Store();
    store.add(feature("source", "source", Geo.point(p(0, 0))));
    store.add(
        feature(
            "old",
            "heat_network",
            Geo.line(p(0, 0), p(0, 440)),
            "diameter",
            125,
            "flow_tph",
            8,
            "upstream_object_id",
            "source"));
    int count = 3 + r.nextInt(2);
    for (int i = 0; i < count; i++) {
      double x = 90 + r.nextDouble() * 140, y = 55 + i * 95 + r.nextDouble() * 15;
      double w = 24 + r.nextDouble() * 25, h = 24 + r.nextDouble() * 18;
      double flow = new double[] {8, 18, 30, 55}[r.nextInt(4)];
      String name = "b" + i;
      store.add(
          feature(name, "oks_future", box(x, y, w, h), "flow_tph", flow, "heat_load", flow * .05));
      int side = r.nextInt(5);
      Coordinate entry =
          side < 3
              ? p(x + 1, y + h * .5)
              : side == 3 ? p(x + w * .5, y + 1) : p(x + w - 1, y + h * .5);
      store.add(feature("entry" + i, "oks_connection_point", Geo.point(entry), "oks_id", name));
      if (r.nextDouble() < .65)
        store.add(
            feature(
                "obstacle" + i,
                "oks_existing",
                box(
                    30 + r.nextDouble() * 20,
                    y - 8,
                    18 + r.nextDouble() * 12,
                    25 + r.nextDouble() * 40)));
    }
    if (id % 3 == 0)
      store.add(feature("road", "restriction", box(12, 15, 9, 400), "restriction_type", "road"));
    if (id % 4 == 0)
      store.add(
          feature(
              "reserve",
              "restriction",
              box(65, 160, 18, 25),
              "restriction_type",
              "prohibited_site"));
    double angle = (id % 7) * .13, cos = Math.cos(angle), sin = Math.sin(angle);
    for (Feature f : store.map.values()) {
      f.geometry.apply(
          (CoordinateFilter)
              c -> {
                double x = c.x - ORIGIN.x, y = c.y - ORIGIN.y;
                c.x = ORIGIN.x + x * cos - y * sin;
                c.y = ORIGIN.y + x * sin + y * cos;
              });
      f.geometry.geometryChanged();
    }
    return store;
  }

  static Planner.Options options(String policy, int id) {
    Planner.Options out = new Planner.Options();
    out.neuralGuidance = policy;
    out.gridM = 15;
    out.maxCells = 8000;
    out.candidateLimit = 4;
    out.variantLimit = 1;
    out.mode = id % 3 == 0 ? "depth" : "plan";
    out.rankingProfile = id % 5 == 0 ? "protocol" : "appendix";
    return out;
  }

  static Map<String, Object> summary(int territory, String policy, Planner.Result result) {
    Map<String, Object> out = new LinkedHashMap<>();
    out.put("territory", territory);
    out.put("policy", policy);
    out.put("elapsedMs", result.elapsedMs);
    out.put("search", result.search);
    out.put("variants", result.variants.stream().map(v -> v.summary).toArray());
    return out;
  }

  static void write(Path file, Object value) throws IOException {
    writeBytes(file, JSON.writerWithDefaultPrettyPrinter().writeValueAsBytes(value));
  }

  static void writeBytes(Path file, byte[] value) throws IOException {
    Path destination=file.toAbsolutePath();
    Files.createDirectories(destination.getParent());
    Path temporary=Files.createTempFile(destination.getParent(),"neural-write-",".tmp");
    try {
      Files.write(temporary,value);
      Files.move(temporary,destination,StandardCopyOption.ATOMIC_MOVE,StandardCopyOption.REPLACE_EXISTING);
    } finally { Files.deleteIfExists(temporary); }
  }

  static void collect(Path output, int first, int count) throws Exception {
    Files.createDirectories(output);
    List<Object> reports = new ArrayList<>();
    for (int id = first; id < first + count; id++) {
      final int mapId = id;
      Path labels = output.resolve(String.format("territory-%03d.jsonl", id));
      {
        List<Map<String,Object>> records=new ArrayList<>();
        Planner planner = new Planner(territory(id), options("off", id), () -> false, (p, t) -> {});
        Planner.Result result =
            planner
                .observeCandidates(
                    record -> {
                      if (Boolean.TRUE.equals(record.get("feasible"))
                          && (double) record.get("lowerBound")
                              > (double) record.get("score") + 1e-6)
                        throw new AssertionError("Non-admissible bound: " + record);
                      record.put("territory", mapId);
                      records.add(record);
                    })
                .calculate();
        long expected=JSON.valueToTree(result.search).path("neuralGuidance").path("candidates").asLong();
        if(records.size()!=expected)throw new AssertionError("Incomplete supervision: "+records.size()+" / "+expected);
        StringBuilder encoded=new StringBuilder();
        for(var record:records)encoded.append(JSON.writeValueAsString(record)).append('\n');
        writeBytes(labels,encoded.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8));
        if(Files.readAllLines(labels).size()!=records.size())throw new AssertionError("Incomplete data write");
        Map<String, Object> report = summary(id, "off-with-observer", result);
        reports.add(report);
        write(output.resolve(String.format("territory-%03d-report.json", id)), report);
        System.out.println(
            "collected territory="
                + id
                + " ms="
                + result.elapsedMs
                + " connected="
                + result.search.get("bestConnectedCount")
                + " required="
                + result.search.get("requiredConnections"));
      }
    }
    write(output.resolve("collection-" + first + ".json"), reports);
  }

  static void benchmark(Path output, int first, int count) throws Exception {
    Files.createDirectories(output);
    List<Object> reports = new ArrayList<>();
    // Warm each code path in the same JVM; fresh planners below have separate caches.
    for (int warm = 0; warm < 2; warm++)
      for (String policy : List.of("off", "bounds", "on"))
        new Planner(territory(999), options(policy, 999), () -> false, (p, t) -> {}).calculate();
    for (int id = first; id < first + count; id++) {
      Double score = null;
      Integer connected = null;
      List<String> policies = new ArrayList<>(List.of("off", "bounds", "on"));
      Collections.rotate(policies, id % 3);
      for (String policy : policies) {
        Planner.Result result =
            new Planner(territory(id), options(policy, id), () -> false, (p, t) -> {}).calculate();
        int n = (int) result.search.get("bestConnectedCount");
        double s = result.variants.isEmpty() ? -1 : result.variants.get(0).score;
        if (score != null && (Math.abs(score - s) > 1e-6 || connected != n))
          throw new AssertionError(
              "Policy changed solution on territory " + id + ": " + score + " vs " + s);
        score = s;
        connected = n;
        Map<String, Object> report = summary(id, policy, result);
        reports.add(report);
        write(output.resolve("ablation.json"), reports);
        System.out.println(
            "benchmark territory="
                + id
                + " policy="
                + policy
                + " ms="
                + result.elapsedMs
                + " score="
                + s
                + " guidance="
                + JSON.writeValueAsString(result.search.get("neuralGuidance")));
      }
    }
  }

  static void supplied(Path output, String policy) throws Exception {
    Store raw = new Store();
    new GeoJsonInput(JSON).read(Path.of("examples/supplied.geojson"), raw::add, false);
    Planner.Options options = new Planner.Options();
    options.mode = "depth";
    options.dataMode = "scenario";
    options.existingLoadPercent = 50d;
    options.gridM = 20;
    options.neuralGuidance = policy;
    FeatureStore store = InputData.scenario(raw, options);
    Planner.Result result =
        new Planner(store, options, () -> false, (p, t) -> System.out.println(p + "% " + t))
            .calculate();
    if (result.variants.size() != 3 || (int) result.search.get("bestConnectedCount") != 17)
      throw new AssertionError("Expected three complete variants for all 17 entries");
    Map<String, Object> report = summary(-1, policy, result);
    report.put("options", options);
    report.put("rulesVersion", Rules.VERSION);
    report.put("harness", "engine in-process; LinkedHashMap FeatureStore; Java 11; Xmx768m");
    report.put("checks", result.variants.stream().map(v -> v.checks).toArray());
    List<Object> changes = new ArrayList<>();
    for (String type : List.of("heat_network", "heat_chamber", "oks_future"))
      for (Feature f : store.all(type))
        if (!f.properties.equals(raw.get(f.id).properties)) changes.add(f.properties);
    report.put("scenarioChanges", changes);
    report.put("diagnostics", result.diagnostics);
    result.metadata.put("data_mode", "scenario");
    result.metadata.put("input_complete", false);
    result.metadata.put("existing_load_percent", 50);
    result.metadata.put("rules_version", Rules.VERSION);
    write(output.resolve("supplied-neural-" + policy + "-report.json"), report);
    GeoJsonOutput.write(
        output.resolve("supplied-neural-" + policy + ".geojson"), result, store, JSON);
    System.out.println("supplied complete ms=" + result.elapsedMs);
  }

  public static void main(String[] args) throws Exception {
    String action = args[0];
    Path output = Path.of(args[1]);
    if (action.equals("collect"))
      collect(output, Integer.parseInt(args[2]), Integer.parseInt(args[3]));
    else if (action.equals("benchmark"))
      benchmark(output, Integer.parseInt(args[2]), Integer.parseInt(args[3]));
    else if (action.equals("supplied")) supplied(output, args.length > 2 ? args[2] : "on");
    else throw new IllegalArgumentException("collect, benchmark or supplied required");
  }
}
