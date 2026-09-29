package ru.teplotrassa.engine;

import java.util.*;
import org.locationtech.jts.geom.*;

/** Continuous, shared-node vertical profile on a fixed planar forest. No depth grid. */
public final class DepthPlanner {
  public static final double BASE = 3, MINIMUM = .7, SLOPE = .10;
  private static final double EPS = 1e-8;

  public static class Section {
    public final double from, to, h0, h1, factor;
    public final String method;

    public Section(double from, double to, double h0, double h1, double factor, String method) {
      this.from = from;
      this.to = to;
      this.h0 = h0;
      this.h1 = h1;
      this.factor = factor;
      this.method = method;
    }
  }

  private static final class Station {
    int parent;
    List<double[]> domain = new ArrayList<>();
    List<Link> links = new ArrayList<>();
    double low, high;

    Station(int id, double min, double max) {
      parent = id;
      domain.add(new double[] {min, max});
    }
  }

  private static final class Link {
    int to;
    double limit;

    Link(int to, double limit) {
      this.to = to;
      this.limit = limit;
    }
  }

  private static final class EdgeProfile {
    Network.Edge edge;
    double[] at;
    int[] nodes;
    boolean[] flat;

    EdgeProfile(Network.Edge e, double[] at, int[] nodes) {
      this.edge = e;
      this.at = at;
      this.nodes = nodes;
      flat = new boolean[at.length - 1];
    }
  }

  /** Compatibility entry point for an isolated line, with ordinary depth at both ends. */
  public static List<Section> plan(
      LineString line, int dn, List<SpatialRules.Passage> passages, boolean depth, double maximum) {
    if (!depth) return base(line, passages);
    Network n = new Network();
    n.nodes.put("a", new Network.Node("a", line.getCoordinateN(0), "tie"));
    n.nodes.put("b", new Network.Node("b", line.getCoordinateN(line.getNumPoints() - 1), "oks"));
    Network.Edge edge = new Network.Edge("edge", "a", "b", line);
    edge.dn = dn;
    edge.passages = passages;
    n.edges.put(edge.id, edge);
    Planner.Options options = new Planner.Options();
    options.mode = "depth";
    options.maxDepthM = maximum;
    assign(n, options);
    return edge.sections;
  }

  public static List<Section> base(LineString line, List<SpatialRules.Passage> passages) {
    TreeSet<Double> cuts = new TreeSet<>();
    cuts.add(0d);
    cuts.add(line.getLength());
    for (SpatialRules.Passage p : passages) {
      cuts.add(p.from);
      cuts.add(p.to);
    }
    List<Double> x = new ArrayList<>(cuts);
    List<Section> out = new ArrayList<>();
    for (int i = 1; i < x.size(); i++) {
      double a = x.get(i - 1), b = x.get(i);
      if (b - a < EPS) continue;
      append(out, a, b, Double.NaN, Double.NaN, factor(passages, (a + b) / 2));
    }
    return partitionAtPassages(out, passages);
  }

