package ru.teplotrassa.engine;

import java.util.*;
import org.locationtech.jts.geom.*;
import ru.teplotrassa.data.*;

public final class Reconstruction {
  public static class Piece {
    public final Feature existing;
    public final LineString geometry;
    public final double added;
    public final int required;

    Piece(Feature existing, LineString geometry, double added, int required) {
      this.existing = existing;
      this.geometry = geometry;
      this.added = added;
      this.required = required;
    }

    public double cost() {
      return geometry.getLength() * Rules.RECON[Rules.index(required)];
    }
  }

  public final List<Piece> pieces = new ArrayList<>();
  public final Map<String, Integer> chambers = new LinkedHashMap<>();

  public static Reconstruction calculate(Network network, FeatureStore store) {
    Map<String, TreeMap<Double, Double>> events = new LinkedHashMap<>();
    for (Network.Node root : network.roots()) {
      double flow = network.rootFlow(root);
      Feature at = store.get(root.existingId);
      Set<String> seen = new HashSet<>();
      boolean first = true;
      while (!at.type.equals("source")) {
        if (!seen.add(at.id))
          throw new IllegalArgumentException("Цикл существующей сети: " + at.id);
        if (at.type.equals("heat_network")) {
          LineString line = (LineString) at.geometry;
          double a = 0, b = line.getLength();
          if (first) {
            double x = Geo.index(line, root.point);
            if (InputValidator.upstreamEnd(at, store).distance(line.getCoordinateN(0)) < .001)
              b = x;
            else a = x;
          }
          if (b - a > 1e-7) {
            TreeMap<Double, Double> m = events.computeIfAbsent(at.id, k -> new TreeMap<>());
            m.merge(a, flow, Double::sum);
            m.merge(b, -flow, Double::sum);
          }
        }
        first = false;
        at = store.get(at.text("upstream_object_id"));
        if (at == null) throw new IllegalArgumentException("Отсутствует upstream объект");
      }
    }
    Reconstruction out = new Reconstruction();
    for (Map.Entry<String, TreeMap<Double, Double>> entry : events.entrySet()) {
      Feature old = store.get(entry.getKey());
      double added = 0, previous = 0;
      for (Map.Entry<Double, Double> event : entry.getValue().entrySet()) {
        if (event.getKey() - previous > 1e-7 && added > 1e-7) {
          int required = Rules.diameter(old.flow() + added);
          if (required > old.dn())
            out.pieces.add(
                new Piece(
                    old,
                    Geo.part((LineString) old.geometry, previous, event.getKey()),
                    added,
                    required));
        }
        added += event.getValue();
        previous = event.getKey();
      }
    }
    for (Network.Node root : network.roots()) {
      Feature existing = store.get(root.existingId);
      if (!existing.type.equals("heat_chamber")) continue;
      int dn = existing.dn();
      for (Network.Edge e : network.children(root.id)) dn = Math.max(dn, e.dn);
      for (Piece piece : out.pieces)
        if (piece.geometry.getBoundary().distance(existing.geometry) < .26)
          dn = Math.max(dn, piece.required);
      if (dn > existing.dn()) out.chambers.merge(existing.id, dn, Math::max);
    }
    return out;
  }

  public int newChamberDiameter(Network.Node node, Network network, FeatureStore store) {
    int dn = 50;
    for (Network.Edge e : network.incident(node.id)) dn = Math.max(dn, e.dn);
    if (node.existingId != null) {
      Feature f = store.get(node.existingId);
      dn = Math.max(dn, f.dn());
      for (Piece p : pieces)
        if (p.existing.id.equals(f.id) && p.geometry.distance(Geo.point(node.point)) < .26)
          dn = Math.max(dn, p.required);
    }
    return dn;
  }
}
