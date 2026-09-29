package ru.teplotrassa.data;

import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.*;
import java.util.function.Consumer;
import org.locationtech.jts.geom.*;
import org.locationtech.jts.index.strtree.STRtree;
import ru.teplotrassa.engine.*;

/** Adapts the GIS export without inventing loads or altering source coordinates. */
public final class DatasetNormalizer {
  private final FeatureStore store;
  private final Consumer<Feature> write;
  private final Map<String, Object> report = new LinkedHashMap<>();
  private final List<String> notes = new ArrayList<>();

  public DatasetNormalizer(FeatureStore store, Consumer<Feature> write) {
    this.store = store;
    this.write = write;
  }

  private ObjectNode props(Feature f) {
    ObjectNode p = f.properties.deepCopy();
    if (!p.has("_tt_original_properties"))
      p.set("_tt_original_properties", f.properties.deepCopy());
    p.put("_tt_compact", true);
    return p;
  }

  private void save(Feature f, String type, ObjectNode p) {
    p.put("object_type", type);
    write.accept(new Feature(f.id, type, p, f.geometry));
  }

  public Map<String, Object> normalize() {
    boolean missing = false;
    for (String type : List.of("heat_network", "heat_chamber"))
      for (String id : store.ids(type))
        missing |= store.get(id).text("upstream_object_id").isBlank();
    if (missing) inferTopology();
    for (String id : store.ids("heat_chamber")) {
      Feature f = store.get(id);
      if (!f.properties.path("diameter").isIntegralNumber()) {
        int dn = 0;
        Envelope box = new Envelope(f.geometry.getEnvelopeInternal());
        box.expandBy(InputValidator.TOLERANCE);
        for (Feature line : store.near(box, "heat_network"))
          if (line.geometry.getBoundary().distance(f.geometry) <= InputValidator.TOLERANCE)
            dn = Math.max(dn, line.dn());
        if (dn == 0)
          throw new IllegalArgumentException(id + ": нельзя восстановить диаметр камеры");
        ObjectNode p = props(f);
        p.put("diameter", dn);
        save(f, f.type, p);
      }
    }
    report.put("topologyInferred", missing);
    report.put("notes", notes);
    return report;
  }

  private static final class Vertex {
    int root;
    Coordinate point;
    List<String> objects = new ArrayList<>();
    List<Integer> edges = new ArrayList<>();

    Vertex(int i, Coordinate c) {
      root = i;
      point = c;
    }
  }

  private static int root(List<Vertex> nodes, int i) {
    while (nodes.get(i).root != i) {
      nodes.get(i).root = nodes.get(nodes.get(i).root).root;
      i = nodes.get(i).root;
    }
    return i;
  }