  public static Map<String, Object> assign(Network network, Planner.Options options) {
    List<Station> stations = new ArrayList<>();
    Map<String, Integer> junctions = new LinkedHashMap<>();
    for (Network.Node node : network.nodes.values()) junctions.put(node.id, add(stations, options));
    List<EdgeProfile> profiles = new ArrayList<>();
    for (Network.Edge e : network.edges.values()) {
      TreeSet<Double> cuts = new TreeSet<>();
      double len = e.geometry.getLength();
      cuts.add(0d);
      cuts.add(len);
      for (SpatialRules.Passage p : e.passages) {
        // Do not silently truncate the mandatory plateau at a graph edge endpoint.
        if (p.coreFrom < p.rule.extension - EPS || len - p.coreTo < p.rule.extension - EPS)
          throw conflict(
              "PLATEAU_TOO_SHORT",
              e.id
                  + " / "
                  + p.object.id
                  + ": для перехода нужны полные "
                  + p.rule.extension
                  + " м с каждой стороны; перенесите узел или маршрут");
        cuts.add(p.from);
        cuts.add(p.to);
      }
      List<Double> distinct = new ArrayList<>();
      for (double x : cuts)
        if (distinct.isEmpty() || x - distinct.get(distinct.size() - 1) > 1e-7) distinct.add(x);
      distinct.set(distinct.size() - 1, len);
      double[] at = distinct.stream().mapToDouble(Double::doubleValue).toArray();
      int[] ids = new int[at.length];
      for (int i = 0; i < ids.length; i++)
        ids[i] =
            i == 0
                ? junctions.get(e.start)
                : i == ids.length - 1 ? junctions.get(e.end) : add(stations, options);
      EdgeProfile profile = new EdgeProfile(e, at, ids);
      profiles.add(profile);
      for (int i = 0; i < at.length - 1; i++)
        for (SpatialRules.Passage p : e.passages)
          if ((at[i] + at[i + 1]) / 2 >= p.from - EPS && (at[i] + at[i + 1]) / 2 <= p.to + EPS) {
            profile.flat[i] = true;
            union(stations, ids[i], ids[i + 1]);
          }
    }
    if (stations.size() > 200000)
      throw conflict("PROFILE_SIZE_LIMIT", "более 200000 станций профиля");
    for (Network.Node n : network.nodes.values())
      if (n.type.equals("tie") || n.type.equals("oks"))
        intersect(
            stations.get(root(stations, junctions.get(n.id))),
            List.of(new double[] {BASE, BASE}),
            "конечный узел " + n.id);
    for (EdgeProfile e : profiles) {
      for (int i = 0; i < e.at.length - 1; i++) {
        int a = root(stations, e.nodes[i]), b = root(stations, e.nodes[i + 1]);
        if (a != b) {
          double limit = SLOPE * (e.at[i + 1] - e.at[i]);
          stations.get(a).links.add(new Link(b, limit));
          stations.get(b).links.add(new Link(a, limit));
        }
      }
      for (SpatialRules.Passage p : e.edge.passages) {
        List<double[]> allowed = allowed(p, e.edge.dn, options);
        for (int i = 0; i < e.at.length; i++)
          if (e.at[i] >= p.from - EPS && e.at[i] <= p.to + EPS)
            intersect(
                stations.get(root(stations, e.nodes[i])), allowed, e.edge.id + " / " + p.object.id);
      }
    }
    // Least feasible depth: propagate lower bounds and round upwards only across forbidden
    // bands.
    PriorityQueue<double[]> lower = new PriorityQueue<>((a, b) -> Double.compare(b[0], a[0]));
    for (int i = 0; i < stations.size(); i++)
      if (root(stations, i) == i) {
        Station s = stations.get(i);
        s.low = s.domain.get(0)[0];
        lower.add(new double[] {s.low, i});
      }
    while (!lower.isEmpty()) {
      double[] item = lower.poll();
      Station s = stations.get((int) item[1]);
      if (item[0] < s.low - EPS) continue;
      for (Link link : s.links) {
        Station t = stations.get(link.to);
        double need = s.low - link.limit;
        if (need > t.low + EPS) {
          t.low = ceil(t.domain, need);
          lower.add(new double[] {t.low, link.to});
        }
      }
    }
    // For every value <=3 m choose the greatest feasible value within its selected band.
    // This returns to ordinary depth as soon as feasible without increasing construction cost.
    PriorityQueue<double[]> upper = new PriorityQueue<>(Comparator.comparingDouble(a -> a[0]));
    for (int i = 0; i < stations.size(); i++)
      if (root(stations, i) == i) {
        Station s = stations.get(i);
        s.high = s.low;
        if (s.low < BASE - EPS)
          for (double[] d : s.domain)
            if (s.low >= d[0] - EPS && s.low <= d[1] + EPS) {
              s.high = Math.min(BASE, d[1]);
              break;
            }
        upper.add(new double[] {s.high, i});
      }
    while (!upper.isEmpty()) {
      double[] item = upper.poll();
      Station s = stations.get((int) item[1]);
      if (item[0] > s.high + EPS) continue;
      for (Link link : s.links) {
        Station t = stations.get(link.to);
        double cap = s.high + link.limit;
        if (cap < t.high - EPS) {
          if (cap < t.low - EPS)
            throw conflict("INCONSISTENT_PROFILE", "не удалось согласовать глубины узлов");
          t.high = Math.max(cap, t.low);
          upper.add(new double[] {t.high, link.to});
        }
      }
    }
    for (Network.Node n : network.nodes.values())
      n.depth = stations.get(root(stations, junctions.get(n.id))).high;
    List<Map<String, Object>> crossings = new ArrayList<>(), exported = new ArrayList<>();
    double minimum = BASE, maximum = BASE, maxSlope = 0, extraCost = 0;
    for (EdgeProfile ep : profiles) {
      Network.Edge e = ep.edge;
      List<Section> out = new ArrayList<>();
      for (int i = 0; i < ep.at.length - 1; i++) {
        double a = ep.at[i],
            b = ep.at[i + 1],
            ha = stations.get(root(stations, ep.nodes[i])).high,
            hb = stations.get(root(stations, ep.nodes[i + 1])).high,
            k = factor(e.passages, (a + b) / 2);
        if (ep.flat[i]) {
          if (Math.abs(ha - hb) > EPS) throw conflict("PLATEAU_SLOPE", e.id);
          append(out, a, b, ha, hb, k);
        } else ramp(out, a, b, ha, hb, k, options.minDepthM, options.maxDepthM);
      }
      e.sections = partitionAtPassages(out, e.passages);
      List<Map<String, Object>> points = new ArrayList<>();
      for (Section s : e.sections) {
        minimum = Math.min(minimum, Math.min(s.h0, s.h1));
        maximum = Math.max(maximum, Math.max(s.h0, s.h1));
        maxSlope = Math.max(maxSlope, Math.abs(s.h1 - s.h0) / (s.to - s.from));
        extraCost +=
            (s.to - s.from)
                * Rules.NEW[Rules.index(e.dn)]
                * s.factor
                * ((Rules.depthFactor(s.h0) + Rules.depthFactor(s.h1)) / 2 - 1);
        if (points.isEmpty()) points.add(Map.of("distanceM", s.from, "depthM", s.h0));
        points.add(Map.of("distanceM", s.to, "depthM", s.h1));
      }
      Map<String, Object> row = new LinkedHashMap<>();
      row.put("edgeId", e.id);
      row.put("startNodeId", e.start);
      row.put("endNodeId", e.end);
      row.put("diameter", e.dn);
      row.put("lengthM", e.geometry.getLength());
      row.put("stations", points);
      exported.add(row);
      for (SpatialRules.Passage p : e.passages) {
        double h = at(e.sections, (p.from + p.to) / 2),
            gap = vertical(p, options),
            oldHeight = oldHeight(p),
            actual;
        String side;
        if (p.rule.polygon) {
          side = "under_surface";
          actual = h;
        } else if (p.rule.top - (h + Rules.HEIGHT[Rules.index(e.dn)]) >= gap - EPS) {
          side = "above";
          actual = p.rule.top - h - Rules.HEIGHT[Rules.index(e.dn)];
        } else {
          side = "below";
          actual = h - p.rule.top - oldHeight;
        }
        if (actual < gap - EPS) throw conflict("VERTICAL_CLEARANCE", e.id + " / " + p.object.id);
        for (Section s : e.sections)
          if (Math.min(s.to, p.to) - Math.max(s.from, p.from) > EPS
              && (Math.abs(at(e.sections, Math.max(s.from, p.from)) - h) > EPS
                  || Math.abs(at(e.sections, Math.min(s.to, p.to)) - h) > EPS))
            throw conflict("PLATEAU_SLOPE", e.id + " / " + p.object.id);
        Map<String, Object> crossing = new LinkedHashMap<>();
        crossing.put("edgeId", e.id);
        crossing.put("existingObjectId", p.object.id);
        crossing.put("type", p.object.restriction());
        crossing.put("fromM", p.from);
        crossing.put("toM", p.to);
        crossing.put("coreFromM", p.coreFrom);
        crossing.put("coreToM", p.coreTo);
        crossing.put("depthM", h);
        crossing.put("position", side);
        crossing.put("requiredClearanceM", gap);
        crossing.put("actualClearanceM", actual);
        crossing.put("newHeightM", Rules.HEIGHT[Rules.index(e.dn)]);
        crossing.put("existingTopM", p.rule.top);
        crossing.put("existingHeightM", oldHeight);
        crossing.put("plateauLengthM", p.to - p.from);
        crossings.add(crossing);
      }
    }
    if (maxSlope > SLOPE + EPS
        || minimum < options.minDepthM - EPS
        || maximum > options.maxDepthM + EPS)
      throw conflict("PROFILE_BOUNDS", "нарушены глубина или уклон");
    Map<String, Object> report = new LinkedHashMap<>();
    report.put("ruleProfile", options.depthRuleProfile);
    report.put("ordinaryDepthM", BASE);
    report.put("boundaryDepthM", BASE);
    report.put("surfaceModel", "flat_z_zero");
    report.put("lengthBasis", "horizontal_plan");
    report.put("allowedMinimumM", options.minDepthM);
    report.put("allowedMaximumM", options.maxDepthM);
    report.put("minimumDepthM", minimum);
    report.put("maximumDepthM", maximum);
    report.put("maximumSlope", maxSlope);
    report.put("slopeLimit", SLOPE);
    report.put("nodeContinuityValid", true);
    report.put("plateausValid", true);
    report.put("additionalDepthCost", extraCost);
    report.put("crossings", crossings);
    report.put("profiles", exported);
    return report;
  }

