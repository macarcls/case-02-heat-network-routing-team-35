package ru.teplotrassa.engine;

import java.util.*;
import org.locationtech.jts.geom.*;
import ru.teplotrassa.data.*;

/** Shared-junction proposals in the receiving pipe's frame. These are not consumer endpoints. */
public final class TrunkLayout {
  private TrunkLayout() {}

  public static List<Coordinate> junctions(
      List<Coordinate> terminals,
      Coordinate parent,
      double axis,
      int dn,
      SpatialRules spatial,
      int limit) {
    double c = Math.cos(axis), s = Math.sin(axis);
    List<Double> xs = new ArrayList<>(), ys = new ArrayList<>();
    for (Coordinate p : terminals) {
      double dx = p.x - parent.x, dy = p.y - parent.y;
      xs.add(dx * c + dy * s);
      ys.add(-dx * s + dy * c);
    }
    if (xs.isEmpty()) return List.of();
    Collections.sort(xs);
    Collections.sort(ys);
    TreeSet<Double> xx = ordinates(xs), yy = ordinates(ys);
    List<Coordinate> candidates = new ArrayList<>();
    for (double x : xx)
      for (double y : yy) {
        Coordinate p = new Coordinate(parent.x + x * c - y * s, parent.y + x * s + y * c);
        if (p.distance(parent) < 10 || !spatial.targetClear(p, dn, null)) continue;
        candidates.add(p);
      }
    candidates.sort(
        Comparator.comparingDouble((Coordinate p) -> objective(p, parent, terminals, c, s))
            .thenComparing(Geo::key));
    List<Coordinate> result = new ArrayList<>();
    for (Coordinate p : candidates) {
      if (result.stream().anyMatch(q -> q.distance(p) < 15)) continue;
      result.add(p);
      if (result.size() >= limit) break;
    }
    return result;
  }

  private static TreeSet<Double> ordinates(List<Double> values) {
    TreeSet<Double> out = new TreeSet<>();
    for (double q : new double[] {0, .25, .5, .75, 1}) {
      double value = values.get((int) Math.round(q * (values.size() - 1)));
      for (double offset : new double[] {-12, -6, 0, 6, 12}) out.add(value + offset);
    }
    out.add((values.get(0) + values.get(values.size() - 1)) / 2);
    return out;
  }

  private static double objective(
      Coordinate p, Coordinate parent, List<Coordinate> terminals, double c, double s) {
    double result = manhattan(p, parent, c, s);
    for (Coordinate t : terminals) result += manhattan(p, t, c, s);
    return result;
  }

  private static double manhattan(Coordinate a, Coordinate b, double c, double s) {
    double dx = a.x - b.x, dy = a.y - b.y;
    return Math.abs(dx * c + dy * s) + Math.abs(-dx * s + dy * c);
  }

  public static List<List<Feature>> split(List<Feature> group, double axis) {
    if (group.size() < 4) return List.of(new ArrayList<>(group));
    double c = Math.cos(axis), s = Math.sin(axis);
    Comparator<Feature> byX =
        Comparator.comparingDouble(
            d -> d.geometry.getCoordinate().x * c + d.geometry.getCoordinate().y * s);
    Comparator<Feature> byY =
        Comparator.comparingDouble(
            d -> -d.geometry.getCoordinate().x * s + d.geometry.getCoordinate().y * c);
    List<Feature> x = new ArrayList<>(group), y = new ArrayList<>(group);
    x.sort(byX);
    y.sort(byY);
    Coordinate xa = x.get(0).geometry.getCoordinate(),
        xb = x.get(x.size() - 1).geometry.getCoordinate();
    Coordinate ya = y.get(0).geometry.getCoordinate(),
        yb = y.get(y.size() - 1).geometry.getCoordinate();
    double dx = Math.abs((xb.x - xa.x) * c + (xb.y - xa.y) * s),
        dy = Math.abs(-(yb.x - ya.x) * s + (yb.y - ya.y) * c);
    List<Feature> sorted = dx >= dy ? x : y;
    return List.of(
        new ArrayList<>(sorted.subList(0, sorted.size() / 2)),
        new ArrayList<>(sorted.subList(sorted.size() / 2, sorted.size())));
  }
}
