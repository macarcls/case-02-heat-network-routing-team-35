package ru.teplotrassa.engine;

/** Comparable indicators; actual soil properties and construction technology are not supplied. */
public final class ConstructionObjectives {
  private ConstructionObjectives() {}

  public static final class Metrics {
    public double trenchVolumeM3, deepVolumeM3, specialVolumeM3;
    public double earthworkIndex, installationIndex, verticalChangeM, maximumDepthM;
    public int depthChangeCount;
  }

  public static Metrics measure(
      Network network, RoutingQuality.Stats turns, int adapterCount, boolean depth) {
    Metrics m = new Metrics();
    m.maximumDepthM = DepthPlanner.BASE;
    double deepLength = 0;
    for (Network.Edge edge : network.edges.values()) {
      double width = Rules.width(edge.dn) + 1.0;
      int previousSlope = 0;
      for (DepthPlanner.Section part : edge.sections) {
        double length = part.to - part.from;
        double a = depth ? part.h0 : DepthPlanner.BASE;
        double b = depth ? part.h1 : DepthPlanner.BASE;
        double average = (a + b) / 2;
        m.maximumDepthM = Math.max(m.maximumDepthM, Math.max(a, b));
        m.trenchVolumeM3 += length * width * average;
        m.deepVolumeM3 += length * width * Math.max(0, average - DepthPlanner.BASE);
        m.specialVolumeM3 += length * width * average * Math.max(0, part.factor - 1);
        if (average > DepthPlanner.BASE + .01) deepLength += length;
        double delta = b - a;
        m.verticalChangeM += Math.abs(delta);
        int slope = delta > .01 ? 1 : delta < -.01 ? -1 : 0;
        if (slope != 0 && slope != previousSlope) m.depthChangeCount++;
        previousSlope = slope;
      }
    }
    m.earthworkIndex = m.trenchVolumeM3 + 2 * m.deepVolumeM3 + 3 * m.specialVolumeM3;
    m.installationIndex = turns.equivalentM + 50 * m.depthChangeCount
        + 100 * m.verticalChangeM + 90 * Math.max(0, m.maximumDepthM - DepthPlanner.BASE)
        + 3 * deepLength + 100 * adapterCount;
    return m;
  }
}
