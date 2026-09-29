package ru.teplotrassa.data;

import java.util.*;
import org.locationtech.jts.geom.Envelope;

public interface FeatureStore {
  default List<Map<String, Object>> diagnostics() {
    return InputDiagnostics.inspect(this);
  }

  Feature get(String id);

  Iterable<String> ids(String type);

  List<Feature> near(Envelope box);

  default List<Feature> all(String type) {
    List<Feature> out = new ArrayList<>();
    for (String id : ids(type)) out.add(get(id));
    return out;
  }

  default List<Feature> near(Envelope box, String type) {
    List<Feature> out = new ArrayList<>();
    for (Feature f : near(box)) if (f.type.equals(type)) out.add(f);
    return out;
  }
}
