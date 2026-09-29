package ru.teplotrassa.engine;

import java.util.*;
import org.locationtech.jts.geom.*;
import ru.teplotrassa.data.*;

/** Relative action descriptors and a permutation-invariant summary of remaining consumers. */
public final class ReinforcementFeatures {
  public static final List<String> NAMES;

  static {
    List<String> names = new ArrayList<>(CandidateFeatures.NAMES);
    names.addAll(
        List.of(
            "connected_fraction",
            "demand_flow_fraction",
            "remaining_target_min_100m",
            "remaining_target_mean_300m",
            "remaining_near_target_fraction",
            "remaining_near_corridor_fraction",
            "remaining_flow_100tph",
            "current_score_per_demand",
            "demand_neighbour_min_100m",
            "required_20"));
    NAMES = Collections.unmodifiableList(names);
  }

  private ReinforcementFeatures() {}

  static double[] context(
      double[] local,
      FeatureStore store,
      Network network,
      List<Feature> demands,
      Feature demand,
      Coordinate target,
      double currentScore) {
    double[] f = Arrays.copyOf(local, NAMES.size());
    Coordinate entry = InputData.portal(store.get(demand.text("_tt_entry_id")));
    LineString corridor = Geo.line(entry, target);
    double totalFlow = 0,
        remainingFlow = 0,
        min = Double.POSITIVE_INFINITY,
        sum = 0,
        nearTarget = 0,
        nearCorridor = 0,
        neighbour = Double.POSITIVE_INFINITY;
    int count = 0;
    for (Feature other : demands) {
      totalFlow += other.flow();
      if (network.connected.contains(other.id) || other.id.equals(demand.id)) continue;
      Coordinate point = InputData.portal(store.get(other.text("_tt_entry_id")));
      double d = point.distance(target);
      min = Math.min(min, d);
      sum += d;
      count++;
      neighbour = Math.min(neighbour, point.distance(entry));
      remainingFlow += other.flow();
      if (d <= 100) nearTarget++;
      if (corridor.distance(Geo.point(point)) <= 60) nearCorridor++;
    }
    int i = local.length;
    f[i++] = network.connected.size() / (double) Math.max(1, demands.size());
    f[i++] = demand.flow() / Math.max(1, totalFlow);
    f[i++] = count == 0 ? 0 : min / 100;
    f[i++] = count == 0 ? 0 : sum / count / 300;
    f[i++] = nearTarget / Math.max(1, count);
    f[i++] = nearCorridor / Math.max(1, count);
    f[i++] = remainingFlow / 100;
    f[i++] = currentScore / Math.max(1, demands.size());
    f[i++] = count == 0 ? 0 : neighbour / 100;
    f[i] = demands.size() / 20d;
    for (double v : f)
      if (!Double.isFinite(v)) throw new IllegalArgumentException("Nonfinite RL feature");
    return f;
  }
}
