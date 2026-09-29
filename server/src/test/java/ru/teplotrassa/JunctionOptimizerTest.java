package ru.teplotrassa;

import static org.junit.jupiter.api.Assertions.*;
import static ru.teplotrassa.EngineTest.*;

import java.util.*;
import org.junit.jupiter.api.Test;
import org.locationtech.jts.geom.*;
import ru.teplotrassa.data.*;
import ru.teplotrassa.engine.*;

class JunctionOptimizerTest {
  private static void edge(Network n, String id, String a, String b, Coordinate... p) {
    n.edges.put(id, new Network.Edge(id, a, b, Geo.line(p)));
  }

  private static void node(Network n, String id, String type, Coordinate p) {
    Network.Node v = new Network.Node(id, p, type);
    if (type.equals("tie")) v.existingId = "old";
    if (type.equals("oks")) v.entryId = id;
    n.nodes.put(id, v);
  }

  private static Planner.Options options() {
    Planner.Options o = new Planner.Options();
    o.routingStrategy = "tree";
    o.treeSingleRootRequired = true;
    o.treeRepairPasses = 2;
    return o;
  }

  private static Store map() {
    return new Store()
        .add(feature("source", "source", Geo.point(c(0, -100))))
        .add(
            feature(
                "old",
                "heat_network",
                Geo.line(c(0, -100), c(0, 0)),
                "diameter",
                300,
                "flow_tph",
                2,
                "upstream_object_id",
                "source"))
        .add(feature("a", "oks_connection_point", Geo.point(c(200, 0)), "flow_tph", 10))
        .add(feature("b", "oks_connection_point", Geo.point(c(100, 100)), "flow_tph", 10));
  }

  private static Network detour() {
    Network n = new Network();
    node(n, "r", "tie", c(0, 0));
    node(n, "j", "chamber", c(100, 50));
    node(n, "a", "oks", c(200, 0));
    node(n, "b", "oks", c(100, 100));
    edge(n, "stem", "r", "j", c(0, 0), c(0, 50), c(100, 50));
    edge(n, "a", "j", "a", c(100, 50), c(200, 50), c(200, 0));
    edge(n, "b", "j", "b", c(100, 50), c(100, 100));
    return n;
  }

  @Test
  void movesJunctionAndAllIncidentPipesWithoutMovingInputs() {
    Store s = map();
    Planner.Options o = options();
    Planner p = new Planner(s, o, () -> false, (n, m) -> {});
    Evaluation before = p.validateIncumbent(detour());
    String untouched = NetworkSnapshot.write(before.network, JSON).toString();
    List<Evaluation> saved = new ArrayList<>();
    JunctionOptimizer search =
        new JunctionOptimizer(s, o, () -> false, p::validateIncumbent, (n, m) -> {});
    Evaluation after = search.improve(before, saved::add);
    assertTrue(after.score < before.score);
    assertEquals(300d, (double) after.summary.get("new_network_length"), 1e-5);
    assertFalse(after.network.nodes.get("j").point.equals2D(before.network.nodes.get("j").point));
    for (String id : List.of("r", "a", "b"))
      assertTrue(after.network.nodes.get(id).point.equals2D(before.network.nodes.get(id).point));
    assertEquals(untouched, NetworkSnapshot.write(before.network, JSON).toString());
    assertFalse(saved.isEmpty());
    assertEquals(2, after.network.connected.size());
    assertEquals(1, after.network.roots().size());
  }

  @Test
  void cancelledSearchKeepsValidatedIncumbent() {
    Store s = map();
    Planner.Options o = options();
    Planner p = new Planner(s, o, () -> false, (n, m) -> {});
    Evaluation before = p.validateIncumbent(detour());
    JunctionOptimizer search =
        new JunctionOptimizer(s, o, () -> true, p::validateIncumbent, (n, m) -> {});
    assertSame(
        before, search.improve(before, v -> fail("Cancelled search must not save a candidate")));
    assertEquals("cancelled", search.diagnostics.get("stopReason"));
  }

  @Test
  void pairSearchCanMoveTwoJunctionsTogether() throws Exception {
    Store s =
        new Store()
            .add(feature("source", "source", Geo.point(c(0, -100))))
            .add(
                feature(
                    "old",
                    "heat_network",
                    Geo.line(c(0, -100), c(0, 0)),
                    "diameter",
                    300,
                    "flow_tph",
                    2,
                    "upstream_object_id",
                    "source"))
            .add(feature("a", "oks_connection_point", Geo.point(c(50, -50)), "flow_tph", 10))
            .add(feature("b", "oks_connection_point", Geo.point(c(200, 0)), "flow_tph", 10))
            .add(feature("c", "oks_connection_point", Geo.point(c(150, 100)), "flow_tph", 10));
    Network n = new Network();
    node(n, "r", "tie", c(0, 0));
    node(n, "j", "chamber", c(50, 50));
    node(n, "k", "chamber", c(150, 50));
    node(n, "a", "oks", c(50, -50));
    node(n, "b", "oks", c(200, 0));
    node(n, "c", "oks", c(150, 100));
    edge(n, "rj", "r", "j", c(0, 0), c(0, 50), c(50, 50));
    edge(n, "ja", "j", "a", c(50, 50), c(50, -50));
    edge(n, "jk", "j", "k", c(50, 50), c(150, 50));
    edge(n, "kb", "k", "b", c(150, 50), c(200, 50), c(200, 0));
    edge(n, "kc", "k", "c", c(150, 50), c(150, 100));
    Planner.Options o = options();
    Planner p = new Planner(s, o, () -> false, (i, m) -> {});
    Evaluation before = p.validateIncumbent(n);
    JunctionOptimizer optimizer =
        new JunctionOptimizer(s, o, () -> false, p::validateIncumbent, (i, m) -> {});
    var method =
        JunctionOptimizer.class.getDeclaredMethod("improvePatch", Evaluation.class, List.class);
    method.setAccessible(true);
    Evaluation after = (Evaluation) method.invoke(optimizer, before, List.of("j", "k"));
    assertTrue(after.score < before.score);
    assertTrue(
        (double) after.summary.get("new_network_length")
            < (double) before.summary.get("new_network_length") - 49);
    assertFalse(after.network.nodes.get("j").point.equals2D(before.network.nodes.get("j").point));
    assertTrue(
        !after.network.nodes.containsKey("k")
            || !after.network.nodes.get("k").point.equals2D(before.network.nodes.get("k").point));
    for (String id : List.of("r", "a", "b", "c"))
      assertTrue(after.network.nodes.get(id).point.equals2D(before.network.nodes.get(id).point));
  }
}
