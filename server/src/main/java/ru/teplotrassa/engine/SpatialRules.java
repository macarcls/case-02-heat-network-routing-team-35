package ru.teplotrassa.engine;

import java.util.*;
import org.locationtech.jts.geom.*;
import ru.teplotrassa.data.*;

public final class SpatialRules {
  public static class Passage {
    public double from, to, coreFrom, coreTo;
    public final Feature object;
    public final Rules.Restriction rule;

    Passage(
        double from,
        double to,
        double coreFrom,
        double coreTo,
        Feature object,
        Rules.Restriction rule) {
      this.coreFrom = coreFrom;
      this.coreTo = coreTo;
      this.from = from;
      this.to = to;
      this.object = object;
      this.rule = rule;
    }
  }

  public static class Assessment {
    public final List<Passage> passages = new ArrayList<>();
    public final List<String> issues = new ArrayList<>();

    public boolean valid() {
      return issues.isEmpty();
    }
  }

  private final List<Feature> objects;
  final boolean queryIndependent;

  public SpatialRules(List<Feature> objects) {
    this(objects, true);
  }

  /** Retain the released search behaviour only for the explicit baseline policy. */
  public SpatialRules(List<Feature> objects, boolean queryIndependent) {
    this.objects = objects;
    this.queryIndependent = queryIndependent;
  }

  public Assessment assess(LineString line, int dn, String leafId, Coordinate tie) {
    return assess(line, dn, leafId, tie, null);
  }

  public Assessment assess(
      LineString line, int dn, String leafId, Coordinate tie, Coordinate entry) {
    return assess(line, dn, leafId, tie, entry, null);
  }