  private static int add(List<Station> s, Planner.Options o) {
    int id = s.size();
    s.add(new Station(id, o.minDepthM, o.maxDepthM));
    return id;
  }

  private static int root(List<Station> s, int i) {
    while (s.get(i).parent != i) {
      s.get(i).parent = s.get(s.get(i).parent).parent;
      i = s.get(i).parent;
    }
    return i;
  }

  private static void union(List<Station> s, int a, int b) {
    a = root(s, a);
    b = root(s, b);
    if (a != b) s.get(b).parent = a;
  }

  private static void intersect(Station s, List<double[]> allowed, String label) {
    List<double[]> next = new ArrayList<>();
    for (double[] a : s.domain)
      for (double[] b : allowed) {
        double lo = Math.max(a[0], b[0]), hi = Math.min(a[1], b[1]);
        if (lo <= hi + EPS) next.add(new double[] {lo, Math.max(lo, hi)});
      }
    next.sort(Comparator.comparingDouble(x -> x[0]));
    if (next.isEmpty()) throw conflict("DEPTH_DOMAIN_EMPTY", label + ": нет совместимой глубины");
    s.domain = next;
  }

  private static double ceil(List<double[]> domain, double needed) {
    for (double[] band : domain)
      if (needed <= band[1] + EPS) return Math.max(band[0], Math.min(needed, band[1]));
    throw conflict(
        "RAMP_OR_DEPTH_LIMIT", "недостаточно длины для уклона 0,10 или превышен диапазон глубин");
  }

