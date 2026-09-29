package ru.teplotrassa;

import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.databind.*;
import java.net.*;
import java.net.http.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.*;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.web.server.LocalServerPort;
import org.springframework.test.context.ActiveProfiles;

/** Fifty isolated user sessions through import, queue, calculation and export. */
@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = {
      "spring.datasource.url=jdbc:h2:mem:load50;MODE=PostgreSQL;DB_CLOSE_DELAY=-1;DATABASE_TO_LOWER=TRUE",
      "teplotrassa.storage=./target/load50-data"
    })
@ActiveProfiles("test")
class Load50Test {
  @LocalServerPort int port;
  private final ObjectMapper json = new ObjectMapper();

  @Test
  void fiftyUsers() throws Exception {
    Assumptions.assumeTrue(Boolean.getBoolean("loadUsers"));
    long started = System.nanoTime();
    ExecutorService pool = Executors.newFixedThreadPool(50);
    List<Future<String>> futures = new ArrayList<>();
    try {
      for (int i = 0; i < 50; i++) futures.add(pool.submit(() -> oneUser()));
      Set<String> jobs = new HashSet<>();
      for (Future<String> result : futures) jobs.add(result.get(120, TimeUnit.SECONDS));
      assertEquals(50, jobs.size());
      Path out = Path.of("../validation");
      Files.createDirectories(out);
      json.writerWithDefaultPrettyPrinter()
          .writeValue(
              out.resolve("load-50-sessions.json").toFile(),
              Map.of(
                  "sessions",
                  50,
                  "completedJobs",
                  jobs.size(),
                  "workers",
                  2,
                  "queueCapacity",
                  100,
                  "dataset",
                  "complete.geojson, three connection points",
                  "database",
                  "H2 integration profile, not PostgreSQL production benchmark",
                  "elapsedMs",
                  (System.nanoTime() - started) / 1000000));
    } finally {
      pool.shutdownNow();
    }
  }

  private String oneUser() throws Exception {
    HttpClient client =
        HttpClient.newBuilder()
            .cookieHandler(new CookieManager(null, CookiePolicy.ACCEPT_ALL))
            .build();
    String base = "http://localhost:" + port + "/api/v1";
    HttpResponse<String> upload =
        client.send(
            HttpRequest.newBuilder(URI.create(base + "/datasets"))
                .header("Content-Type", "application/geo+json")
                .POST(HttpRequest.BodyPublishers.ofFile(Path.of("../examples/complete.geojson")))
                .build(),
            HttpResponse.BodyHandlers.ofString());
    assertEquals(202, upload.statusCode(), upload.body());
    String dataset = json.readTree(upload.body()).path("id").asText();
    await(client, base + "/datasets/" + dataset, "READY");
    HttpResponse<String> start =
        client.send(
            HttpRequest.newBuilder(URI.create(base + "/datasets/" + dataset + "/jobs"))
                .header("Content-Type", "application/json")
                .POST(
                    HttpRequest.BodyPublishers.ofString(
                        "{\"variantLimit\":1,\"candidateLimit\":2}"))
                .build(),
            HttpResponse.BodyHandlers.ofString());
    assertEquals(202, start.statusCode(), start.body());
    String job = json.readTree(start.body()).path("id").asText();
    await(client, base + "/jobs/" + job, "DONE");
    HttpResponse<String> result =
        client.send(
            HttpRequest.newBuilder(URI.create(base + "/jobs/" + job + "/result")).GET().build(),
            HttpResponse.BodyHandlers.ofString());
    assertEquals(200, result.statusCode());
    assertEquals("FeatureCollection", json.readTree(result.body()).path("type").asText());
    return job;
  }

  private void await(HttpClient client, String url, String expected) throws Exception {
    long deadline = System.nanoTime() + 110_000_000_000L;
    while (System.nanoTime() < deadline) {
      HttpResponse<String> response =
          client.send(
              HttpRequest.newBuilder(URI.create(url)).GET().build(),
              HttpResponse.BodyHandlers.ofString());
      assertEquals(200, response.statusCode(), response.body());
      JsonNode state = json.readTree(response.body());
      if (expected.equals(state.path("status").asText())) return;
      if (state.path("status").asText().equals("FAILED")) fail(state.toString());
      Thread.sleep(100);
    }
    fail("Timeout for " + url);
  }
}
