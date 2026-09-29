package ru.teplotrassa;

import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.databind.*;
import java.net.*;
import java.net.http.*;
import java.nio.file.*;
import java.time.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.web.server.LocalServerPort;
import org.springframework.test.context.ActiveProfiles;
import ru.teplotrassa.api.BuildInfo;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
class ApiTest {
  @LocalServerPort int port;
  static final ObjectMapper JSON = new ObjectMapper();
  HttpClient client;
  String base;

  @BeforeEach
  void setup() {
    client =
        HttpClient.newBuilder()
            .cookieHandler(new CookieManager(null, CookiePolicy.ACCEPT_ALL))
            .build();
    base = "http://localhost:" + port + "/api/v1";
  }

  JsonNode get(String path) throws Exception {
    HttpResponse<String> r =
        client.send(
            HttpRequest.newBuilder(URI.create(base + path)).GET().build(),
            HttpResponse.BodyHandlers.ofString());
    assertEquals(200, r.statusCode(), r.body());
    return JSON.readTree(r.body());
  }

  JsonNode poll(String path, String terminal) throws Exception {
    long end = System.nanoTime() + 60_000_000_000L;
    for (; ; ) {
      JsonNode n = get(path);
      if (terminal.equals(n.path("status").asText())) return n;
      if (n.path("status").asText().equals("FAILED")) fail(n.toString());
      if (System.nanoTime() > end) fail("timeout: " + n);
      Thread.sleep(30);
    }
  }

  @ParameterizedTest
  @ValueSource(strings = {"plan", "depth"})
  void uploadCalculateDownloadIsolationAndSwagger(String mode) throws Exception {
    String example = mode.equals("depth") ? "depth-demo.geojson" : "complete.geojson";
    HttpResponse<String> upload =
        client.send(
            HttpRequest.newBuilder(URI.create(base + "/datasets?name=" + example))
                .header("Content-Type", "application/geo+json")
                .POST(HttpRequest.BodyPublishers.ofFile(Path.of("../examples/" + example)))
                .build(),
            HttpResponse.BodyHandlers.ofString());
    assertEquals(202, upload.statusCode(), upload.body());
    String dataset = JSON.readTree(upload.body()).path("id").asText();
    assertTrue(
        poll("/datasets/" + dataset, "READY")
            .path("inputPreparation")
            .path("strictReady")
            .asBoolean());
    HttpResponse<String> start =
        client.send(
            HttpRequest.newBuilder(URI.create(base + "/datasets/" + dataset + "/jobs"))
                .header("Content-Type", "application/json")
                .POST(
                    HttpRequest.BodyPublishers.ofString(
                        "{\"candidateLimit\":3,\"mode\":\""
                            + mode
                            + "\""
                            + (mode.equals("depth") ? ",\"timeLimitSeconds\":0" : "")
                            + "}"))
                .build(),
            HttpResponse.BodyHandlers.ofString());
    assertEquals(202, start.statusCode(), start.body());
    String job = JSON.readTree(start.body()).path("id").asText();
    JsonNode done = poll("/jobs/" + job, "DONE");
    assertEquals(BuildInfo.ID, done.path("summary").path("application").path("buildId").asText());
    assertEquals(BuildInfo.VERSION, get("/health").path("version").asText());
    assertFalse(done.path("summary").path("options").has("timeLimitSeconds"));
    assertFalse(done.path("summary").has("timeLimited"));
    assertFalse(JSON.readTree(done.path("options").asText()).has("timeLimitSeconds"));
    JsonNode variants = done.path("summary").path("variants");
    double bestScore = variants.get(0).path("score").asDouble();
    double previousScore = -1, previousRating = 101;
    for (int i = 0; i < variants.size(); i++) {
      JsonNode variant = variants.get(i);
      double score = variant.path("score").asDouble();
      double rating = variant.path("rating").asDouble();
      assertEquals(i + 1, variant.path("rank").asInt());
      assertTrue(score >= previousScore);
      assertTrue(rating <= previousRating);
      assertEquals(score == 0 ? 100 : 100 * (bestScore / score), rating, 1e-9);
      previousScore = score;
      previousRating = rating;
    }
    assertEquals(100, variants.get(0).path("rating").asDouble());
    assertEquals(
        0, done.path("summary").path("variants").get(0).path("unconnected_oks_ids").size());
    JsonNode output = get("/jobs/" + job + "/result");
    assertEquals(
        BuildInfo.ID, output.path("metadata").path("application").path("buildId").asText());
    assertEquals("FeatureCollection", output.path("type").asText());
    assertFalse(output.path("metadata").has("time_limited"));
    assertTrue(output.path("metadata").path("input_complete").asBoolean());
    assertEquals(mode, output.path("metadata").path("calculation_mode").asText());
    assertEquals(done.path("summary").path("ranking"), output.path("metadata").path("ranking"));
    for (JsonNode feature : output.path("features")) {
      JsonNode properties = feature.path("properties");
      if (properties.path("object_type").asText().equals("variant_summary")) {
        JsonNode variant = variants.get(properties.path("rank").asInt() - 1);
        assertEquals(variant.path("score"), properties.path("score"));
        assertEquals(variant.path("rating"), properties.path("rating"));
      }
    }
    if (mode.equals("depth")) {
      JsonNode checks = done.path("summary").path("checks").get(0).path("depth");
      assertTrue(checks.path("maximumDepthM").asDouble() > 3);
      assertTrue(checks.path("minimumDepthM").asDouble() < 3);
      assertTrue(checks.path("maximumSlope").asDouble() <= .1000001);
      assertTrue(checks.path("nodeContinuityValid").asBoolean());
      Path out = Path.of("../validation");
      Files.createDirectories(out);
      JSON.writerWithDefaultPrettyPrinter()
          .writeValue(out.resolve("depth-demo.geojson").toFile(), output);
      JSON.writerWithDefaultPrettyPrinter()
          .writeValue(out.resolve("depth-demo-report.json").toFile(), done.path("summary"));
    }
    assertTrue(get("/jobs/" + job + "/view").path("features").size() > 0);
    HttpClient outsider = HttpClient.newHttpClient();
    assertEquals(
        404,
        outsider
            .send(
                HttpRequest.newBuilder(URI.create(base + "/jobs/" + job)).GET().build(),
                HttpResponse.BodyHandlers.ofString())
            .statusCode());
    HttpResponse<String> swagger =
        client.send(
            HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/v3/api-docs"))
                .GET()
                .build(),
            HttpResponse.BodyHandlers.ofString());
    assertEquals(200, swagger.statusCode());
    assertTrue(swagger.body().contains("/api/v1/datasets"));
    assertFalse(swagger.body().contains("timeLimitSeconds"));
  }

