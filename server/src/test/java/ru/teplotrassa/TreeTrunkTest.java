package ru.teplotrassa;

import static org.junit.jupiter.api.Assertions.*;
import static ru.teplotrassa.EngineTest.*;

import java.lang.reflect.*;
import java.nio.file.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.locationtech.jts.geom.*;
import ru.teplotrassa.data.*;
import ru.teplotrassa.engine.*;

class TreeTrunkTest {
  private static Planner.Options options() {
    Planner.Options o = new Planner.Options();
    o.routingStrategy = "tree";
    o.treeSingleRootRequired = true;
    o.treeLearning = false;
    o.treeGroupRepair = false;
    o.treeRepairPasses = 0;
    return o;
  }

  @Test
  void suppliedRootDirectionsAreScreenedForEveryEntrance() throws Exception {
    Store raw = new Store();
    new GeoJsonInput(JSON).read(Path.of("../examples/supplied.geojson"), raw::add, false);
    Planner.Options o = options();
    o.dataMode = "scenario";
    o.existingLoadPercent = 50d;
    FeatureStore store = InputData.scenario(raw, o);
    TreeRoots roots = new TreeRoots(store, InputData.demands(store), null, 5, () -> false);
    assertFalse(roots.selected.isEmpty());
    assertTrue(roots.selected.stream().allMatch(c -> c.blockedEntries.isEmpty()));
    @SuppressWarnings("unchecked")
    List<Map<String, Object>> rejected =
        (List<Map<String, Object>>) roots.diagnostics.get("rejectedLocations");
    for (String id : List.of("106", "110"))
      assertTrue(
          rejected.stream()
              .anyMatch(
                  r ->
                      r.get("existingId").equals(id)
                          && ((List<?>) r.get("blockedEntryIds")).contains("10")));
    BuildingAccess.Gate gate = BuildingAccess.resolve(store, store.get("10")).gate.forDiameter(125);
    assertEquals(1.8030186378, gate.nearestWall.getLength(), 1e-5);
    assertEquals(1.2030186384, gate.walls.get(0).getLength(), 1e-5);
  }

  private static Store openMap() {
    return new Store()
        .add(feature("source", "source", Geo.point(c(0, 0))))
        .add(
            feature(
                "old",
                "heat_network",
                Geo.line(c(0, 0), c(0, 100)),
                "diameter",
                300,
                "flow_tph",
                2,
                "upstream_object_id",
                "source"))
        .add(feature("a", "oks_connection_point", Geo.point(c(130, 50)), "flow_tph", 50))
        .add(feature("b", "oks_connection_point", Geo.point(c(150, 10)), "flow_tph", 50))
        .add(feature("other", "oks_connection_point", Geo.point(c(80, 100)), "flow_tph", 5));
  }

  private static void node(
      Network n, String id, String type, Coordinate p, String entry, double flow) {
    Network.Node v = new Network.Node(id, p, type);
    v.entryId = entry;
    v.oksId = entry;
    v.demand = flow;
    if (type.equals("tie")) v.existingId = "old";
    n.nodes.put(id, v);
  }

  private static void edge(Network n, String id, String a, String b, Coordinate... points) {
    n.edges.put(id, new Network.Edge(id, a, b, Geo.line(points)));
  }

  private static Network longStem() {
    Network n = new Network();
    node(n, "r", "tie", c(0, 100), null, 0);
    node(n, "j", "chamber", c(50, 100), null, 0);
    node(n, "h", "chamber", c(150, 50), null, 0);
    node(n, "a", "oks", c(130, 50), "a", 50);
    node(n, "b", "oks", c(150, 10), "b", 50);
    node(n, "other", "oks", c(80, 100), "other", 5);
    edge(n, "main", "r", "j", c(0, 100), c(50, 100));
    edge(n, "detour", "j", "h", c(50, 100), c(50, 250), c(250, 250), c(250, 50), c(150, 50));
    edge(n, "a-leaf", "h", "a", c(150, 50), c(130, 50));
    edge(n, "b-leaf", "h", "b", c(150, 50), c(150, 10));
    edge(n, "other-leaf", "j", "other", c(50, 100), c(80, 100));
    n.connected.addAll(List.of("a", "b", "other"));
    return n;
  }