  private static List<double[]> allowed(SpatialRules.Passage p, int dn, Planner.Options o) {
    if (p.rule.polygon) return List.of(new double[] {vertical(p, o), o.maxDepthM});
    return List.of(
        new double[] {o.minDepthM, p.rule.top - vertical(p, o) - Rules.HEIGHT[Rules.index(dn)]},
        new double[] {p.rule.top + oldHeight(p) + vertical(p, o), o.maxDepthM});
  }

  private static double oldHeight(SpatialRules.Passage p) {
    return p.object.type.equals("heat_network")
        ? Rules.HEIGHT[Rules.index(p.object.dn())]
        : p.rule.height;
  }

  private static double vertical(SpatialRules.Passage p, Planner.Options o) {
    return o.depthRuleProfile.equals("protocol") ? Math.max(.7, p.rule.vertical) : p.rule.vertical;
  }

  private static double factor(List<SpatialRules.Passage> p, double x) {
    double f = 1;
    for (SpatialRules.Passage a : p)
      if (x >= a.from - EPS && x <= a.to + EPS) f = Math.max(f, a.rule.factor);
    return f;
  }

  public static double at(List<Section> sections, double x) {
    for (Section s : sections)
      if (x >= s.from - EPS && x <= s.to + EPS)
        return s.h0 + (s.h1 - s.h0) * Math.max(0, Math.min(1, (x - s.from) / (s.to - s.from)));
    throw conflict("PROFILE_STATION_MISSING", Double.toString(x));
  }

