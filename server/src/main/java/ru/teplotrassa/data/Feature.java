package ru.teplotrassa.data;

import com.fasterxml.jackson.databind.JsonNode;
import org.locationtech.jts.geom.Geometry;
import ru.teplotrassa.engine.Rules;

public final class Feature {
  public final String id, type;
  public final JsonNode properties;
  public final Geometry geometry;

  public Feature(String id, String type, JsonNode properties, Geometry geometry) {
    this.id = id;
    this.type = type;
    this.properties = properties;
    this.geometry = geometry;
  }

  public String text(String key) {
    return properties.path(key).asText("");
  }

  public boolean compact() {
    return properties.path("_tt_compact").asBoolean();
  }

  public double flow() {
    if (properties.path("_tt_missing_flow").asBoolean() && !properties.path("flow_tph").isNumber())
      throw new IllegalArgumentException(
          id + ": текущий расход сети неизвестен; задайте сценарную загрузку");
    return properties.path("flow_tph").asDouble();
  }

  public int dn() {
    return properties.path("diameter").asInt();
  }

  public String restriction() {
    return type.equals("heat_network") ? type : text("restriction_type");
  }

  public void validate() {
    if (id.isBlank() || id.length() > 256)
      throw new IllegalArgumentException("Пустой или слишком длинный id объекта");
    if (geometry.isEmpty() || !geometry.isValid())
      throw new IllegalArgumentException(id + ": пустая или невалидная геометрия");
    String g = geometry.getGeometryType();
    switch (type) {
      case "source":
      case "heat_chamber":
      case "oks_connection_point":
        require(g.equals("Point"), "ожидается Point");
        break;
      case "heat_network":
        require(
            g.equals("LineString") && geometry.getLength() > 0, "ожидается непустой LineString");
        break;
      case "oks_future":
      case "oks_existing":
        require(g.equals("Polygon") || g.equals("MultiPolygon"), "ожидается Polygon/MultiPolygon");
        break;
      case "restriction":
        Rules.Restriction r = Rules.RESTRICTIONS.get(restriction());
        require(
            r != null && !restriction().equals("heat_network"),
            "неизвестный restriction_type: " + restriction());
        require(r.polygon ? (g.equals("Polygon") || g.equals("MultiPolygon"))
            : (g.equals("LineString") || g.equals("MultiLineString")),
            "геометрия не соответствует типу ограничения");
        break;
      default:
        throw new IllegalArgumentException(id + ": неизвестный object_type " + type);
    }
    if (type.equals("heat_network") || type.equals("heat_chamber")
        && properties.has("diameter")) {
      require(properties.path("diameter").isIntegralNumber(), "diameter должен быть целым");
      Rules.index(dn());
    }
    if (type.equals("heat_network"))
      require(properties.path("diameter").isIntegralNumber(), "diameter должен быть целым");
    if (type.equals("heat_network") && properties.path("flow_tph").isNumber()
        || type.equals("oks_future"))
      require(
          properties.path("flow_tph").isNumber() && Double.isFinite(flow()) && flow() >= 0,
          "неверный flow_tph");
    if (type.equals("oks_future"))
      require(
          flow() > 0
              && (compact() && properties.path("heat_load").isNull()
                  || properties.path("heat_load").isNumber()
                      && Double.isFinite(properties.path("heat_load").asDouble())
                      && properties.path("heat_load").asDouble() >= 0),
          "нужны положительный flow_tph и неотрицательный heat_load");
    if (type.equals("oks_connection_point"))
      require(properties.path("flow_tph").isNumber() && Double.isFinite(flow()) && flow() > 0,
          "нужен положительный flow_tph точки подключения");
  }

  private void require(boolean ok, String message) {
    if (!ok) throw new IllegalArgumentException(id + ": " + message);
  }
}
