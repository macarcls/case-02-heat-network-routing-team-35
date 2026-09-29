package ru.teplotrassa.engine;

import java.util.*;
import org.locationtech.jts.geom.*;
import ru.teplotrassa.data.*;

/** An optimistic price for one attachment topology. No learned value is a pruning bound. */
public final class CandidateBound {
  public static final class Value {
    public final double score, cost, length;
    public final int fixedEdges;

    Value(double score, double cost, double length, int fixedEdges) {
      this.score = score;
      this.cost = cost;
      this.length = length;
      this.fixedEdges = fixedEdges;
    }
  }

  private CandidateBound() {}

  public static Value calculate(
      Network template, String proposedEdge, FeatureStore store, Planner.Options options) {
    try {
      Network network = template.copy();
      // Flow-only DN is optimistic; length checks can raise it by several sizes.
      network.flows();
      double construction = 0, newLength = 0, bends = 0, chambers = 0;
      int fixed = 0;
      for (Network.Edge edge : network.edges.values()) {
        Network.Node start = network.nodes.get(edge.start), end = network.nodes.get(edge.end);
        boolean stationary = !edge.id.equals(proposedEdge) && stationary(edge, start, end, store);
        double chord =
            edge.geometry
                .getCoordinateN(0)
                .distance(edge.geometry.getCoordinateN(edge.geometry.getNumPoints() - 1));
        double length =
            stationary
                ? edge.geometry.getLength()
                : Math.min(chord, start.point.distance(end.point));
        if (stationary) {
          fixed++;
          bends += RoutingQuality.bends(edge.geometry).equivalentM;
        }
        newLength += length;
        // Ground/depth factors are >= 1. Connections and movable bends contribute >= 0.
        construction += length * Rules.NEW[Rules.index(edge.dn)];
      }
      boolean contestRanking = options.rankingProfile.equals("contest");
      Reconstruction reconstruction = contestRanking
          ? new Reconstruction() : Reconstruction.calculate(network, store);
      for (Network.Node node : network.nodes.values())
        if (node.type.equals("chamber")
            || node.type.equals("tie") && store.get(node.existingId).type.equals("heat_network"))
          chambers += Rules.chamber(reconstruction.newChamberDiameter(node, network, store));
      double reconCost =
          reconstruction.pieces.stream().mapToDouble(Reconstruction.Piece::cost).sum();
      double reconLength =
          reconstruction.pieces.stream().mapToDouble(p -> p.geometry.getLength()).sum();
      double chamberRecon =
          reconstruction.chambers.values().stream().mapToDouble(Rules::chamber).sum();
      double ties =
          network.roots().stream().mapToInt(r -> network.children(r.id).size()).sum() * 5e6;
      double cost = contestRanking
          ? construction + chambers + ContestScore.existingChamberConnections(network, store)
          : construction + chambers + ties + reconCost + chamberRecon;
      double length = contestRanking ? newLength : newLength + reconLength;
      double score = options.score(cost, length + (contestRanking ? 0 : bends));
      return Double.isFinite(score) && score >= 0
          ? new Value(Math.max(0, score - 1e-7), cost, length, fixed)
          : new Value(0, 0, 0, 0);
    } catch (IllegalArgumentException | org.locationtech.jts.geom.TopologyException e) {
      // Failure to establish a bound grants no permission to discard the candidate.
      return new Value(0, 0, 0, 0);
    }
  }

  private static boolean stationary(
      Network.Edge edge, Network.Node start, Network.Node end, FeatureStore store) {
    String owner = end.buildingId == null ? end.oksId : end.buildingId;
    SpatialRules spatial =
        new SpatialRules(store.near(SpatialRules.expand(edge.geometry.getEnvelopeInternal(), 15)));
    int base = Rules.index(edge.dn);
    for (int i : new int[] {base, Rules.DN.length - 1})
      if (!spatial
          .assess(
              edge.geometry,
              Rules.DN[i],
              owner,
              start.type.equals("tie") ? start.point : null,
              end.type.equals("oks") ? end.point : null,
              end.entryWall)
          .valid()) return false;
    // repairClearances visits an old edge only when assess() fails at its final DN.
    return true;
  }
}