  public Assessment assess(
      LineString line,
      int dn,
      String leafId,
      Coordinate tie,
      Coordinate entry,
      LineString selectedWall) {
    Assessment out = new Assessment();
    double length = line.getLength(), half = Rules.width(dn) / 2;
    for (Feature f : objects) {
      if (!f.geometry.getEnvelopeInternal().intersects(expand(line.getEnvelopeInternal(), 12)))
        continue;
      if (BuildingAccess.isBuilding(f)
          || f.type.equals("restriction")
              && Rules.RESTRICTIONS.get(f.restriction()) != null
              && Rules.RESTRICTIONS.get(f.restriction()).hard) {
        double margin = BuildingAccess.isBuilding(f) ? Rules.buildingClearance(dn) : 1;
        LineString checked = line;
        boolean own = BuildingAccess.isBuilding(f) && f.id.equals(leafId) && entry != null;
        BuildingAccess.Gate gate = own ? gate(f, entry, dn) : null;
        Coordinate crossing = gate == null ? null : gate.terminalCrossing(line);
        boolean interior = BuildingAccess.isBuilding(f) && solid(f).intersects(line);
        if (interior
            && (gate == null
                || crossing == null
                || !gate.allowsInterior(line, solid(f).getGeometry()))) {
          out.issues.add(
              "BUILDING_INTERSECTION: "
                  + f.id
              + ": разрешён только прямой конечный вход через выбранную наружную"
              + " стену к исходной точке");
          continue;
        }
        if (interior
            && selectedWall != null
            && selectedWall.distance(Geo.point(crossing)) > BuildingAccess.EPS) {
          out.issues.add("BUILDING_ENTRY_WALL: " + f.id + ": вход вне назначенного участка стены");
          continue;
        }
        if (BuildingAccess.isBuilding(f) && f.id.equals(leafId) && entry != null) {
          // The internal terminal length is charged; only this entry's final approach
          // may cross its wall and clearance buffer. Transit and other branches stay blocked.
          double portal = approachLength(dn) + (crossing == null ? 0 : entry.distance(crossing));
          if (line.getCoordinateN(0).distance(entry) < BuildingAccess.EPS)
            checked = Geo.part(line, Math.min(portal, length), length);
          else if (line.getCoordinateN(line.getNumPoints() - 1).distance(entry)
              < BuildingAccess.EPS) checked = Geo.part(line, 0, Math.max(0, length - portal));
        }
        if (checked.getLength() > 1e-8 && checked.distance(f.geometry) < half + margin - 1e-5)
          out.issues.add(f.id + ": нарушен габарит или отступ");
        continue;
      }
      if (!f.type.equals("restriction") && !f.type.equals("heat_network")) continue;
      Rules.Restriction r = Rules.RESTRICTIONS.get(f.restriction());
      if (r == null || r.hard) continue;
      Geometry intersection = line.intersection(f.geometry);
      List<Passage> local = new ArrayList<>();
      if (r.polygon) {
        for (int i = 0; i < intersection.getNumGeometries(); i++) {
          Geometry g = intersection.getGeometryN(i);
          if (g.getLength() < 1e-7) continue;
          double a = Double.POSITIVE_INFINITY, b = 0;
          for (Coordinate c : g.getCoordinates()) {
            double x = Geo.index(line, c);
            a = Math.min(a, x);
            b = Math.max(b, x);
          }
          if (b - a > 1e-5) {
            if (r.angle && !angleAllowed(line, a, f.geometry))
              out.issues.add(f.id + ": угол пересечения менее 45°");
            double from = Math.max(0, a - r.extension), to = Math.min(length, b + r.extension);
            if (!straight(Geo.part(line, from, to)))
              out.issues.add(f.id + ": специальный проход должен быть прямым");
            local.add(new Passage(from, to, a, b, f, r));
          }
        }
      } else {
        if (intersection.getDimension() > 0 && intersection.getLength() > 1e-5)
          out.issues.add(f.id + ": совпадение осей вместо независимого пересечения");
        for (Coordinate c : intersection.getCoordinates()) {
          if (tie != null && c.distance(tie) < .26 && f.geometry.distance(Geo.point(tie)) < .26)
            continue;
          double x = Geo.index(line, c);
          double from = Math.max(0, x - r.extension), to = Math.min(length, x + r.extension);
          if (!straight(Geo.part(line, from, to)))
            out.issues.add(f.id + ": специальный проход должен быть прямым");
          local.add(new Passage(from, to, x, x, f, r));
        }
      }
      local.sort(Comparator.comparingDouble(p -> p.from));
      List<Passage> merged = new ArrayList<>();
      for (Passage p : local) {
        if (!merged.isEmpty() && p.from <= merged.get(merged.size() - 1).to + 1e-7) {
          Passage previous = merged.get(merged.size() - 1);
          previous.to = Math.max(p.to, previous.to);
          previous.coreFrom = Math.min(p.coreFrom, previous.coreFrom);
          previous.coreTo = Math.max(p.coreTo, previous.coreTo);
        } else merged.add(p);
      }
      // Outside crossing strips the minimum gap is measured between envelopes, never axes.
      double objectHalf =
          r.polygon ? 0 : f.type.equals("heat_network") ? Rules.width(f.dn()) / 2 : r.height / 2;
      // Horizontal clearances govern parallel proximity, not the perpendicular
      // approach to an allowed crossing. Exempt the approach envelope as well as
      // the priced strip, without increasing the charged special length.
      List<double[]> exceptions = new ArrayList<>();
      for (Passage p : merged) {
        double approach = half + objectHalf + r.clearance + .05;
        exceptions.add(
            new double[] {Math.max(0, p.from - approach), Math.min(length, p.to + approach)});
      }
      if (tie != null && f.geometry.distance(Geo.point(tie)) < .26) {
        double x = Geo.index(line, tie), portal = 2 * (half + objectHalf + r.clearance);
        exceptions.add(new double[] {Math.max(0, x - portal), Math.min(length, x + portal)});
      }
      exceptions.sort(Comparator.comparingDouble(v -> v[0]));
      double at = 0;
      for (double[] v : exceptions) {
        if (v[0] > at + .001
            && Geo.part(line, at, v[0]).distance(f.geometry)
                < half + objectHalf + r.clearance - 1e-5)
          out.issues.add(f.id + ": недостаточный горизонтальный просвет рядом с объектом");
        at = Math.max(at, v[1]);
      }
      if (at < length - .001
          && Geo.part(line, at, length).distance(f.geometry)
              < half + objectHalf + r.clearance - 1e-5)
        out.issues.add(f.id + ": недостаточный горизонтальный просвет рядом с объектом");
      out.passages.addAll(local);
    }
    out.passages.sort(Comparator.comparingDouble(p -> p.from));

    return out;
  }

