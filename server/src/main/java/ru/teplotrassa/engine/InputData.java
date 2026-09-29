package ru.teplotrassa.engine;

import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.*;
import org.locationtech.jts.geom.*;
import ru.teplotrassa.data.*;

/** Connection points identify demands; the wall crossing is selected during routing. */
public final class InputData {
  public static Coordinate portal(Feature entry) {
    return entry.geometry.getCoordinate();
  }

  public static String buildingId(Feature demand) {
    return demand.text("oks_id").isBlank() ? demand.id : demand.text("oks_id");
  }

  public static List<Feature> demands(FeatureStore store) {
    List<Feature> out = new ArrayList<>();
    for (String id : store.ids("oks_connection_point")) {
      Feature entry = store.get(id);
      String key = entry.id;
      ObjectNode p = entry.properties.deepCopy();
      if (!entry.text("oks_id").isBlank()) p.put("oks_id", entry.text("oks_id"));
      p.put("_tt_entry_id", entry.id);
      double flow = p.path("flow_tph").asDouble(Double.NaN);
      if (!Double.isFinite(flow) || flow <= 0)
        throw new IllegalArgumentException(id + ": нужен положительный flow_tph");
      out.add(new Feature(key, "demand", p, entry.geometry));
    }
    return out;
  }

  public static void validateOptions(FeatureStore store, Planner.Options options) {
    options.validate();
    List<Map<String, Object>> issues = store.diagnostics();
    if (options.dataMode.equals("strict")) {
      List<String> errors = new ArrayList<>();
      for (Map<String, Object> issue : issues)
        if (issue.get("severity").equals("error"))
          errors.add(issue.get("object_id") + ": " + issue.get("message"));
      if (!errors.isEmpty())
        throw new IllegalArgumentException(
            "Входные данные неполны: "
                + String.join("; ", errors.subList(0, Math.min(12, errors.size())))
                + ". Полный перечень — в диагностике набора.");
    } else {
      for (Map<String, Object> issue : issues) {
        String code = issue.get("code").toString();
        if (Set.of(
                "INVALID_GEOMETRY",
                "INVALID_DEMAND",
                "NO_DEMAND",
                "SOURCE_COUNT",
                "UNKNOWN_UPSTREAM",
                "DIAGNOSTICS_TRUNCATED")
            .contains(code)) throw new IllegalArgumentException(issue.get("message").toString());
        if (code.equals("INVALID_FEATURE")) {
          Feature f = store.get(issue.get("object_id").toString());
          boolean absentFlow =
              f != null
                  && f.type.equals("heat_network")
                  && !f.properties.path("flow_tph").isNumber();
          boolean unknownRule =
              f != null
                  && f.type.equals("restriction")
                  && !Rules.RESTRICTIONS.containsKey(f.restriction())
                  && (f.geometry instanceof Polygon || f.geometry instanceof MultiPolygon);
          if (!absentFlow && !unknownRule)
            throw new IllegalArgumentException(issue.get("message").toString());
        }
      }
      boolean missing = false;
      for (String id : store.ids("heat_network"))
        missing |= !store.get(id).properties.path("flow_tph").isNumber();
      if (missing && options.existingLoadPercent == null
          && !options.rankingProfile.equals("contest"))
        throw new IllegalArgumentException(
            "Для сценарного расчёта задайте existingLoadPercent, % пропускной способности. Это"
                + " допущение, а не исходный расход.");
    }
  }

  public static FeatureStore scenario(FeatureStore base, Planner.Options options) {
    validateOptions(base, options);
    Map<String, Feature> changes = new HashMap<>();
    FeatureStore overlay =
        new FeatureStore() {
          private Feature adapt(Feature f) {
            if (f == null) return null;
            f = changes.getOrDefault(f.id, f);
            if (options.dataMode.equals("scenario")
                && f.type.equals("restriction")
                && !Rules.RESTRICTIONS.containsKey(f.restriction())) {
              ObjectNode p = f.properties.deepCopy();
              p.put("_tt_original_restriction", f.restriction());
              p.put("restriction_type", "prohibited_site");
              return new Feature(f.id, f.type, p, f.geometry);
            }
            return f;
          }

          public Feature get(String id) {
            return adapt(base.get(id));
          }

          public Iterable<String> ids(String type) {
            return base.ids(type);
          }

          public List<Feature> near(Envelope box) {
            List<Feature> out = new ArrayList<>();
            for (Feature f : base.near(box)) out.add(adapt(f));
            return out;
          }
        };
    if (options.dataMode.equals("scenario") && !options.rankingProfile.equals("contest")) {
      new DatasetNormalizer(overlay, f -> changes.put(f.id, f)).normalize();

      for (String id : overlay.ids("heat_network")) {
        Feature f = overlay.get(id);
        if (!f.properties.path("flow_tph").isNumber()) {
          ObjectNode p = f.properties.deepCopy();
          p.put(
              "flow_tph", Rules.CAPACITY[Rules.index(f.dn())] * options.existingLoadPercent / 100);
          p.put("_tt_flow_basis", "scenario_capacity_percent");
          changes.put(id, new Feature(id, f.type, p, f.geometry));
        }
      }
    }
    // The source point remains the logical indoor terminal. The exterior wall
    // is selected with each route to the growing network.
    if (options.rankingProfile.equals("contest")) InputValidator.validateContest(overlay);
    else InputValidator.validate(overlay);
    return overlay;
  }

  public static List<Map<String, Object>> scenarioChanges(FeatureStore store) {
    List<Map<String, Object>> changes = new ArrayList<>();
    for (String type : List.of("heat_network", "heat_chamber"))
      for (String id : store.ids(type)) {
        Feature f = store.get(id);
        if (f.compact() || !f.text("_tt_flow_basis").isBlank()) {
          Map<String, Object> item = new LinkedHashMap<>();
          item.put("id", id);
          item.put("upstream_object_id", f.text("upstream_object_id"));
          item.put("diameter", f.dn());
          item.put("flow_tph", f.type.equals("heat_network") ? f.flow() : null);
          item.put(
              "flow_basis",
              f.text("_tt_flow_basis").isBlank() ? "input" : f.text("_tt_flow_basis"));
          item.put(
              "topology_inferred",
              f.properties.path("_tt_topology_inferred").asBoolean()
                  || f.type.equals("heat_chamber") && f.compact());
          changes.add(item);
        }
      }
    return changes;
  }

  private InputData() {}
}
