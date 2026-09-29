package ru.teplotrassa.engine;

import java.util.*;
import java.util.stream.Collectors;
import org.locationtech.jts.geom.*;

/** Selects distinct complete networks whose assigned indicators actually improve. */
public final class VariantSelector {
  private VariantSelector() {}

  private static double metric(Evaluation e, String key) {
    return ((Number) e.summary.get(key)).doubleValue();
  }

  private static Geometry lines(Network n) {
    List<Geometry> edges = new ArrayList<>();
    for (Network.Edge edge : n.edges.values()) edges.add(edge.geometry);
    return Geo.GF.buildGeometry(edges);
  }

  private static double unmatched(Network candidate, Geometry reference) {
    double outside = 0, total = 0;
    for (Network.Edge edge : candidate.edges.values()) {
      double length = edge.geometry.getLength();
      int slices = Math.max(1, (int) Math.ceil(length / 12));
      for (int i = 0; i < slices; i++) {
        double piece = length / slices;
        Coordinate p = Geo.at(edge.geometry, (i + .5) * piece);
        if (reference.isEmpty() || reference.distance(Geo.point(p)) > 6) outside += piece;
      }
      total += length;
    }
    return total < 1e-6 ? 0 : outside / total;
  }

  public static double difference(Evaluation a, Evaluation b) {
    return Math.max(unmatched(a.network, lines(b.network)), unmatched(b.network, lines(a.network)));
  }

  /** Only changes of source or of actual branching can distinguish close geometries. */
  public static boolean structurallyDifferent(Network a, Network b) {
    Set<String> sourcesA = new TreeSet<>(), sourcesB = new TreeSet<>();
    for (Network.Node n : a.roots()) sourcesA.add(String.valueOf(n.existingId));
    for (Network.Node n : b.roots()) sourcesB.add(String.valueOf(n.existingId));
    return !sourcesA.equals(sourcesB) || !branchGroups(a).equals(branchGroups(b));
  }

  private static Set<String> branchGroups(Network network) {
    Set<String> groups = new TreeSet<>();
    for (Network.Edge edge : network.edges.values()) {
      Set<String> terminals = new TreeSet<>();
      Deque<String> pending = new ArrayDeque<>();
      pending.add(edge.end);
      while (!pending.isEmpty()) {
        String id = pending.remove();
        Network.Node node = network.nodes.get(id);
        if (node == null) continue;
        if (node.entryId != null) terminals.add(node.entryId);
        for (Network.Edge child : network.children(id)) pending.add(child.end);
      }
      if (terminals.size() > 1 && terminals.size() < network.connected.size())
        groups.add(String.join("+", terminals));
    }
    return groups;
  }

  private static boolean distinct(Evaluation candidate, List<Evaluation> selected) {
    for (Evaluation earlier : selected) {
      double routeDifference = difference(candidate, earlier);
      if (routeDifference < .06
          && !(routeDifference >= .03
              && structurallyDifferent(candidate.network, earlier.network))) return false;
    }
    return true;
  }

  private static boolean improved(Evaluation candidate, Evaluation reference, String objective) {
    double baseline = metric(reference, objective);
    return metric(candidate, objective) < baseline - Math.max(.001, baseline * .005);
  }

  private static Optional<Evaluation> installationFor(
      List<Evaluation> pool, Evaluation balanced, Evaluation earth, boolean requireTradeoff) {
    return pool.stream()
        .filter(e -> e != balanced && e != earth)
        .filter(e -> improved(e, balanced, "installation_index"))
        .filter(e -> improved(e, earth, "installation_index"))
        .filter(e -> !requireTradeoff || improved(earth, e, "earthwork_index"))
        .filter(e -> distinct(e, List.of(balanced, earth)))
        .min(Comparator.comparingDouble((Evaluation e) -> metric(e, "installation_index"))
            .thenComparingDouble(e -> e.score));
  }

  public static List<Evaluation> choose(
      List<Evaluation> pool, int limit, Map<String, Object> diagnostics) {
    diagnostics.put("consideredFullNetworks", pool.size());
    diagnostics.put("minimumDifferentRouteFraction", .06);
    diagnostics.put("comparisonCorridorRadiusM", 6);
    diagnostics.put("structuralDifferenceMinimumRouteFraction", .03);
    diagnostics.put("structuralDifferenceSignals", List.of("existing_tie_in", "terminal_branch_groups"));
    diagnostics.put("soilDataAvailable", false);
    List<Evaluation> selected = new ArrayList<>();
    if (pool.isEmpty()) return selected;
    Evaluation balanced = pool.stream().min(Comparator.comparingDouble(e -> e.score)).orElseThrow();
    balanced.summary.put("variant_role", "balanced");
    balanced.summary.put("variant_name", "Лучший по общей оценке");
    selected.add(balanced);
    if (limit == 1) return selected;

    List<Evaluation> earthCandidates = pool.stream()
        .filter(e -> e != balanced)
        .filter(e -> improved(e, balanced, "earthwork_index"))
        .filter(e -> distinct(e, selected))
        .sorted(Comparator.comparingDouble((Evaluation e) -> metric(e, "earthwork_index"))
            .thenComparingDouble(e -> e.score)).collect(Collectors.toList());
    if (earthCandidates.isEmpty()) {
      diagnostics.put("earthwork_indexNotFound",
          "Проверенный маршрут с меньшими земляными работами и другой геометрией не найден");
    } else {
      Evaluation earth = earthCandidates.get(0), installation = null;
      boolean tradeoffFound = false;
      // Prefer a true tradeoff: the second route has less earthwork than the
      // third, while the third is simpler to install. Fall back honestly if
      // these two engineering indicators happen to improve together.
      if (limit > 2) for (boolean tradeoff : new boolean[] {true, false}) {
        for (Evaluation candidate : earthCandidates) {
          Optional<Evaluation> simpler = installationFor(pool, balanced, candidate, tradeoff);
          if (simpler.isPresent()) {
            earth = candidate;
            installation = simpler.get();
            tradeoffFound = tradeoff;
            break;
          }
        }
        if (installation != null) break;
      }
      if (installation != null) diagnostics.put("earthworkTradeoffFound", tradeoffFound);
      earth.summary.put("variant_role", "earthworks");
      earth.summary.put("variant_name", "Проще земляные работы");
      selected.add(earth);
      if (installation != null) {
        installation.summary.put("variant_role", "installation");
        installation.summary.put("variant_name", "Проще прокладка");
        selected.add(installation);
        if (!tradeoffFound) diagnostics.put("tradeoffNote",
            "Третий вариант также уменьшил земляные работы; отдельного превосходства второго по грунту не найдено");
      } else if (limit > 2) diagnostics.put("installation_indexNotFound",
          "Маршрут с более простой прокладкой, чем первые два, и другой геометрией не найден");
    }
    diagnostics.put("selectedRoles", selected.stream()
        .map(e -> e.summary.get("variant_role")).toArray());
    return selected;
  }
}
