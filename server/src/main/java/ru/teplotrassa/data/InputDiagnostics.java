package ru.teplotrassa.data;

import java.util.*;
import org.locationtech.jts.operation.valid.IsValidOp;
import ru.teplotrassa.engine.*;

/** Data findings, independent of the chosen scenario or optimizer. */
public final class InputDiagnostics {
  public static List<Map<String, Object>> inspect(FeatureStore store) {
    List<Map<String, Object>> out = new ArrayList<>();
    for (String type :
        List.of(
            "source",
            "heat_network",
            "heat_chamber",
            "oks_connection_point",
            "oks_existing",
            "oks_future",
            "restriction"))
      for (String id : store.ids(type)) {
        Feature f = store.get(id);
        try {
          f.validate();
        } catch (IllegalArgumentException e) {
          if (!e.getMessage().contains("upstream_object_id")
              && !e.getMessage().contains("diameter должен"))
            add(out, id, "INVALID_FEATURE", "error", e.getMessage());
        }
        if (!f.geometry.isValid())
          add(
              out,
              id,
              "INVALID_GEOMETRY",
              "error",
              String.valueOf(new IsValidOp(f.geometry).getValidationError()));
        if (type.equals("heat_network") || type.equals("heat_chamber")) {
          if (f.text("upstream_object_id").isBlank())
            add(out, id, "MISSING_UPSTREAM", "info", "для конкурсного расчёта upstream_object_id не требуется");
          else if (store.get(f.text("upstream_object_id")) == null)
            add(
                out,
                id,
                "UNKNOWN_UPSTREAM",
                "info",
                "upstream_object_id ссылается на отсутствующий объект");
          if (!f.properties.path("diameter").isIntegralNumber())
            add(out, id, "MISSING_DIAMETER", type.equals("heat_network") ? "error" : "info",
                type.equals("heat_network") ? "не задан обязательный diameter сети"
                    : "диаметр существующей камеры необязателен для конкурсного расчёта");
        }
        if (type.equals("heat_network") && !f.properties.path("flow_tph").isNumber())
          add(
              out,
              id,
              "MISSING_EXISTING_FLOW",
              "info",
              "текущий flow_tph не нужен для конкурсного расчёта; инженерная оценка требует допущения");
        if (type.equals("restriction")
            && !Rules.RESTRICTIONS.containsKey(f.restriction()))
          add(
              out,
              id,
              "UNSPECIFIED_RESTRICTION",
              "error",
              "для "
                  + f.restriction()
                  + " нет правила в приложении; сценарный режим допускает консервативный запрет");
      }
    if (store.all("source").size() != 1)
      add(out, "dataset", "SOURCE_COUNT", "error", "нужен ровно один источник");
    try {
      if (InputData.demands(store).isEmpty())
        add(out, "dataset", "NO_DEMAND", "error", "нет точек подключения");
    } catch (IllegalArgumentException e) {
      add(out, "dataset", "INVALID_DEMAND", "error", e.getMessage());
    }
    return out;
  }

  private static void add(
      List<Map<String, Object>> out, String id, String code, String severity, String message) {
    if (out.size() < 10000)
      out.add(Map.of("object_id", id, "code", code, "severity", severity, "message", message));
    else if (out.size() == 10000)
      out.add(
          Map.of(
              "object_id",
              "dataset",
              "code",
              "DIAGNOSTICS_TRUNCATED",
              "severity",
              "error",
              "message",
              "Показаны первые 10000 замечаний"));
  }

  private InputDiagnostics() {}
}