  @Test
  @Timeout(60)
  void longSearchCanBeCancelledThroughHttpWithoutPublishingPartialResults() throws Exception {
    HttpResponse<String> upload =
        client.send(
            HttpRequest.newBuilder(URI.create(base + "/datasets"))
                .header("Content-Type", "application/geo+json")
                .POST(HttpRequest.BodyPublishers.ofFile(Path.of("../examples/supplied.geojson")))
                .build(),
            HttpResponse.BodyHandlers.ofString());
    assertEquals(202, upload.statusCode(), upload.body());
    String dataset = JSON.readTree(upload.body()).path("id").asText();
    poll("/datasets/" + dataset, "READY");
    HttpResponse<String> start =
        client.send(
            HttpRequest.newBuilder(URI.create(base + "/datasets/" + dataset + "/jobs"))
                .header("Content-Type", "application/json")
                .POST(
                    HttpRequest.BodyPublishers.ofString(
                        "{\"dataMode\":\"scenario\",\"existingLoadPercent\":50,"
                            + "\"candidateLimit\":50,\"gridM\":20,\"mode\":\"depth\"}"))
                .build(),
            HttpResponse.BodyHandlers.ofString());
    assertEquals(202, start.statusCode(), start.body());
    String job = JSON.readTree(start.body()).path("id").asText();
    HttpResponse<String> cancel =
        client.send(
            HttpRequest.newBuilder(URI.create(base + "/jobs/" + job + "/cancel"))
                .POST(HttpRequest.BodyPublishers.noBody())
                .build(),
            HttpResponse.BodyHandlers.ofString());
    assertEquals(200, cancel.statusCode(), cancel.body());
    assertTrue(JSON.readTree(cancel.body()).path("cancelled").asBoolean());
    JsonNode cancelled = poll("/jobs/" + job, "CANCELLED");
    assertTrue(cancelled.path("summary").isNull());
    assertTrue(cancelled.path("cancelled").asBoolean());
  }

