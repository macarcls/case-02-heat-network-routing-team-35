package ru.teplotrassa.engine;

import java.math.BigDecimal;
import java.math.RoundingMode;
import ru.teplotrassa.data.Feature;
import ru.teplotrassa.data.FeatureStore;

/** The flat-stage comparison score. Reconstruction remains a separate engineering estimate. */
public final class ContestScore {
  private ContestScore() {}

  /** Each new edge entering an existing chamber is a separate charge. */
  public static double existingChamberConnections(Network network, FeatureStore store) {
    double price = 0;
    for (Network.Node root : network.roots()) {
      Feature existing = root.existingId == null ? null : store.get(root.existingId);
      if (existing != null && existing.type.equals("heat_chamber"))
        price += 5e6 * network.children(root.id).size();
    }
    return price;
  }

  public static double raw(double newConstruction, double newChambers,
      double existingChamberConnections, double newLength) {
    return Rules.score(newConstruction + newChambers + existingChamberConnections, newLength);
  }

  public static double rounded(double raw) {
    return BigDecimal.valueOf(raw).setScale(4, RoundingMode.HALF_UP).doubleValue();
  }
}