  private static final class BufferedObject {
    final Feature feature;
    final org.locationtech.jts.geom.prep.PreparedGeometry geometry;

    BufferedObject(Feature f, Geometry g) {
      feature = f;
      geometry = org.locationtech.jts.geom.prep.PreparedGeometryFactory.prepare(g);
    }
  }

  private final Map<Integer, org.locationtech.jts.index.strtree.STRtree>
      hardIndexes = new HashMap<>(),
      softIndexes = new HashMap<>();
  private final Map<String, org.locationtech.jts.geom.prep.PreparedGeometry> solids =
      new HashMap<>();

  private org.locationtech.jts.geom.prep.PreparedGeometry solid(Feature f) {
    return solids.computeIfAbsent(
        f.id,
        id ->
            org.locationtech.jts.geom.prep.PreparedGeometryFactory.prepare(
                f.geometry.buffer(-BuildingAccess.EPS)));
  }

  public static double approachLength(int dn) {
    return 2 * (Rules.buildingClearance(dn) + Rules.width(dn) / 2) + .05;
  }

  private final Map<String, Optional<BuildingAccess.Gate>> gates = new HashMap<>();

  private BuildingAccess.Gate gate(Feature f, Coordinate entry, int dn) {
    String key =
        f.id + ":" + Double.toHexString(entry.x) + ":" + Double.toHexString(entry.y) + ":" + dn;
    return gates
        .computeIfAbsent(
            key,
            k -> {
              BuildingAccess.Gate permission = BuildingAccess.gate(f, entry);
              if (permission != null && !queryIndependent) permission.preserveBaselineQueries();
              return Optional.ofNullable(permission == null ? null : permission.forDiameter(dn));
            })
        .orElse(null);
  }

  public BuildingAccess.Gate entryGate(String buildingId, Coordinate entry, int dn) {
    for (Feature f : objects)
      if (f.id.equals(buildingId) && BuildingAccess.isBuilding(f)) return gate(f, entry, dn);
    return null;
  }

  private org.locationtech.jts.index.strtree.STRtree index(int dn, boolean hard) {
    Map<Integer, org.locationtech.jts.index.strtree.STRtree> cache =
        hard ? hardIndexes : softIndexes;
    if (cache.containsKey(dn)) return cache.get(dn);
    org.locationtech.jts.index.strtree.STRtree tree =
        new org.locationtech.jts.index.strtree.STRtree();
    for (Feature f : objects) {
      Rules.Restriction r = Rules.RESTRICTIONS.get(f.restriction());
      double gap = -1;
      if (hard) {
        if (BuildingAccess.isBuilding(f)) gap = Rules.width(dn) / 2 + Rules.buildingClearance(dn);
        else if (f.type.equals("restriction") && r != null && r.hard)
          gap = Rules.width(dn) / 2 + r.clearance;
      } else if ((f.type.equals("restriction") || f.type.equals("heat_network"))
          && r != null
          && !r.hard) gap = r.extension + Rules.width(dn) / 2;
      if (gap < 0) continue;
      Geometry buffer = f.geometry.buffer(Math.max(0, gap - 1e-5), 8);
      tree.insert(buffer.getEnvelopeInternal(), new BufferedObject(f, buffer));
    }
    tree.build();
    cache.put(dn, tree);
    return tree;
  }

  public boolean hardClear(LineString line, int dn, String leafId) {
    return hardClear(line, dn, leafId, null);
  }

  /** Necessary endpoint constraints, checked before spending a routing candidate. */
  public boolean targetClear(Coordinate coordinate, int dn, String leafId) {
    Point point = Geo.point(coordinate);
    for (Object item : index(dn, true).query(point.getEnvelopeInternal())) {
      Feature f = ((BufferedObject) item).feature;
      boolean building = BuildingAccess.isBuilding(f);
      if (building && solid(f).contains(point)) return false;
      // An outside tie may lie within this entry's permitted approach. Its actual
      // terminal arc is checked after routing; other buildings have no exemption.
      if (building && f.id.equals(leafId)) continue;
      double margin =
          building
              ? Rules.buildingClearance(dn)
              : Rules.RESTRICTIONS.get(f.restriction()).clearance;
      if (point.distance(f.geometry) < margin + Rules.width(dn) / 2 - 1e-5) return false;
    }
    return true;
  }

