package ru.teplotrassa.api;

import com.fasterxml.jackson.core.*;
import com.fasterxml.jackson.databind.*;
import java.io.*;
import java.nio.file.*;
import java.security.*;
import java.util.*;
import javax.servlet.http.*;
import org.locationtech.jts.geom.*;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody;
import ru.teplotrassa.data.*;
import ru.teplotrassa.engine.*;

@RestController
@RequestMapping({"/api/v1", "/api/contest"})
public class ContestController {
  private static final long MAX_UPLOAD = 3_000_000_000L;
  private final Workspace w;

  public ContestController(Workspace w) {
    this.w = w;
  }

  private String owner(HttpServletRequest request, HttpServletResponse response) {
    if (request.getCookies() != null)
      for (Cookie c : request.getCookies())
        if (c.getName().equals("tt_workspace") && c.getValue().matches("[a-f0-9]{64}"))
          return c.getValue();
    byte[] bytes = new byte[32];
    new SecureRandom().nextBytes(bytes);
    StringBuilder s = new StringBuilder();
    for (byte b : bytes) s.append(String.format("%02x", b));
    Cookie c = new Cookie("tt_workspace", s.toString());
    c.setPath("/");
    c.setHttpOnly(true);
    c.setSecure(request.isSecure());
    c.setMaxAge(365 * 86400);
    response.addCookie(c);
    return s.toString();
  }

  private Map<String, Object> owned(String table, UUID id, String owner) {
    List<Map<String, Object>> rows =
        w.db.queryForList("SELECT * FROM " + table + " WHERE id=? AND owner=?", id, owner);
    if (rows.isEmpty())
      throw new ResponseStatusException(
          HttpStatus.NOT_FOUND, "Объект не найден в текущем рабочем пространстве");
    return rows.get(0);
  }

  @GetMapping("/health")
  public Map<String, Object> health(HttpServletRequest req, HttpServletResponse res) {
    owner(req, res);
    return Map.of(
        "version",
        BuildInfo.VERSION,
        "buildId",
        BuildInfo.ID,
        "uiPath",
        BuildInfo.UI_PATH,
        "rulesVersion",
        Rules.VERSION,
        "maxUploadBytes",
        MAX_UPLOAD,
        "maxOutputBytes",
        GeoJsonOutput.MAX_BYTES);
  }

  @GetMapping("/rules")
  public Object rules() {
    Map<String, Object> result =
        new LinkedHashMap<>(
            Map.of(
                "version",
                Rules.VERSION,
                "diameters",
                Rules.DN,
                "capacityTph",
                Rules.CAPACITY,
                "limitM",
                Rules.LENGTH,
                "newRubPerM",
                Rules.NEW,
                "reconstructionRubPerM",
                Rules.RECON,
                "widthBasis",
                "pair_of_pipes",
                "defaultRanking",
                "0.7 * C_new / 25000000 + 0.3 * L_new / 100"));
    result.put("rankingProfiles", List.of("contest", "appendix", "protocol"));
    result.put("contestRanking", Map.of(
        "cost", "new construction, new chambers, one connection per existing chamber",
        "length", "new network only",
        "reconstruction", "estimated separately, excluded from score",
        "turnsAndWallPenalties", "search hints and diagnostics, excluded from score"));
    result.put("outerHeightM", Rules.HEIGHT);
    result.put(
        "depth",
        Map.of(
            "ordinaryM",
            DepthPlanner.BASE,
            "minimumM",
            DepthPlanner.MINIMUM,
            "slopeLimit",
            DepthPlanner.SLOPE,
            "defaultMaximumM",
            6,
            "ruleProfiles",
            List.of("appendix", "protocol"),
            "costFactor",
            "1 + 0.1 * max(0, h - 3)",
            "lengthBasis",
            "horizontal_plan"));
    result.put("restrictions", Rules.RESTRICTIONS);
    return result;
  }

  @GetMapping("/datasets/{id}/diagnostics")
  public Object diagnostics(@PathVariable UUID id, HttpServletRequest req, HttpServletResponse res)
      throws IOException {
    Map<String, Object> row = owned("datasets", id, owner(req, res));
    if (!row.get("status").equals("READY"))
      throw new ResponseStatusException(HttpStatus.CONFLICT, "Диагностика ещё не готова");
    return w.importReport(id);
  }

