package ru.teplotrassa.engine;

import org.locationtech.jts.geom.Coordinate;
import org.locationtech.jts.geom.LineString;

/** Search preferences in equivalent metres; never construction tariffs or physical pipe length. */
public final class RoutingQuality {
  public static final double TURN_90_M = 25;
  public static final double TURN_45_M = 50;
  public static final double TURN_135_M = 75;
  public static final double FACADE_ADAPTER_EXTRA_M = 10;
  private static final double TOLERANCE = 1e-5;

  private RoutingQuality() {}

  public static double change(double first, double second) {
    return Math.acos(Math.max(-1, Math.min(1, Math.cos(first - second))));
  }

  /** A tee/chamber may receive flow from either direction of the receiving axis. */
  public static double connectionAngle(double bearing, double axis) {
    return Math.acos(Math.min(1, Math.abs(Math.cos(bearing - axis))));
  }

  public static double penalty(double radians) {
    if (radians < TOLERANCE) return 0;
    if (Math.abs(radians - Math.PI / 2) < TOLERANCE) return TURN_90_M;
    if (Math.abs(radians - Math.PI / 4) < TOLERANCE) return TURN_45_M;
    if (Math.abs(radians - 3 * Math.PI / 4) < TOLERANCE) return TURN_135_M;
    // Only the exact vector validator may authorize these short custom adapters.
    // This finite penalty is also used by the optimistic network cost bound.
    double near = Math.toRadians(4);
    if (radians < near) return TURN_90_M + FACADE_ADAPTER_EXTRA_M;
    if (Math.abs(radians - Math.PI / 2) < near)
      return TURN_90_M + FACADE_ADAPTER_EXTRA_M;
    if (Math.abs(radians - Math.PI / 4) < near)
      return TURN_45_M + FACADE_ADAPTER_EXTRA_M;
    if (Math.abs(radians - 3 * Math.PI / 4) < near)
      return TURN_135_M + FACADE_ADAPTER_EXTRA_M;
    return 2 * TURN_45_M; // Nonstandard manually supplied geometry is not rewarded.
  }

  public static double connectionPenalty(double bearing, Double axis) {
    return axis == null ? 0 : penalty(connectionAngle(bearing, axis));
  }

  /**
   * At a new junction use the actual incoming segment, never a chord across a bend. Splitting this
   * line at the point creates an incoming edge with exactly this bearing.
   */
  public static double upstreamBearing(LineString line, Coordinate point) {
    double at = Geo.index(line, point), walked = 0;
    Coordinate[] coordinates = line.getCoordinates();
    for (int i = 1; i < coordinates.length; i++) {
      Coordinate a = coordinates[i - 1], b = coordinates[i];
      double length = a.distance(b);
      if (length > 1e-8 && (at <= walked + length + 1e-7 || i == coordinates.length - 1))
        return Math.atan2(b.y - a.y, b.x - a.x);
      walked += length;
    }
    throw new IllegalArgumentException("Пустая принимающая ветвь");
  }

  public static Stats bends(LineString line) {
    Stats out = new Stats();
    Coordinate[] c = line.getCoordinates();
    for (int i = 1; i + 1 < c.length; i++) {
      double a = Math.atan2(c[i].y - c[i - 1].y, c[i].x - c[i - 1].x);
      double b = Math.atan2(c[i + 1].y - c[i].y, c[i + 1].x - c[i].x);
      out.add(change(a, b));
    }
    return out;
  }

  public static final class Stats {
    public int count, turns45, turns90, turns135, other;
    public double equivalentM;

    public void add(double radians) {
      double value = penalty(radians);
      if (value == 0) return;
      count++;
      equivalentM += value;
      if (Math.abs(radians - Math.PI / 2) < TOLERANCE) turns90++;
      else if (Math.abs(radians - Math.PI / 4) < TOLERANCE) turns45++;
      else if (Math.abs(radians - 3 * Math.PI / 4) < TOLERANCE) turns135++;
      else other++;
    }

    public void add(Stats value) {
      count += value.count;
      turns45 += value.turns45;
      turns90 += value.turns90;
      turns135 += value.turns135;
      other += value.other;
      equivalentM += value.equivalentM;
    }
  }
}
