package ru.teplotrassa.data;

import com.fasterxml.jackson.core.*;
import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.util.TokenBuffer;
import java.io.*;
import java.nio.file.*;
import java.util.*;
import java.util.function.Consumer;
import org.locationtech.jts.io.geojson.GeoJsonReader;
import ru.teplotrassa.engine.Geo;

/** Reads one bounded feature at a time; the file is never held in a String or JsonNode. */
public final class GeoJsonInput {
  private final ObjectMapper mapper;

  public GeoJsonInput(ObjectMapper mapper) {
    this.mapper = mapper;
  }

  public long read(Path file, Consumer<Feature> output) throws IOException {
    return read(file, output, true);
  }

  public long read(Path file, Consumer<Feature> output, boolean strict) throws IOException {
    JsonFactory factory = mapper.getFactory().copy();
    factory.enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION);
    try (JsonParser p = factory.createParser(file.toFile())) {
      if (p.nextToken() != JsonToken.START_OBJECT)
        throw new IllegalArgumentException("Ожидается объект FeatureCollection");
      boolean collection = false, features = false;
      long count = 0;
      while (p.nextToken() != JsonToken.END_OBJECT) {
        if (p.currentToken() != JsonToken.FIELD_NAME)
          throw new IllegalArgumentException("Некорректный GeoJSON");
        String key = p.currentName();
        p.nextToken();
        if (key.equals("type")) {
          collection = "FeatureCollection".equals(p.getText());
        } else if (key.equals("crs")) {
          JsonNode crs;
          long crsStart = p.getTokenLocation().getByteOffset();
          int crsLevel = 0;
          try (TokenBuffer buffer = new TokenBuffer(p)) {
            do {
              JsonToken t = p.currentToken();
              if (t == null) throw new IllegalArgumentException("Незавершённый crs");
              if (t.isStructStart()) crsLevel++;
              if (t.isStructEnd()) crsLevel--;
              if (crsLevel > 16 || p.getCurrentLocation().getByteOffset() - crsStart > 16384)
                throw new IllegalArgumentException("Слишком большой объект crs");
              buffer.copyCurrentEvent(p);
              if (crsLevel > 0) p.nextToken();
            } while (crsLevel > 0);
            crs = mapper.readTree(buffer.asParser(mapper));
          }
          String name = crs.path("properties").path("name").asText();
          if (!Set.of(
                  "urn:ogc:def:crs:OGC:1.3:CRS84",
                  "OGC:CRS84",
                  "EPSG:4326",
                  "urn:ogc:def:crs:EPSG::4326")
              .contains(name))
            throw new IllegalArgumentException(
                "Поддерживается WGS84 / CRS84 с порядком координат долгота, широта; получено: "
                    + name);
        } else if (key.equals("features")) {
          if (p.currentToken() != JsonToken.START_ARRAY)
            throw new IllegalArgumentException("features должен быть массивом");
          features = true;
          while (p.nextToken() != JsonToken.END_ARRAY) {
            if (Thread.currentThread().isInterrupted()) throw new IOException("Импорт прерван");
            if (p.currentToken() != JsonToken.START_OBJECT)
              throw new IllegalArgumentException("Элемент features должен быть объектом");
            long start = p.getTokenLocation().getByteOffset();
            int level = 0;
            try (TokenBuffer buffer = new TokenBuffer(p)) {
              do {
                JsonToken t = p.currentToken();
                if (t == null) throw new IllegalArgumentException("Незавершённый объект GeoJSON");
                if (t.isStructStart()) level++;
                if (t.isStructEnd()) level--;
                if (level > 64
                    || p.getCurrentLocation().getByteOffset() - start > 32L * 1024 * 1024)
                  throw new IllegalArgumentException(
                      "Один объект превышает 32 МиБ или глубину вложенности 64. Разделите сложную"
                          + " геометрию на связанные объекты.");
                buffer.copyCurrentEvent(p);
                if (level > 0) p.nextToken();
              } while (level > 0);
              JsonNode n = mapper.readTree(buffer.asParser(mapper));
              JsonNode props = n.path("properties");
              if (!n.path("type").asText().equals("Feature")
                  || !props.isObject()
                  || !props.path("object_type").isTextual())
                throw new IllegalArgumentException(
                    "Feature должен содержать properties.id (строка или целое число) и"
                        + " properties.object_type (строка)");
              com.fasterxml.jackson.databind.node.ObjectNode values =
                  (com.fasterxml.jackson.databind.node.ObjectNode) props;
              List<String> reserved = new ArrayList<>();
              values
                  .fieldNames()
                  .forEachRemaining(
                      k -> {
                        if (k.startsWith("_tt_")) reserved.add(k);
                      });
              values.remove(reserved);
              for (String field : List.of("id", "oks_id", "upstream_object_id"))
                if (values.has(field)) {
                  JsonNode value = values.get(field);
                  if (!value.isTextual() && !value.isIntegralNumber())
                    throw new IllegalArgumentException(
                        "Объект №"
                            + (count + 1)
                            + ": "
                            + field
                            + " должен быть строкой или целым числом");
                  if (field.equals("id") && value.isIntegralNumber())
                    values.set("_tt_source_id", value.deepCopy());
                  values.put(field, value.asText());
                }
              if (!values.has("id"))
                throw new IllegalArgumentException(
                    "Объект №" + (count + 1) + ": отсутствует properties.id");
              Feature f =
                  new Feature(
                      values.get("id").asText(),
                      values.get("object_type").asText(),
                      values,
                      Geo.project(new GeoJsonReader().read(n.path("geometry").toString())));
              if (!Set.of(
                      "source",
                      "heat_chamber",
                      "oks_connection_point",
                      "heat_network",
                      "oks_future",
                      "oks_existing",
                      "restriction")
                  .contains(f.type))
                throw new IllegalArgumentException(f.id + ": неизвестный object_type " + f.type);
              if (strict) f.validate();
              else if (f.id.isBlank()
                  || f.id.length() > 256
                  || f.geometry.isEmpty()
                  || !f.geometry.isValid())
                throw new IllegalArgumentException(f.id + ": пустой id или невалидная геометрия");
              output.accept(f);
              count++;
            } catch (org.locationtech.jts.io.ParseException e) {
              throw new IllegalArgumentException(
                  "Невозможно прочитать геометрию объекта №" + (count + 1), e);
            }
          }
        } else p.skipChildren();
      }
      if (!collection || !features || count == 0)
        throw new IllegalArgumentException("Ожидается непустой FeatureCollection");
      if (p.nextToken() != null)
        throw new IllegalArgumentException("Лишние данные после FeatureCollection");
      return count;
    }
  }
}