  @Test
  void movingWholeSubtreePreservesItsConsumersAndCutsDetour() throws Exception {
    Store store = openMap();
    Planner.Options o = options();
    Planner planner = new Planner(store, o, () -> false, (p, t) -> {});
    List<Feature> demands = InputData.demands(store);
    Network original = longStem();
    String snapshot = NetworkSnapshot.write(original, JSON).toString();
    Evaluation before = planner.validateIncumbent(original);
    Method method =
        Planner.class.getDeclaredMethod(
            "transplantStem", Evaluation.class, Network.Edge.class, List.class);
    method.setAccessible(true);
    Evaluation after =
        (Evaluation) method.invoke(planner, before, before.network.edges.get("detour"), demands);
    assertNotNull(after);
    assertTrue(after.score < before.score);
    assertEquals(1, after.network.roots().size());
    assertEquals(3, after.network.connected.size());
    assertTrue(
        (double) after.summary.get("new_network_length")
            < (double) before.summary.get("new_network_length") - 300);
    for (String id : List.of("a", "b")) {
      assertTrue(after.network.nodes.get(id).point.equals2D(original.nodes.get(id).point));
      assertEquals(
          original.edges.get(id + "-leaf").geometry.toText(),
          after.network.edges.get(id + "-leaf").geometry.toText());
    }
    assertEquals(snapshot, NetworkSnapshot.write(original, JSON).toString());
  }

  @Test
  void strictTreeNeverReturnsAnExtraExistingTie() {
    Store store = openMap();
    Planner.Options o = options();
    o.treeBeamWidth = 3;
    o.treeExpansion = 2;
    Planner.Result result = new Planner(store, o, () -> false, (p, t) -> {}).calculate();
    assertFalse(result.variants.isEmpty());
    for (Evaluation v : result.variants) {
      assertEquals(1, v.network.roots().size());
      assertEquals(3, v.network.connected.size());
      assertEquals(true, v.checks.get("rootPolicyValid"));
    }
  }

  @Test
  void strictEvaluationRejectsForestInsteadOfSilentlyReturningIt() {
    Store store = openMap();
    Network n = longStem();
    node(n, "second", "tie", c(0, 50), null, 0);
    n.edges.remove("a-leaf");
    edge(n, "new-tie", "second", "a", c(0, 50), c(130, 50));
    IllegalArgumentException failure =
        assertThrows(
            IllegalArgumentException.class,
            () -> new Planner(store, options(), () -> false, (p, t) -> {}).validateIncumbent(n));
    assertTrue(failure.getMessage().contains("SINGLE_ROOT_REQUIRED"));
  }

  @Test
  void sharedTrunkUsesOnlyRealConsumerEndpoints() throws Exception {
    Store store = openMap();
    Planner.Options o = options();
    Planner planner = new Planner(store, o, () -> false, (p, t) -> {});
    List<Feature> demands = InputData.demands(store);
    Map<String, BuildingAccess> access = new LinkedHashMap<>();
    for (Feature d : demands)
      access.put(d.id, BuildingAccess.resolve(store, store.get(d.text("_tt_entry_id"))));
    Class<?> target = Class.forName("ru.teplotrassa.engine.Planner$Target");
    Constructor<?> constructor = target.getDeclaredConstructor(Coordinate.class);
    constructor.setAccessible(true);
    Object root = constructor.newInstance(c(0, 100));
    Field id = target.getDeclaredField("existingId");
    id.setAccessible(true);
    id.set(root, "old");
    Method method =
        Planner.class.getDeclaredMethod(
            "buildSharedGroup",
            Network.class,
            List.class,
            Map.class,
            List.class,
            target,
            boolean.class);
    method.setAccessible(true);
    Network built =
        (Network) method.invoke(planner, new Network(), demands, access, demands, root, false);
    assertEquals(3, built.connected.size());
    assertEquals(1, built.roots().size());
    Evaluation evaluated = planner.validateIncumbent(built);
    assertEquals(true, evaluated.checks.get("originalEndpointsPreserved"));
    assertEquals(3, built.nodes.values().stream().filter(n -> n.type.equals("oks")).count());
    assertTrue(built.nodes.values().stream().anyMatch(n -> n.type.equals("chamber")));
  }
}