  private static double envelope(double x, double len, double a, double b, double min, double max) {
    double lo = Math.max(min, Math.max(a - SLOPE * x, b - SLOPE * (len - x))),
        hi = Math.min(max, Math.min(a + SLOPE * x, b + SLOPE * (len - x)));
    return Math.max(lo, Math.min(BASE, hi));
  }

  private static void ramp(
      List<Section> out,
      double from,
      double to,
      double a,
      double b,
      double factor,
      double min,
      double max) {
    double len = to - from;
    if (Math.abs(a - b) > SLOPE * len + EPS)
      throw conflict("RAMP_TOO_STEEP", "наклонный участок слишком короткий");
    double[][] lines = {
      {min, 0},
      {max, 0},
      {BASE, 0},
      {a, -SLOPE},
      {b - SLOPE * len, SLOPE},
      {a, SLOPE},
      {b + SLOPE * len, -SLOPE}
    };
    TreeSet<Double> x = new TreeSet<>();
    x.add(0d);
    x.add(len);
    for (int i = 0; i < lines.length; i++)
      for (int j = i + 1; j < lines.length; j++)
        if (Math.abs(lines[i][1] - lines[j][1]) > EPS) {
          double at = (lines[j][0] - lines[i][0]) / (lines[i][1] - lines[j][1]);
          if (at > EPS && at < len - EPS) x.add(at);
        }
    List<Double> cuts = new ArrayList<>(x);
    for (int i = 1; i < cuts.size(); i++) {
      double l = cuts.get(i - 1), r = cuts.get(i);
      if (r - l > EPS)
        append(
            out,
            from + l,
            from + r,
            envelope(l, len, a, b, min, max),
            envelope(r, len, a, b, min, max),
            factor);
    }
  }

  private static void append(
      List<Section> out, double from, double to, double h0, double h1, double f) {
    if (to - from < EPS) return;
    if (!out.isEmpty()) {
      Section prev = out.get(out.size() - 1);
      boolean planar = !Double.isFinite(h0) && !Double.isFinite(prev.h0);
      boolean sameSlope =
          !planar
              && Math.abs(prev.h1 - h0) < EPS
              && Math.abs((prev.h1 - prev.h0) / (prev.to - prev.from) - (h1 - h0) / (to - from))
                  < 1e-9;
      boolean crossesBase = !planar && (prev.h0 - BASE) * (h1 - BASE) < -EPS;
      if (Math.abs(prev.to - from) < EPS
          && Math.abs(prev.factor - f) < 1e-12
          && (planar || sameSlope && !crossesBase)) {
        out.remove(out.size() - 1);
        from = prev.from;
        h0 = prev.h0;
      }
    }
    out.add(new Section(from, to, h0, h1, f, f > 1 ? "special" : "base"));
  }

  /** A change in the set of special passages starts a new output edge, even at equal tariffs. */
  private static List<Section> partitionAtPassages(
      List<Section> sections, List<SpatialRules.Passage> passages) {
    if (passages.isEmpty()) return sections;
    List<Section> out = new ArrayList<>();
    for (Section s : sections) {
      TreeSet<Double> cuts = new TreeSet<>(List.of(s.from, s.to));
      for (SpatialRules.Passage p : passages) {
        if (p.from > s.from + EPS && p.from < s.to - EPS) cuts.add(p.from);
        if (p.to > s.from + EPS && p.to < s.to - EPS) cuts.add(p.to);
      }
      Double before = null;
      for (double after : cuts) {
        if (before != null && after - before > EPS) {
          double h0 = Double.isFinite(s.h0)
              ? s.h0 + (s.h1 - s.h0) * (before - s.from) / (s.to - s.from) : Double.NaN;
          double h1 = Double.isFinite(s.h1)
              ? s.h0 + (s.h1 - s.h0) * (after - s.from) / (s.to - s.from) : Double.NaN;
          double k = factor(passages, (before + after) / 2);
          out.add(new Section(before, after, h0, h1, k, k > 1 ? "special" : "base"));
        }
        before = after;
      }
    }
    return out;
  }

  private static IllegalArgumentException conflict(String code, String message) {
    return new IllegalArgumentException(code + ": " + message);
  }

  private DepthPlanner() {}
}
