package ru.teplotrassa.experiment;

import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;
import org.locationtech.jts.geom.Coordinate;
import ru.teplotrassa.api.ExperienceStore;
import ru.teplotrassa.engine.ContestScore;
import ru.teplotrassa.engine.Geo;
import ru.teplotrassa.engine.Network;
import ru.teplotrassa.engine.NetworkSnapshot;
import ru.teplotrassa.engine.Planner;
import ru.teplotrassa.engine.ReinforcementPolicy;
import ru.teplotrassa.engine.VariantSelector;

/** Small behavior checks for new scoring and safe reuse of old saved geometry. */
public final class Smoke {
  private static void require(boolean ok, String message) {
    if (!ok) throw new AssertionError(message);
  }

  private static Network network(String firstBranch, String secondBranch) {
    Network n = new Network();
    Network.Node root = new Network.Node("root", new Coordinate(0, 0), "tie");
    root.existingId = "chamber";
    n.nodes.put(root.id, root);
    Network.Node branch = new Network.Node("branch", new Coordinate(2, 0), "chamber");
    n.nodes.put(branch.id, branch);
    n.edges.put("trunk", new Network.Edge("trunk", root.id, branch.id,
        Geo.line(root.point, branch.point)));
    for (int i = 0; i < 3; i++) {
      String label = "abc".substring(i, i + 1);
      Network.Node terminal = new Network.Node(label, new Coordinate(5, i + 1), "oks");
      terminal.entryId = label;
      n.nodes.put(label, terminal);
      String parent = label.equals(firstBranch) || label.equals(secondBranch)
          ? branch.id : root.id;
      n.edges.put(label, new Network.Edge(label, parent, label,
          Geo.line(n.nodes.get(parent).point, terminal.point)));
      n.connected.add(label);
    }
    return n;
  }

  public static void main(String[] args) throws Exception {
    Network n = network("a", "b");
    NeuralExperiment.Store store = new NeuralExperiment.Store();
    store.add(NeuralExperiment.feature("chamber", "heat_chamber", Geo.point(new Coordinate(0, 0))));
    require(ContestScore.existingChamberConnections(n, store) == 10e6,
        "each of the two new edges at the existing chamber costs one tie-in");
    n.nodes.get("root").existingId = "pipe";
    store.add(NeuralExperiment.feature("pipe", "heat_network",
        Geo.line(new Coordinate(0, 0), new Coordinate(1, 0))));
    require(ContestScore.existingChamberConnections(n, store) == 0,
        "new chamber on existing pipe already includes the connection");
    require(ContestScore.rounded(1.23445) == 1.2345, "single HALF_UP rounding");
    require(VariantSelector.structurallyDifferent(network("a", "b"), network("a", "c")),
        "groups beyond a shared branch distinguish the topologies");

    Path scratch = Files.createTempDirectory("teplotrassa-contest-smoke-");
    ExperienceStore experience = new ExperienceStore(scratch, NeuralExperiment.JSON);
    String owner = "a".repeat(64), input = "b".repeat(64);
    Planner.Options current = new Planner.Options();
    current.routingStrategy = "tree";
    current.treeSingleRootRequired = true;
    Planner.Options previous = current.copy();
    previous.rankingProfile = "appendix";
    String oldVersion = "LCT-2026-09-27-v1.5-network-walls";
    String namespace = ExperienceStore.hash((oldVersion + ":active-search-v1:"
        + ReinforcementPolicy.bundled().sha256).getBytes()).substring(0, 16);
    Path former = scratch.resolve("experience-v1").resolve(namespace).resolve(owner)
        .resolve("territories").resolve(experience.key(input, previous)).resolve("best.json");
    ObjectNode saved = NeuralExperiment.JSON.createObjectNode();
    ArrayNode array = saved.putArray("networks");
    array.add(NetworkSnapshot.write(network("a", "b"), NeuralExperiment.JSON));
    experience.write(former, saved);
    ExperienceStore.Session loaded = experience.open(owner, input, UUID.randomUUID(), current);
    require(loaded.networks.size() == 1, "old route must be considered again");
    require(!loaded.treeResumed, "old model weights must not be imported");
    System.out.println("score, topology, and legacy-network migration: OK");
  }
}
