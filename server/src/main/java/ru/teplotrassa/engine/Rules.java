package ru.teplotrassa.engine;

import java.util.*;

/** Immutable parameters transcribed from the supplied technical appendix, sections 4, 5, 8, 9. */
public final class Rules {
  public static final String VERSION = "LCT-2026-09-28-v1.7-appendix-geometry";
  public static final double[] CAPACITY = {
    3.5, 8.3, 13.2, 22.3, 40.2, 65.1, 152.3, 274.9, 437.4, 943.1, 1663.4, 2627.7, 3735.1, 5296.8,
    7165, 9391.8, 15012.8, 22501.9
  };
  public static final int[] DN = {
    50, 65, 80, 100, 125, 150, 200, 250, 300, 400, 500, 600, 700, 800, 900, 1000, 1200, 1400
  };
  public static final double[] LENGTH = {
    181, 245, 327, 419, 554, 696, 1042, 1379, 1718, 2477, 3245, 4037, 4775, 5644, 6518, 7419, 9288,
    11276
  };
  public static final double[] NEW = {
    74023, 78631, 83530, 89748, 97275, 105507, 120275, 135323, 150022, 190299, 224137, 264790,
    324298, 325996, 327693, 418777, 428074, 683417
  };
  public static final double[] RECON = {
    96180, 109989, 117582, 133694, 148030, 152295, 181766, 202030, 228707, 271317, 333884, 372703,
    439571, 489918, 553607, 606679, 825692, 978584
  };
  public static final double[] HEIGHT = {
    .125, .140, .160, .180, .225, .250, .315, .400, .450, .560, .710, .800, .900, 1, 1.1, 1.2,
    1.425, 1.6
  };

  public static int index(int dn) {
    int i = Arrays.binarySearch(DN, dn);
    if (i < 0) throw new IllegalArgumentException("Диаметр отсутствует в таблице 4.1: " + dn);
    return i;
  }

  public static int diameter(double flow) {
    for (int i = 0; i < DN.length; i++) if (flow <= CAPACITY[i] + 1e-9) return DN[i];
    throw new IllegalArgumentException("Расход превышает пропускную способность DN1400: " + flow);
  }

  public static double width(int dn) {
    return 2 * HEIGHT[index(dn)] + (dn <= 150 ? .15 : .25);
  }

  public static double buildingClearance(int dn) {
    return dn < 500 ? 5 : dn <= 800 ? 7 : 9;
  }

  public static double chamber(int dn) {
    return dn <= 200 ? 3e6 : dn <= 500 ? 5e6 : dn <= 1000 ? 8e6 : 12e6;
  }

  public static double score(double cost, double length) {
    return .7 * cost / 25e6 + .3 * length / 100;
  }

  /** Relative display rating: higher is better; the best returned variant receives 100. */
  public static double relativeRating(double score, double bestScore) {
    if (!Double.isFinite(score)
        || !Double.isFinite(bestScore)
        || bestScore < 0
        || score < bestScore)
      throw new IllegalArgumentException("Некорректный индекс ранжирования");
    return score == 0 ? 100 : 100 * (bestScore / score);
  }

  public static double depthFactor(double h) {
    return 1 + .1 * Math.max(0, h - 3);
  }

  public static class Restriction {
    public final double clearance, extension, factor, vertical, height, top;
    public final boolean hard, polygon, angle;

    Restriction(
        boolean hard,
        boolean polygon,
        double clearance,
        double extension,
        double factor,
        boolean angle,
        double vertical,
        double height,
        double top) {
      this.hard = hard;
      this.polygon = polygon;
      this.clearance = clearance;
      this.extension = extension;
      this.factor = factor;
      this.angle = angle;
      this.vertical = vertical;
      this.height = height;
      this.top = top;
    }
  }

  public static final Map<String, Restriction> RESTRICTIONS;

  static {
    Map<String, Restriction> m = new LinkedHashMap<>();
    for (String type : List.of("park", "social_area", "prohibited_site", "water", "railway"))
      m.put(type, new Restriction(true, true, 1, 0, 1, false, 0, 0, 0));
    m.put("oks", new Restriction(true, true, 5, 0, 1, false, 0, 0, 0));
    m.put("road", new Restriction(false, true, 1.5, 3, 1.60, true, 1, 0, 0));
    m.put("tram_tracks", new Restriction(false, true, 1.5, 3, 1.75, true, 1.2, 0, 0));
    m.put("gas_pipeline", new Restriction(false, false, 2, 2, 1.25, false, .2, .4, 2.8));
    m.put("power_cable", new Restriction(false, false, 2, 2, 1.15, false, .5, .2, 2.7));
    m.put("heat_network", new Restriction(false, false, 1, 2, 1.05, false, .5, 0, 3));
    RESTRICTIONS = Collections.unmodifiableMap(m);
  }

  private Rules() {}
}