  public boolean hardClear(LineString line, int dn, String leafId, Coordinate entry) {
    for (Object item : index(dn, true).query(line.getEnvelopeInternal())) {
      BufferedObject object = (BufferedObject) item;
      if (!object.geometry.intersects(line)) continue;
      Feature f = object.feature;
      if (!BuildingAccess.isBuilding(f) || !f.id.equals(leafId) || entry == null) return false;
      BuildingAccess.Gate gate = gate(f, entry, dn);
      if (solid(f).intersects(line)
          && (gate == null || !gate.allowsInterior(line, solid(f).getGeometry()))) return false;
      // Interior permission has already been checked. In these two exact cases
      // the subsequent differences would be empty; avoid constructing overlays.
      if (gate != null && (solid(f).covers(line) || gate.searchApproachCovers(line, dn))) continue;
      Geometry permitted =
          gate == null ? Geo.point(entry).buffer(approachLength(dn), 16) : gate.searchApproach(dn);
      // Interior fragments already passed the point-specific straight-ray check.
      Geometry outsideApproach = line.difference(permitted);
      if (gate != null) outsideApproach = outsideApproach.difference(f.geometry);
      if (!outsideApproach.isEmpty()
          && outsideApproach.distance(f.geometry)
              < Rules.buildingClearance(dn) + Rules.width(dn) / 2 - 1e-5) return false;
    }
    return true;
  }

  public double multiplier(Coordinate at, int mode, boolean depth, int dn) {
    Point point = Geo.point(at);
    double result = 1;
    for (Object item : index(dn, false).query(new Envelope(at))) {
      BufferedObject object = (BufferedObject) item;
      if (!object.geometry.covers(point)) continue;
      Rules.Restriction r = Rules.RESTRICTIONS.get(object.feature.restriction());
      double k = r.factor;
      if (mode == 1 && r.polygon) k *= 3;
      if (mode == 2 && !r.polygon) k *= 4;
      result = Math.max(result, k);
    }
    return result;
  }

  private static boolean straight(LineString line) {
    Coordinate[] c = line.getCoordinates();
    if (c.length < 3) return true;
    Coordinate a = c[0], b = c[c.length - 1];
    double length = a.distance(b);
    if (length < 1e-8) return false;
    for (int i = 1; i < c.length - 1; i++)
      if (Math.abs((b.x - a.x) * (c[i].y - a.y) - (b.y - a.y) * (c[i].x - a.x))
          > length * 1e-5) return false;
    return true;
  }

  /** Crossing angle is measured against the polygon's actual exterior boundary at entry. */
  private static boolean angleAllowed(LineString path, double x, Geometry polygon) {
    if (x < 1e-5 || x > path.getLength() - 1e-5) return false;
    Coordinate a = Geo.at(path, Math.max(0, x - .02)),
        b = Geo.at(path, Math.min(path.getLength(), x + .02)),
        hit = Geo.at(path, x);
    double ux = b.x - a.x, uy = b.y - a.y;
    Geometry boundary = polygon.getBoundary();
    boolean found = false;
    for (int j = 0; j < boundary.getNumGeometries(); j++) {
      Coordinate[] c = boundary.getGeometryN(j).getCoordinates();
      for (int i = 1; i < c.length; i++) {
        LineSegment side = new LineSegment(c[i - 1], c[i]);
        if (side.distance(hit) > .02) continue;
        double vx = c[i].x - c[i - 1].x, vy = c[i].y - c[i - 1].y;
        double den = Math.hypot(ux, uy) * Math.hypot(vx, vy);
        if (den < 1e-10) continue;
        found = true;
        if (Math.abs((ux * vx + uy * vy) / den) > Math.cos(Math.PI / 4) + 1e-6)
          return false;
      }
    }
    return found;
  }

  public static Envelope expand(Envelope source, double by) {
    Envelope e = new Envelope(source);
    e.expandBy(by);
    return e;
  }
}
