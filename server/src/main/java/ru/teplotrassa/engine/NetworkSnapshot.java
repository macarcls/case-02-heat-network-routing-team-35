package ru.teplotrassa.engine;

import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.*;
import org.locationtech.jts.geom.*;
import org.locationtech.jts.io.*;

/** Only topology/geometry is stored. Flow, DN, depth, cost and feasibility are recomputed. */
public final class NetworkSnapshot {
  public static ObjectNode write(Network network, ObjectMapper json) {
    ObjectNode out = json.createObjectNode();
    ArrayNode nodes = out.putArray("nodes"), edges = out.putArray("edges");
    for (Network.Node n : network.nodes.values()) {
      ObjectNode v = nodes.addObject();
      v.put("id", n.id);
      v.put("x", n.point.x);
      v.put("y", n.point.y);
      v.put("type", n.type);
      v.put("existingId", n.existingId);
      v.put("entryId", n.entryId);
    }
    for (Network.Edge e : network.edges.values()) {
      ObjectNode v = edges.addObject();
      v.put("id", e.id);
      v.put("start", e.start);
      v.put("end", e.end);
      v.put("wkt", e.geometry.toText());
    }
    return out;
  }

  public static Network read(JsonNode n) {
    Network net = new Network();
    WKTReader reader = new WKTReader();
    if (!n.path("nodes").isArray() || !n.path("edges").isArray())
      throw new IllegalArgumentException("Network snapshot");
    for (JsonNode v : n.path("nodes")) {
      double x = v.path("x").asDouble(Double.NaN), y = v.path("y").asDouble(Double.NaN);
      if (!Double.isFinite(x) || !Double.isFinite(y))
        throw new IllegalArgumentException("Coordinates");
      String id = v.path("id").asText();
      Network.Node node = new Network.Node(id, new Coordinate(x, y), v.path("type").asText());
      node.existingId = v.path("existingId").isTextual() ? v.path("existingId").asText() : null;
      node.entryId = v.path("entryId").isTextual() ? v.path("entryId").asText() : null;
      if (id.isEmpty() || net.nodes.put(id, node) != null)
        throw new IllegalArgumentException("Duplicate node");
    }
    for (JsonNode v : n.path("edges")) {
      try {
        Geometry g = reader.read(v.path("wkt").asText());
        if (!(g instanceof LineString) || g.isEmpty() || !g.isValid())
          throw new IllegalArgumentException("LineString");
        for (Coordinate c : g.getCoordinates())
          if (!Double.isFinite(c.x) || !Double.isFinite(c.y))
            throw new IllegalArgumentException("Coordinates");
        String id = v.path("id").asText();
        Network.Edge edge =
            new Network.Edge(id, v.path("start").asText(), v.path("end").asText(), (LineString) g);
        if (id.isEmpty() || net.edges.put(id, edge) != null)
          throw new IllegalArgumentException("Duplicate edge");
      } catch (ParseException e) {
        throw new IllegalArgumentException("Network geometry", e);
      }
    }
    return net;
  }

  private NetworkSnapshot() {}
}