  @GetMapping("/datasets")
  public Object datasets(HttpServletRequest req, HttpServletResponse res) {
    return w.db.queryForList(
        "SELECT id,name,status,created_at,bytes,feature_count,oks_count,error,sha256 FROM"
            + " datasets WHERE owner=? ORDER BY created_at DESC LIMIT 100",
        owner(req, res));
  }

  @PostMapping(
      value = "/datasets",
      consumes = {"application/geo+json", "application/json", "application/octet-stream"})
  @ResponseStatus(HttpStatus.ACCEPTED)
  public Object upload(
      @RequestParam(defaultValue = "dataset.geojson") String name,
      HttpServletRequest req,
      HttpServletResponse res)
      throws Exception {
    String declared = req.getHeader("Content-Length");
    if (declared != null && (!declared.matches("[0-9]+") || declared.length() > 18))
      throw new IllegalArgumentException("Некорректный Content-Length");
    if (req.getContentLengthLong() > MAX_UPLOAD
        || declared != null && Long.parseLong(declared) > MAX_UPLOAD)
      throw new ResponseStatusException(HttpStatus.PAYLOAD_TOO_LARGE, "Файл превышает 3 ГБ");
    String owner = owner(req, res);
    UUID id = UUID.randomUUID();
    String filename = name.replaceAll("[\\p{Cntrl}]", "");
    if (filename.length() > 255) filename = filename.substring(0, 255);
    w.db.update(
        "INSERT INTO datasets(id,name,status,owner) VALUES(?,?,'UPLOADING',?)",
        id,
        filename,
        owner);
    Path file = w.directory(id).resolve("input.geojson");
    long size = 0;
    MessageDigest digest = MessageDigest.getInstance("SHA-256");
    try (InputStream input = req.getInputStream();
        OutputStream output = Files.newOutputStream(file)) {
      byte[] buffer = new byte[65536];
      for (int n; (n = input.read(buffer)) != -1; ) {
        size += n;
        if (size > MAX_UPLOAD)
          throw new ResponseStatusException(HttpStatus.PAYLOAD_TOO_LARGE, "Файл превышает 3 ГБ");
        digest.update(buffer, 0, n);
        output.write(buffer, 0, n);
      }
    } catch (Exception e) {
      Files.deleteIfExists(file);
      w.db.update(
          "UPDATE datasets SET status='FAILED',error=? WHERE id=?", Workspace.message(e), id);
      throw e;
    }
    StringBuilder hash = new StringBuilder();
    for (byte b : digest.digest()) hash.append(String.format("%02x", b));
    w.db.update("UPDATE datasets SET bytes=?,sha256=? WHERE id=?", size, hash.toString(), id);
    w.importFile(id, file);
    return Map.of("id", id);
  }

  @GetMapping("/datasets/{id}")
  public Object dataset(@PathVariable UUID id, HttpServletRequest req, HttpServletResponse res)
      throws IOException {
    Map<String, Object> row = owned("datasets", id, owner(req, res));
    if ("READY".equals(row.get("status"))) row.put("inputPreparation", w.importReport(id));
    res.setHeader("Cache-Control", "no-store");
    return row;
  }

  @PostMapping("/datasets/{id}/jobs")
  @ResponseStatus(HttpStatus.ACCEPTED)
  public Object start(
      @PathVariable UUID id,
      @RequestBody(required = false) Planner.Options options,
      HttpServletRequest req,
      HttpServletResponse res)
      throws Exception {
    String owner = owner(req, res);
    Map<String, Object> dataset = owned("datasets", id, owner);
    if (!dataset.get("status").equals("READY"))
      throw new ResponseStatusException(HttpStatus.CONFLICT, "Набор ещё не прошёл проверку");
    if (options == null) options = new Planner.Options();
    options.validate();
    InputData.validateOptions(w.validatedStore(id), options);
    UUID job = UUID.randomUUID();
    w.db.update(
        "INSERT INTO jobs(id,dataset_id,status,options,owner) VALUES(?,?,'QUEUED',?,?)",
        job,
        id,
        w.json.writeValueAsString(options),
        owner);
    w.calculate(job, id, options);
    return Map.of("id", job);
  }

  @GetMapping("/experience")
  public Object experience(HttpServletRequest req, HttpServletResponse res) throws IOException {
    res.setHeader("Cache-Control", "no-store");
    return w.experience.status(owner(req, res));
  }

