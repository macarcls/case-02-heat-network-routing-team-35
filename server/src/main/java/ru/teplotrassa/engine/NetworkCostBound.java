package ru.teplotrassa.engine;

import java.util.*;
import org.locationtech.jts.geom.*;
import ru.teplotrassa.data.*;

/** Lower bound for a fixed candidate topology and geometry, not a global optimum certificate. */
public final class NetworkCostBound {
  private NetworkCostBound() {}

  /** Optimistic fixed-geometry score for the current exploratory objective. */
  public static double objectiveLowerBound(
      Network n, FeatureStore store, Planner.Options options, boolean turns) {
    double balanced = score(n, store, options, turns);
    if (options.variantObjective.equals("balanced") || !Double.isFinite(balanced))
      return balanced;
    if (options.variantObjective.equals("earthworks")) {
      double volume = 0;
      double minimum = options.mode.equals("depth") ? options.minDepthM : DepthPlanner.BASE;
      for (Network.Edge e : n.edges.values())
        volume += e.geometry.getLength() * (Rules.width(e.dn) + 1.0) * minimum;
      return volume / 400 + .08 * balanced;
    }
    double bends = 0;
    for (Network.Edge e : n.edges.values())
      bends += RoutingQuality.bends(e.geometry).equivalentM;
    return bends / 70 + .08 * balanced;
  }

  public static double score(
      Network n, FeatureStore store, Planner.Options options, boolean turns) {
    try {
      n.flows();
      n.lengthChecks();
      boolean contest = options.rankingProfile.equals("contest");
      Reconstruction r = contest ? new Reconstruction() : Reconstruction.calculate(n, store);
      double cost = 0, length = 0, penalty = 0;
      for (Network.Edge e : n.edges.values()) {
        double len = e.geometry.getLength();
        length += len;
        cost += len * Rules.NEW[Rules.index(e.dn)];
        if (turns) {
          penalty += RoutingQuality.bends(e.geometry).equivalentM;
          Coordinate a = e.geometry.getCoordinateN(0), b = e.geometry.getCoordinateN(1);
          penalty +=
              RoutingQuality.connectionPenalty(
                  Math.atan2(b.y - a.y, b.x - a.x), axis(n, n.nodes.get(e.start), store));
        }
      }
      if (!contest) {
        for (Reconstruction.Piece p : r.pieces) {
          cost += p.cost();
          length += p.geometry.getLength();
        }
        for (int dn : r.chambers.values()) cost += Rules.chamber(dn);
      }
      for (Network.Node node : n.nodes.values()) {
        int existing = 0;
        if (node.existingId != null) {
          Feature f = store.get(node.existingId);
          existing = f.type.equals("heat_chamber") ? InputValidator.existingDegree(f, store) : 2;
        }
        if (n.incident(node.id).size() + existing > 4) return Double.POSITIVE_INFINITY;
        if (node.type.equals("chamber")
            || node.type.equals("tie") && store.get(node.existingId).type.equals("heat_network"))
          cost += Rules.chamber(r.newChamberDiameter(node, n, store));
        if (node.type.equals("tie"))
          cost += contest
              ? (store.get(node.existingId).type.equals("heat_chamber")
                  ? 5e6 * n.children(node.id).size() : 0)
              : n.children(node.id).size() * 5e6;
      }
      // Every special factor and depth factor is >= 1 in the supplied catalogue.
      return options.score(cost, length +
          (options.rankingProfile.equals("contest") ? 0 : penalty));
    } catch (IllegalArgumentException | TopologyException invalid) {
      return Double.POSITIVE_INFINITY;
    }
  }

  private static Double axis(Network n, Network.Node node, FeatureStore store) {
    if (node.existingId != null) {
      Feature f = store.get(node.existingId);
      Set<String> seen = new HashSet<>();
      while (f != null && f.type.equals("heat_chamber") && seen.add(f.id))
        f = store.get(f.text("upstream_object_id"));
      return f != null && f.geometry instanceof LineString
          ? TreeRoots.axis((LineString) f.geometry, node.point)
          : null;
    }
    for (Network.Edge e : n.edges.values())
      if (e.end.equals(node.id)) {
        int last = e.geometry.getNumPoints() - 1;
        Coordinate a = e.geometry.getCoordinateN(last - 1), b = e.geometry.getCoordinateN(last);
        return Math.atan2(b.y - a.y, b.x - a.x);
      }
    return null;
  }
}
