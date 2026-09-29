package ru.teplotrassa.engine;

import com.fasterxml.jackson.core.*;
import com.fasterxml.jackson.databind.*;
import java.io.*;
import java.nio.file.*;
import java.util.*;
import org.locationtech.jts.geom.*;
import ru.teplotrassa.data.*;

public final class GeoJsonOutput {
  public static final long MAX_BYTES = 500_000_000L;

  public static void write(
      Path file, Planner.Result result, FeatureStore store, ObjectMapper mapper)
      throws IOException {
    Path temporary = file.resolveSibling(file.getFileName() + ".partial");
    try (OutputStream raw = Files.newOutputStream(temporary);
        OutputStream bounded =
            new FilterOutputStream(raw) {
              long bytes;

              private void count(int size) throws IOException {
                bytes += size;
                if (bytes > MAX_BYTES) throw new IOException("Результат превышает 500 МБ");
              }

              public void write(int b) throws IOException {
                count(1);
                out.write(b);
              }

              public void write(byte[] b, int o, int l) throws IOException {
                count(l);
                out.write(b, o, l);
              }
            };
        JsonGenerator g = mapper.getFactory().createGenerator(bounded)) {
      g.writeStartObject();
      g.writeStringField("type", "FeatureCollection");
      g.writeObjectField("metadata", result.metadata);
      g.writeArrayFieldStart("features");
      for (int i = 0; i < result.variants.size(); i++)
        writeVariant(g, result.variants.get(i), "v" + (i + 1), store);
      g.writeEndArray();
      g.writeEndObject();
    } catch (Exception e) {
      Files.deleteIfExists(temporary);
      throw e;
    }
    Files.move(
        temporary, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
  }

  private static Map<String, Object> props(Object id, String type, String variant) {
    Map<String, Object> p = new LinkedHashMap<>();
    p.put("id", id);
    p.put("object_type", type);
    p.put("variant_id", variant);
    return p;
  }

  private static Object originalId(Feature f) {
    JsonNode id = f.properties.path("_tt_source_id");
    return id.isIntegralNumber() ? id.numberValue() : f.id;
  }

  private static Object nodeId(Network.Node n, String prefix, FeatureStore store) {
    if (n.type.equals("oks")) return originalId(store.get(n.entryId));
    if (n.type.equals("tie") && store.get(n.existingId).type.equals("heat_chamber"))
      return originalId(store.get(n.existingId));
    return prefix + n.id;
  }

  private static void writeVariant(
      JsonGenerator g, Evaluation v, String variant, FeatureStore store) throws IOException {
    Network network = v.network;
    boolean depth =
        network.edges.values().stream()
            .anyMatch(e -> !e.sections.isEmpty() && Double.isFinite(e.sections.get(0).h0));
    String chosen = "tt:" + variant + ":";
    for (; ; ) {
      boolean collision = false;
      for (String type : List.of("source", "heat_network", "heat_chamber",
          "oks_connection_point", "restriction", "oks_future", "oks_existing"))
        for (String id : store.ids(type))
          if (id.startsWith(chosen)) collision = true;
      if (!collision) break;
      chosen = "_" + chosen;
    }
    final String prefix = chosen;
    for (Network.Node node : network.nodes.values()) {
      // Original connection points are already present in the input dataset.
      if (node.type.equals("oks")) continue;
      if (node.type.equals("tie")
          && store.get(node.existingId).type.equals("heat_chamber")) continue;
      int dn = v.reconstruction.newChamberDiameter(node, network, store);
      Map<String, Object> p =
          props(prefix + node.id, "heat_chamber", variant);
      p.put("diameter", dn);
      p.put("cost", Rules.chamber(dn));
      feature(g, Geo.point(node.point), p, depth, node.depth, node.depth);
    }
    for (Network.Edge e : network.edges.values()) {
      Object previous = nodeId(network.nodes.get(e.start), prefix, store);
      for (int i = 0; i < e.sections.size(); i++) {
        DepthPlanner.Section s = e.sections.get(i);
        Object end =
            i == e.sections.size() - 1
                ? nodeId(network.nodes.get(e.end), prefix, store)
                : prefix + e.id + ":node:" + i;
        if (i < e.sections.size() - 1)
          feature(
              g,
              Geo.point(Geo.at(e.geometry, s.to)),
              props(end, "technical_node", variant),
              Double.isFinite(s.h1),
              s.h1,
              s.h1);
        Map<String, Object> p = props(prefix + e.id + ":" + i, "heat_network", variant);
        p.put("start_node_id", previous);
        p.put("end_node_id", end);
        p.put("flow_tph", e.flow);
        p.put("diameter", e.dn);
        p.put("length", s.to - s.from);
        p.put("laying_method", s.method);
        p.put("depth_start", Double.isFinite(s.h0) ? s.h0 : null);
        p.put("depth_end", Double.isFinite(s.h1) ? s.h1 : null);
        p.put(
            "cost",
            (s.to - s.from)
                * Rules.NEW[Rules.index(e.dn)]
                * s.factor
                * (Double.isFinite(s.h0)
                    ? (Rules.depthFactor(s.h0) + Rules.depthFactor(s.h1)) / 2
                    : 1));
        feature(g, Geo.part(e.geometry, s.from, s.to), p, Double.isFinite(s.h0), s.h0, s.h1);
        previous = end;
      }
    }
    Map<String, Object> summary = props(prefix + "summary", "variant_summary", variant);
    summary.putAll(v.summary);
    feature(g, null, summary, false, 0, 0);
  }

  public static void feature(
      JsonGenerator g,
      Geometry geometry,
      Map<String, ?> properties,
      boolean depth,
      double h0,
      double h1)
      throws IOException {
    g.writeStartObject();
    g.writeStringField("type", "Feature");
    g.writeFieldName("geometry");
    if (geometry == null) g.writeNull();
    else {
      g.writeStartObject();
      g.writeStringField("type", geometry.getGeometryType());
      g.writeFieldName("coordinates");
      if (geometry instanceof Point) coordinate(g, geometry.getCoordinate(), depth, h0);
      else if (geometry instanceof LineString) {
        g.writeStartArray();
        Coordinate[] c = geometry.getCoordinates();
        double at = 0, length = geometry.getLength();
        for (int i = 0; i < c.length; i++) {
          if (i > 0) at += c[i].distance(c[i - 1]);
          coordinate(g, c[i], depth, h0 + (h1 - h0) * (length == 0 ? 0 : at / length));
        }
        g.writeEndArray();
      } else if (geometry instanceof Polygon) polygon(g, (Polygon) geometry);
      else if (geometry instanceof MultiPolygon) {
        g.writeStartArray();
        for (int i = 0; i < geometry.getNumGeometries(); i++)
          polygon(g, (Polygon) geometry.getGeometryN(i));
        g.writeEndArray();
      } else
        throw new IOException("Не поддержана выходная геометрия " + geometry.getGeometryType());
      g.writeEndObject();
    }
    g.writeObjectField("properties", properties);
    g.writeEndObject();
  }

  private static void polygon(JsonGenerator g, Polygon polygon) throws IOException {
    g.writeStartArray();
    ring(g, polygon.getExteriorRing());
    for (int i = 0; i < polygon.getNumInteriorRing(); i++) ring(g, polygon.getInteriorRingN(i));
    g.writeEndArray();
  }

  private static void ring(JsonGenerator g, LineString ring) throws IOException {
    g.writeStartArray();
    for (Coordinate c : ring.getCoordinates()) coordinate(g, c, false, 0);
    g.writeEndArray();
  }

  private static void coordinate(JsonGenerator g, Coordinate c, boolean depth, double h)
      throws IOException {
    Coordinate ll = Geo.ll(c);
    g.writeStartArray();
    g.writeNumber(ll.x);
    g.writeNumber(ll.y);
    if (depth) g.writeNumber(-h);
    g.writeEndArray();
  }

  private GeoJsonOutput() {}
}