  @PostMapping("/experience/train")
  @ResponseStatus(HttpStatus.ACCEPTED)
  public Object trainExperience(
      @RequestParam(defaultValue = "1") int passes, HttpServletRequest req, HttpServletResponse res)
      throws Exception {
    if (passes < 1 || passes > 20) throw new IllegalArgumentException("Число проходов: 1–20");
    String who = owner(req, res);
    if (!Boolean.TRUE.equals(w.experience.status(who).get("canTrainGeneral")))
      throw new ResponseStatusException(
          HttpStatus.CONFLICT,
          "Пока мало разных участков: нужны минимум 2 обучающих и 2 проверочных. Продолжайте"
              + " расчёты с сохранением опыта.");
    JsonNode first = w.experience.territories(who).get(0);
    UUID dataset = UUID.fromString(first.path("dataset").asText());
    owned("datasets", dataset, who);
    UUID job = UUID.randomUUID();
    w.db.update(
        "INSERT INTO jobs(id,dataset_id,status,options,owner) VALUES(?,?,'QUEUED',?,?)",
        job,
        dataset,
        w.json.writeValueAsString(Map.of("jobKind", "general_training", "passes", passes)),
        who);
    w.trainGeneral(job, who, passes);
    return Map.of("id", job);
  }

  @GetMapping("/jobs")
  public Object jobs(HttpServletRequest req, HttpServletResponse res) {
    return w.db.queryForList(
        "SELECT id,dataset_id,status,created_at,updated_at,progress,message FROM jobs WHERE"
            + " owner=? ORDER BY created_at DESC LIMIT 100",
        owner(req, res));
  }

  @GetMapping("/jobs/{id}")
  public Object job(@PathVariable UUID id, HttpServletRequest req, HttpServletResponse res)
      throws Exception {
    Map<String, Object> row = owned("jobs", id, owner(req, res));
    row.remove("owner");
    if (row.get("summary") != null)
      row.put("summary", w.json.readTree(row.get("summary").toString()));
    return row;
  }

  @PostMapping("/jobs/{id}/cancel")
  public Object cancel(@PathVariable UUID id, HttpServletRequest req, HttpServletResponse res) {
    owned("jobs", id, owner(req, res));
    w.cancel(id);
    return Map.of("cancelled", true);
  }

  @GetMapping("/jobs/{id}/result")
  public ResponseEntity<StreamingResponseBody> result(
      @PathVariable UUID id, HttpServletRequest req, HttpServletResponse res) throws IOException {
    return download(id, "result.geojson", "application/geo+json", req, res);
  }

  @GetMapping("/jobs/{id}/report")
  public ResponseEntity<StreamingResponseBody> report(
      @PathVariable UUID id, HttpServletRequest req, HttpServletResponse res) throws IOException {
    return download(id, "report.json", "application/json", req, res);
  }

  private ResponseEntity<StreamingResponseBody> download(
      UUID id, String filename, String mime, HttpServletRequest req, HttpServletResponse res)
      throws IOException {
    Map<String, Object> row = owned("jobs", id, owner(req, res));
    if (!row.get("status").equals("DONE"))
      throw new ResponseStatusException(HttpStatus.CONFLICT, "Результат ещё не готов");
    Path file = w.directory(id).resolve(filename);
    if (!Files.isRegularFile(file))
      throw new ResponseStatusException(HttpStatus.NOT_FOUND, "У этого задания нет такого файла");
    return ResponseEntity.ok()
        .contentType(MediaType.parseMediaType(mime))
        .contentLength(Files.size(file))
        .header(
            "Content-Disposition",
            "attachment; filename=\"teplotrassa_"
                + BuildInfo.VERSION.split("-")[0]
                + "_"
                + id
                + "_"
                + filename
                + "\"")
        .body(
            out -> {
              try (InputStream in = Files.newInputStream(file)) {
                in.transferTo(out);
              }
            });
  }