  @Test
  void incompleteFileHasActionableDiagnosticsAndNoGenericConfirmationGate() throws Exception {
    HttpResponse<String> upload =
        client.send(
            HttpRequest.newBuilder(URI.create(base + "/datasets"))
                .header("Content-Type", "application/geo+json")
                .POST(HttpRequest.BodyPublishers.ofFile(Path.of("../examples/supplied.geojson")))
                .build(),
            HttpResponse.BodyHandlers.ofString());
    String id = JSON.readTree(upload.body()).path("id").asText();
    JsonNode data = poll("/datasets/" + id, "READY");
    assertFalse(data.path("inputPreparation").path("strictReady").asBoolean());
    assertTrue(data.path("inputPreparation").path("diagnostics").size() > 30);
    HttpResponse<String> start =
        client.send(
            HttpRequest.newBuilder(URI.create(base + "/datasets/" + id + "/jobs"))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString("{}"))
                .build(),
            HttpResponse.BodyHandlers.ofString());
    assertEquals(400, start.statusCode());
    assertTrue(start.body().contains("upstream_object_id"));
    assertFalse(start.body().contains("Подтвердите"));
  }

  @Test
  @Timeout(90)
  void learningOptionsPersistenceAndWorkspaceIsolationAreExposedThroughApi() throws Exception {
    String dataset =
        postJson(
                "/datasets?name=learning.geojson",
                Files.readString(Path.of("../examples/complete.geojson")),
                202)
            .path("id")
            .asText();
    poll("/datasets/" + dataset, "READY");
    String options =
        "{\"routingStrategy\":\"reinforcement\",\"rlLearning\":true,\"rlRemember\":true,"
            + "\"rlEpisodes\":5,\"rlBatchSize\":4,\"candidateLimit\":3,\"gridM\":20,\"mode\":\"depth\"}";
    String first = postJson("/datasets/" + dataset + "/jobs", options, 202).path("id").asText();
    JsonNode one = poll("/jobs/" + first, "DONE").path("summary");
    assertTrue(
        one.path("search").path("reinforcement").path("trainingDuringCalculation").asBoolean());
    assertEquals(1, one.path("search").path("reinforcement").path("updatesThisRun").asInt());
    assertTrue(one.path("search").path("reinforcement").path("weightsChanged").asBoolean());
    assertTrue(one.path("search").path("reinforcement").path("weightDeltaL2").asDouble() > 0);
    assertFalse(one.path("variants").isEmpty());
    String second = postJson("/datasets/" + dataset + "/jobs", options, 202).path("id").asText();
    JsonNode two = poll("/jobs/" + second, "DONE").path("summary");
    assertTrue(two.path("search").path("experience").path("resumedTraining").asBoolean());
    assertEquals(5, two.path("search").path("reinforcement").path("resumedRollouts").asInt());
    assertEquals(2, two.path("search").path("reinforcement").path("totalUpdates").asInt());
    var quality = two.path("search").path("qualityProgress");
    assertEquals("saved_network", quality.path("baselineSource").asText());
    assertEquals(
        one.path("variants").get(0).path("score").asDouble(),
        quality.path("bestBeforeRun").asDouble(),
        1e-8);
    assertEquals(
        two.path("variants").get(0).path("score").asDouble(),
        quality.path("bestAfterRun").asDouble(),
        1e-8);
    for (var attempt : two.path("search").path("attempts")) {
      assertTrue(attempt.has("scoreAfterSmoothing"));
      assertTrue(attempt.path("bestScoreSoFar").isNumber());
    }
    assertTrue(
        two.path("variants").get(0).path("score").asDouble()
            <= one.path("variants").get(0).path("score").asDouble() + 1e-10);
    assertEquals(1, get("/experience").path("territories").asInt());
    assertFalse(get("/experience").path("canTrainGeneral").asBoolean());
    postJson("/experience/train", "", 409);
    postJson(
        "/datasets/" + dataset + "/jobs",
        options.replace("\"rlEpisodes\":5", "\"rlEpisodes\":5,\"rlTemperature\":0"),
        400);
    HttpResponse<String> outside =
        HttpClient.newHttpClient()
            .send(
                HttpRequest.newBuilder(URI.create(base + "/experience")).GET().build(),
                HttpResponse.BodyHandlers.ofString());
    assertEquals(200, outside.statusCode());
    assertEquals(0, JSON.readTree(outside.body()).path("territories").asInt());
  }

  JsonNode postJson(String path, String data, int status) throws Exception {
    HttpResponse<String> response =
        client.send(
            HttpRequest.newBuilder(URI.create(base + path))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(data))
                .build(),
            HttpResponse.BodyHandlers.ofString());
    assertEquals(status, response.statusCode(), response.body());
    return JSON.readTree(response.body());
  }
}
