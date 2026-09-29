package ru.teplotrassa.engine;

import java.util.*;
import org.locationtech.jts.geom.*;
import ru.teplotrassa.data.*;

/** Referential integrity, directed chains and geometric continuity before route search. */
public final class InputValidator {
  public static final double TOLERANCE = .25;

  /** Required appendix fields: existing flows, upstream links and chamber DN are optional. */
  public static void validateContest(FeatureStore store) {
    if (store.all("source").size() != 1)
      throw new IllegalArgumentException("В наборе должен быть ровно один источник source");
    for (String type : List.of("heat_network", "heat_chamber", "oks_connection_point", "restriction"))
      for (String id : store.ids(type)) store.get(id).validate();
    if (InputData.demands(store).isEmpty())
      throw new IllegalArgumentException("Нет точек подключения");
    for (String id : store.ids("heat_chamber"))
      if (existingDegree(store.get(id), store) > 4)
        throw new IllegalArgumentException(id + ": более четырёх существующих примыкающих участков");
  }

  public static void validate(FeatureStore store) {
    List<Feature> sources = store.all("source");
    if (sources.size() != 1)
      throw new IllegalArgumentException("В наборе должен быть ровно один источник source");
    String source = sources.get(0).id;
    Set<String> resolved = new HashSet<>();
    resolved.add(source);
    for (String type : List.of("heat_network", "heat_chamber"))
      for (String id : store.ids(type)) {
        Feature initial = store.get(id);
        initial.validate();
        String current = id;
        Set<String> chain = new HashSet<>();
        while (!resolved.contains(current)) {
          if (!chain.add(current))
            throw new IllegalArgumentException("Цикл upstream_object_id: " + current);
          Feature f = store.get(current);
          if (f == null || !List.of("heat_network", "heat_chamber").contains(f.type))
            throw new IllegalArgumentException("Неверная ссылка upstream_object_id: " + current);
          Feature upstream = store.get(f.text("upstream_object_id"));
          if (upstream == null
              || !List.of("heat_network", "heat_chamber", "source").contains(upstream.type))
            throw new IllegalArgumentException(
                f.id + ": отсутствует допустимый объект по направлению к источнику");

          if (f.type.equals("heat_network")) {
            upstreamEnd(f, store);
            if (upstream.type.equals("heat_network") && !f.compact() && upstream.dn() < f.dn())
              throw new IllegalArgumentException(
                  f.id + ": существующий DN уменьшается к источнику");
          }
          current = upstream.id;
        }
        resolved.addAll(chain);
      }
    for (String id : store.ids("oks_connection_point")) store.get(id).validate();
    if (InputData.demands(store).isEmpty())
      throw new IllegalArgumentException("Нет точек подключения");
    for (String id : store.ids("heat_chamber")) {
      Feature chamber = store.get(id);
      if (existingDegree(chamber, store) > 4)
        throw new IllegalArgumentException(
            id + ": более четырёх существующих примыкающих участков");
      Envelope e = chamber.geometry.getEnvelopeInternal();
      e.expandBy(TOLERANCE);
      int maximum = 0;
      for (Feature f : store.near(e, "heat_network"))
        if (f.geometry.getBoundary().distance(chamber.geometry) <= TOLERANCE)
          maximum = Math.max(maximum, f.dn());
      if (maximum != chamber.dn())
        throw new IllegalArgumentException(
            id
                + ": diameter камеры должен равняться максимальному DN существующих примыкающих"
                + " участков");
    }
    for (String id : store.ids("heat_network")) {
      Feature line = store.get(id), up = store.get(line.text("upstream_object_id"));
      while (up.type.equals("heat_chamber")) up = store.get(up.text("upstream_object_id"));
      if (up.type.equals("heat_network") && !line.compact() && up.dn() < line.dn())
        throw new IllegalArgumentException(
            id + ": DN существующей сети уменьшается по направлению к источнику");
    }
  }

  public static Coordinate upstreamEnd(Feature line, FeatureStore store) {
    LineString g = (LineString) line.geometry;
    Geometry u = store.get(line.text("upstream_object_id")).geometry;
    double a = g.getStartPoint().distance(u), b = g.getEndPoint().distance(u);
    if (Math.abs(a - b) < 1e-6)
      throw new IllegalArgumentException(
          line.id + ": неоднозначное направление геометрии к источнику");
    return a < b ? g.getCoordinateN(0) : g.getCoordinateN(g.getNumPoints() - 1);
  }

  public static int existingDegree(Feature chamber, FeatureStore store) {
    Envelope e = chamber.geometry.getEnvelopeInternal();
    e.expandBy(TOLERANCE);
    int n = 0;
    for (Feature f : store.near(e, "heat_network"))
      if (f.geometry.getBoundary().distance(chamber.geometry) <= TOLERANCE) n++;
    return n;
  }

  private InputValidator() {}
}