  private void inferTopology() {
    List<Feature> lines = store.all("heat_network"),
        chambers = store.all("heat_chamber"),
        sources = store.all("source");
    if (sources.size() != 1) throw new IllegalArgumentException("В наборе нужен один source");
    if (lines.size() > 50000)
      throw new IllegalArgumentException(
          "Для более 50000 участков задайте явные upstream_object_id в полном формате");
    List<Vertex> nodes = new ArrayList<>();
    STRtree index = new STRtree();
    List<int[]> ends = new ArrayList<>();
    for (Feature f : lines) {
      if (!(f.geometry instanceof LineString))
        throw new IllegalArgumentException(f.id + ": сеть должна быть LineString");
      Rules.index(f.dn());
      Coordinate[] c = f.geometry.getCoordinates();
      int a = nodes.size();
      nodes.add(new Vertex(a, c[0]));
      nodes.add(new Vertex(a + 1, c[c.length - 1]));
      ends.add(new int[] {a, a + 1});
    }
    Map<String, Integer> points = new LinkedHashMap<>();
    for (Feature f : concat(chambers, sources)) {
      if (!(f.geometry instanceof Point))
        throw new IllegalArgumentException(f.id + ": узел должен быть Point");
      int i = nodes.size();
      nodes.add(new Vertex(i, f.geometry.getCoordinate()));
      points.put(f.id, i);
    }
    for (int i = 0; i < nodes.size(); i++) index.insert(new Envelope(nodes.get(i).point), i);
    index.build();
    for (int i = 0; i < nodes.size(); i++) {
      Envelope area = new Envelope(nodes.get(i).point);
      area.expandBy(InputValidator.TOLERANCE);
      for (Object found : index.query(area)) {
        int j = (Integer) found;
        if (j > i && nodes.get(i).point.distance(nodes.get(j).point) <= InputValidator.TOLERANCE)
          nodes.get(root(nodes, j)).root = root(nodes, i);
      }
    }
    Map<Integer, List<Coordinate>> members = new HashMap<>();
    for (int i = 0; i < nodes.size(); i++)
      members.computeIfAbsent(root(nodes, i), k -> new ArrayList<>()).add(nodes.get(i).point);
    for (List<Coordinate> cluster : members.values())
      for (Coordinate a : cluster)
        for (Coordinate b : cluster)
          if (a.distance(b) > InputValidator.TOLERANCE + 1e-8)
            throw new IllegalArgumentException(
                "Неоднозначная группа концов сети: суммарное расхождение более 0,25 м");
    for (Map.Entry<String, Integer> p : points.entrySet()) {
      Vertex v = nodes.get(root(nodes, p.getValue()));
      if (!v.objects.isEmpty())
        throw new IllegalArgumentException(
            "Совпадающие камеры/источник: " + v.objects.get(0) + " / " + p.getKey());
      v.objects.add(p.getKey());
    }
    for (int i = 0; i < ends.size(); i++) {
      int[] e = ends.get(i);
      e[0] = root(nodes, e[0]);
      e[1] = root(nodes, e[1]);
      if (e[0] == e[1])
        throw new IllegalArgumentException(
            lines.get(i).id + ": участок образует петлю или короче допуска 0,25 м");
      nodes.get(e[0]).edges.add(i);
      nodes.get(e[1]).edges.add(i);
    }
    int start = root(nodes, points.get(sources.get(0).id));
    Map<Integer, String> parentLine = new HashMap<>();
    Set<Integer> seen = new HashSet<>(), edgeSeen = new HashSet<>();
    Deque<Integer> queue = new ArrayDeque<>();
    seen.add(start);
    queue.add(start);
    int nonmonotone = 0, inferred = 0;
    while (!queue.isEmpty()) {
      int at = queue.remove();
      Vertex v = nodes.get(at);
      String parent = parentLine.get(at), object = v.objects.isEmpty() ? null : v.objects.get(0);
      if (object != null && at != start) {
        Feature c = store.get(object);
        ObjectNode p = props(c);
        int dn = 0;
        for (int ei : v.edges) dn = Math.max(dn, lines.get(ei).dn());
        if (dn == 0) throw new IllegalArgumentException(c.id + ": камера не соединена с сетью");
        if (p.has("diameter")
            && (!p.path("diameter").isIntegralNumber() || p.path("diameter").asInt() != dn))
          throw new IllegalArgumentException(
              c.id + ": diameter камеры не соответствует примыкающим участкам");
        p.put("diameter", dn);
        setUpstream(c, p, parent);
        save(c, c.type, p);
      }
      for (int ei : v.edges) {
        if (!edgeSeen.add(ei)) continue;
        int[] edge = ends.get(ei);
        int to = edge[0] == at ? edge[1] : edge[0];
        Feature line = lines.get(ei);
        if (!seen.add(to))
          throw new IllegalArgumentException(
              "Кольцо существующей сети: "
                  + line.id
                  + ". Автоматически выбрать направление нельзя; нужен полный формат с заданной"
                  + " топологией.");
        ObjectNode p = props(store.get(line.id));
        setUpstream(line, p, object == null ? parent : object);
        p.put("_tt_topology_inferred", true);
        save(line, line.type, p);
        inferred++;
        if (parent != null && store.get(parent).dn() < line.dn()) nonmonotone++;
        parentLine.put(to, line.id);
        queue.add(to);
      }
    }
    if (edgeSeen.size() != lines.size())
      throw new IllegalArgumentException(
          "Не все участки соединены с источником в пределах 0,25 м. Разрывы не дорисовываются.");
    for (Feature c : chambers)
      if (!seen.contains(root(nodes, points.get(c.id))))
        throw new IllegalArgumentException(c.id + ": камера не соединена с концами участков");
    report.put("inferredNetworkCount", inferred);
    report.put("diameterTransitionsToCheck", nonmonotone);
    notes.add(
        "Направление к источнику восстановлено по концам участков и камерам с допуском 0,25 м;"
            + " пересечения осей без общего конца не создают узел. Координаты сети не менялись.");
    if (nonmonotone > 0)
      notes.add(
          "В существующей сети "
              + nonmonotone
              + " перехода с уменьшением DN к источнику. Исходные диаметры сохранены; при"
              + " добавлении расхода проверяются все участки до источника. Нужна проверка исходной"
              + " схемы.");
  }

  private void setUpstream(Feature f, ObjectNode p, String id) {
    if (id == null) throw new IllegalArgumentException(f.id + ": не найден путь к источнику");
    if (!f.text("upstream_object_id").isBlank() && !f.text("upstream_object_id").equals(id))
      throw new IllegalArgumentException(
          f.id + ": upstream_object_id противоречит геометрической цепочке");
    p.put("upstream_object_id", id);
  }

  private static List<Feature> concat(List<Feature> a, List<Feature> b) {
    List<Feature> out = new ArrayList<>(a);
    out.addAll(b);
    return out;
  }
}
