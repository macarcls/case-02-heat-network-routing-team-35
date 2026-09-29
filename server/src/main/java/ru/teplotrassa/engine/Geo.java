package ru.teplotrassa.engine;

import org.locationtech.jts.geom.*;
import org.locationtech.jts.linearref.LengthIndexedLine;
import org.locationtech.proj4j.*;

public final class Geo {
  public static final GeometryFactory GF = new GeometryFactory(new PrecisionModel(), 32637);
  private static final CRSFactory CRS = new CRSFactory();
  private static final CoordinateTransform FORWARD =
      new CoordinateTransformFactory()
          .createTransform(CRS.createFromName("EPSG:4326"), CRS.createFromName("EPSG:32637"));
  private static final CoordinateTransform INVERSE =
      new CoordinateTransformFactory()
          .createTransform(CRS.createFromName("EPSG:32637"), CRS.createFromName("EPSG:4326"));

  public static Coordinate xy(double lon, double lat) {
    ProjCoordinate r = new ProjCoordinate();
    FORWARD.transform(new ProjCoordinate(lon, lat), r);
    return new Coordinate(r.x, r.y);
  }

  public static Coordinate ll(Coordinate c) {
    ProjCoordinate r = new ProjCoordinate();
    INVERSE.transform(new ProjCoordinate(c.x, c.y), r);
    return new Coordinate(r.x, r.y, c.getZ());
  }

  public static Geometry project(Geometry input) {
    Geometry g = input.copy();
    g.apply(
        (CoordinateFilter)
            c -> {
              if (!Double.isFinite(c.x)
                  || !Double.isFinite(c.y)
                  || Math.abs(c.x) > 180
                  || c.y < 0
                  || c.y > 84)
                throw new IllegalArgumentException(
                    "Ожидаются координаты WGS84 северного полушария (EPSG:32637)");
              Coordinate p = xy(c.x, c.y);
              c.x = p.x;
              c.y = p.y;
              c.setZ(Double.NaN);
            });
    g.geometryChanged();
    g.setSRID(32637);
    return g;
  }

  public static LineString line(Coordinate... points) {
    return GF.createLineString(points);
  }

  public static Point point(Coordinate c) {
    return GF.createPoint(c);
  }

  public static LineString part(LineString line, double a, double b) {
    return (LineString) new LengthIndexedLine(line).extractLine(a, b);
  }

  public static Coordinate at(LineString line, double at) {
    return new LengthIndexedLine(line).extractPoint(at);
  }

  public static double index(LineString line, Coordinate c) {
    return new LengthIndexedLine(line).project(c);
  }

  public static String key(Coordinate c) {
    return Math.round(c.x * 1000) + ":" + Math.round(c.y * 1000);
  }

  private Geo() {}
}
