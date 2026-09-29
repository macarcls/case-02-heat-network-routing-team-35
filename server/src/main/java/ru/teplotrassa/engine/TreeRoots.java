package ru.teplotrassa.engine;

import java.util.*;
import java.util.function.BooleanSupplier;
import org.locationtech.jts.geom.*;
import ru.teplotrassa.data.*;

/** Necessary entrance checks before spending route searches on an entire rooted tree. */
public final class TreeRoots {
  public static final class Candidate {
    public final String existingId;
    public final Coordinate point;
    public final double axis;
    public final List<String> blockedEntries;
    public double distance;

    Candidate(String id, Coordinate p, double a, List<String> blocked) {
      existingId = id;
      point = p.copy();
      axis = a;
      blockedEntries = List.copyOf(blocked);
    }

    public Map<String, Object> description() {
      Coordinate ll = Geo.ll(point);
      Map<String, Object> row = new LinkedHashMap<>();
      row.put("existingId", existingId);
      row.put("coordinates", new double[] {ll.x, ll.y});
      row.put("axisDegrees", Math.toDegrees(axis));
      row.put("blockedEntryIds", blockedEntries);
      row.put("entranceCompatible", blockedEntries.isEmpty());
      row.put("advisoryDistanceM", distance);
      return row;
    }
  }

  public final List<Candidate> selected = new ArrayList<>();
  public final Map<String, Object> diagnostics = new LinkedHashMap<>();

  public TreeRoots(
      FeatureStore store,
      List<Feature> demands,
      CorridorGraph graph,
      int limit,
      BooleanSupplier stop) {
    List<BuildingAccess> entries = new ArrayList<>();
    for (Feature d : demands)
      entries.add(BuildingAccess.resolve(store, store.get(d.text("_tt_entry_id"))));
    List<Feature> chambers = store.all("heat_chamber");
    Map<String, Candidate> candidates = new LinkedHashMap<>();
    Map<Long, List<String>> bearings = new HashMap<>();
    Coordinate center = new Coordinate();
    for (BuildingAccess a : entries) {
      center.x += a.entry.geometry.getCoordinate().x;
      center.y += a.entry.geometry.getCoordinate().y;
    }
    if (!entries.isEmpty()) {
      center.x /= entries.size();
      center.y /= entries.size();
    }
    for (Feature f : chambers) {
      if (stop.getAsBoolean()) break;
      if (InputValidator.existingDegree(f, store) >= 4) continue;
      Feature line = f;
      Set<String> seen = new HashSet<>();
      while (line != null && line.type.equals("heat_chamber") && seen.add(line.id))
        line = store.get(line.text("upstream_object_id"));
      if (line == null) {
        Envelope near = new Envelope(f.geometry.getEnvelopeInternal());
        near.expandBy(InputValidator.TOLERANCE);
        for (Feature candidate : store.near(near, "heat_network"))
          if (candidate.geometry.distance(f.geometry) <= InputValidator.TOLERANCE) {
            line = candidate;
            break;
          }
      }
      if (line != null && line.type.equals("heat_network"))
        add(
            candidates,
            bearings,
            f.id,
            f.geometry.getCoordinate(),
            axis((LineString) line.geometry, f.geometry.getCoordinate()),
            entries,
            demands);
    }
    for (Feature f : store.all("heat_network")) {
      if (stop.getAsBoolean()) break;
      LineString line = (LineString) f.geometry;
      Coordinate[] vertices = line.getCoordinates();
      for (int i = 1; i < vertices.length; i++) {
        LineString segment = Geo.line(vertices[i - 1], vertices[i]);
        if (segment.getLength() < .5) continue;
        TreeSet<Double> positions = new TreeSet<>();
        positions.add(segment.getLength() / 2);
        positions.add(Geo.index(segment, center));
        for (BuildingAccess a : entries)
          positions.add(Geo.index(segment, a.entry.geometry.getCoordinate()));
        for (double at : positions) {
          Coordinate p = Geo.at(segment, at);
          // A nearby existing chamber owns this connection location, as in Planner.targets().
          if (chambers.stream().anyMatch(c -> InputValidator.existingDegree(c, store) < 4
              && c.geometry.distance(Geo.point(p)) <= 10.000001))
            continue;
          add(candidates, bearings, f.id, p, axis(line, p), entries, demands);
        }
      }
    }
    List<Candidate> valid = new ArrayList<>();
    List<Map<String, Object>> rejected = new ArrayList<>();
    for (Candidate c : candidates.values()) {
      if (stop.getAsBoolean()) break;
      for (BuildingAccess a : entries) {
        Coordinate p = a.entry.geometry.getCoordinate();
        c.distance += graph == null ? p.distance(c.point) : graph.distance(p, c.point);
      }
      c.distance /= Math.max(1, entries.size());
      if (c.blockedEntries.isEmpty()) valid.add(c);
      else rejected.add(c.description());
    }
    valid.sort(
        Comparator.comparingDouble((Candidate c) -> c.distance)
            .thenComparing(c -> c.existingId)
            .thenComparing(c -> Geo.key(c.point)));
    // Preserve useful direction families, then spatial alternatives in the same family.
    for (Candidate c : valid) {
      if (selected.size() >= limit) break;
      if (selected.stream()
          .anyMatch(
              s ->
                  s.point.distance(c.point) < 20
                      && equivalentAxis(s.axis, c.axis) < Math.toRadians(.5))) continue;
      selected.add(c);
    }
    diagnostics.put("checkedLocations", candidates.size());
    diagnostics.put("entranceCompatibleLocations", valid.size());
    diagnostics.put("rejectedLocations", rejected);
    List<Map<String, Object>> chosen = new ArrayList<>();
    for (Candidate c : selected) chosen.add(c.description());
    diagnostics.put("selectedLocations", chosen);
    diagnostics.put("candidateLimit", limit);
    diagnostics.put("necessaryCheckOnly", true);
    diagnostics.put("method", "all_terminal_fixed_wall_direction_compatibility");
    diagnostics.put("globalInfeasibilityProven", false);
  }

  private static void add(
      Map<String, Candidate> out,
      Map<Long, List<String>> bearings,
      String id,
      Coordinate p,
      double axis,
      List<BuildingAccess> entries,
      List<Feature> demands) {
    List<String> blocked =
        bearings.computeIfAbsent(
            Double.doubleToLongBits(axis),
            ignored -> {
              List<String> ids = new ArrayList<>();
              for (int i = 0; i < entries.size(); i++) {
                BuildingAccess a = entries.get(i);
                if (a.gate != null
                    && !compatible(
                        a.gate.forDiameter(Rules.diameter(demands.get(i).flow())),
                        a.entry.geometry.getCoordinate(),
                        axis)) ids.add(a.entry.id);
              }
              return ids;
            });
    out.putIfAbsent(id + ":" + Geo.key(p), new Candidate(id, p, axis, blocked));
  }

  public static boolean compatible(BuildingAccess.Gate gate, Coordinate p, double axis) {
    // A narrow valid wall may lie between sampled compass rays. Never exclude
    // a root on the strength of a discrete angle screening heuristic.
    return !gate.walls.isEmpty();
  }

  public static double axis(LineString line, Coordinate p) {
    return RoutingQuality.upstreamBearing(line, p);
  }

  private static double equivalentAxis(double a, double b) {
    double delta = Math.abs(Math.IEEEremainder(a - b, Math.PI / 4));
    return Math.min(delta, Math.PI / 4 - delta);
  }
}