  @GetMapping("/datasets/{id}/view")
  public ResponseEntity<StreamingResponseBody> datasetView(
      @PathVariable UUID id,
      @RequestParam(required = false) String bbox,
      HttpServletRequest req,
      HttpServletResponse res) {
    owned("datasets", id, owner(req, res));
    Envelope box;
    if (bbox == null) {
      Map<String, Object> b =
          w.db.queryForMap(
              "SELECT min(minx) x0,min(miny) y0,max(maxx) x1,max(maxy) y1 FROM"
                  + " features WHERE dataset_id=?",
              id);
      if (b.get("x0") == null)
        throw new ResponseStatusException(HttpStatus.CONFLICT, "Набор ещё пуст");
      box =
          new Envelope(
              ((Number) b.get("x0")).doubleValue(),
              ((Number) b.get("x1")).doubleValue(),
              ((Number) b.get("y0")).doubleValue(),
              ((Number) b.get("y1")).doubleValue());
    } else {
      String[] s = bbox.split(",");
      if (s.length != 4) throw new IllegalArgumentException("bbox: west,south,east,north");
      Coordinate a = Geo.xy(Double.parseDouble(s[0]), Double.parseDouble(s[1])),
          b = Geo.xy(Double.parseDouble(s[2]), Double.parseDouble(s[3]));
      box = new Envelope(a, b);
    }
    final Envelope window = box;
    return ResponseEntity.ok()
        .contentType(MediaType.parseMediaType("application/geo+json"))
        .body(
            out -> {
              JdbcFeatureStore store = new JdbcFeatureStore(w.db, id, w.json);
              List<String> rows = store.viewIds(window);
              try (JsonGenerator g = w.json.getFactory().createGenerator(out)) {
                g.writeStartObject();
                g.writeStringField("type", "FeatureCollection");
                Coordinate a = Geo.ll(new Coordinate(window.getMinX(), window.getMinY())),
                    b = Geo.ll(new Coordinate(window.getMaxX(), window.getMaxY()));
                g.writeObjectField("bbox", new double[] {a.x, a.y, b.x, b.y});
                g.writeBooleanField("truncated", rows.size() > 5000);
                g.writeArrayFieldStart("features");
                for (int i = 0; i < Math.min(5000, rows.size()); i++) {
                  Feature f = store.get(rows.get(i));
                  GeoJsonOutput.feature(
                      g, f.geometry, w.json.convertValue(f.properties, Map.class), false, 0, 0);
                }
                g.writeEndArray();
                g.writeEndObject();
              }
            });
  }

  @GetMapping("/jobs/{id}/view")
  public ResponseEntity<StreamingResponseBody> resultView(
      @PathVariable UUID id,
      @RequestParam(defaultValue = "v1") String variant,
      HttpServletRequest req,
      HttpServletResponse res)
      throws IOException {
    if (!variant.matches("v[1-3]")) throw new IllegalArgumentException("Неизвестный вариант");
    Map<String, Object> row = owned("jobs", id, owner(req, res));
    if (!row.get("status").equals("DONE"))
      throw new ResponseStatusException(HttpStatus.CONFLICT, "Результат ещё не готов");
    Path file = w.directory(id).resolve("result.geojson");
    return ResponseEntity.ok()
        .contentType(MediaType.parseMediaType("application/geo+json"))
        .body(
            out -> {
              try (JsonParser p = w.json.getFactory().createParser(file.toFile());
                  JsonGenerator g = w.json.getFactory().createGenerator(out)) {
                g.writeStartObject();
                g.writeStringField("type", "FeatureCollection");
                g.writeArrayFieldStart("features");
                int count = 0;
                ObjectReader featureReader =
                    w.json
                        .readerFor(JsonNode.class)
                        .without(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);
                while (p.nextToken() != null)
                  if (p.currentToken() == JsonToken.FIELD_NAME
                      && p.currentName().equals("features")) {
                    p.nextToken();
                    while (p.nextToken() != JsonToken.END_ARRAY) {
                      JsonNode f = featureReader.readValue(p);
                      if (f.path("properties").path("variant_id").asText().equals(variant)
                          && !f.path("geometry").isNull()) {
                        if (++count <= 10000) g.writeTree(f);
                      }
                    }
                    break;
                  }
                g.writeEndArray();
                g.writeBooleanField("truncated", count > 10000);
                g.writeEndObject();
              }
            });
  }

  @ExceptionHandler(ResponseStatusException.class)
  public ResponseEntity<Object> statusError(ResponseStatusException e) {
    return ResponseEntity.status(e.getStatus())
        .body(
            Map.of(
                "error", e.getReason() == null ? e.getStatus().getReasonPhrase() : e.getReason()));
  }

  @ExceptionHandler(IllegalArgumentException.class)
  @ResponseStatus(HttpStatus.BAD_REQUEST)
  public Object bad(IllegalArgumentException e) {
    return Map.of("error", Workspace.message(e));
  }
}
