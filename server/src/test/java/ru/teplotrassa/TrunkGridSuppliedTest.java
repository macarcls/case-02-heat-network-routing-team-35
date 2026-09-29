package ru.teplotrassa;

import static org.junit.jupiter.api.Assertions.*;
import static ru.teplotrassa.EngineTest.*;

import java.nio.file.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import ru.teplotrassa.data.*;
import ru.teplotrassa.engine.*;

class TrunkGridSuppliedTest {
  @Test
  void corridorTreeKeepsOriginalEntriesAndPassesExactChecks() throws Exception {
    org.junit.jupiter.api.Assumptions.assumeTrue(Boolean.getBoolean("trunkGridSupplied"));
    Store raw = new Store();
    new GeoJsonInput(JSON).read(Path.of("../examples/supplied.geojson"), raw::add, false);
    Planner.Options o = new Planner.Options();
    o.routingStrategy = "tree";
    o.treeSingleRootRequired = true;
    o.mode = "depth";
    o.gridM = 20;
    o.dataMode = "scenario";
    o.existingLoadPercent = 50d;
    FeatureStore store = InputData.scenario(raw, o);
    List<Feature> demands = InputData.demands(store);
    TreeRoots roots = new TreeRoots(store, demands, null, 5, () -> false);
    int complete = 0;
    for (TreeRoots.Candidate root : roots.selected) {
      TrunkGrid graph = new TrunkGrid(store, demands, root, o, () -> false);
      System.out.println(
          "GRID " + root.existingId + " " + JSON.writeValueAsString(graph.diagnostics));
      for (int order = 0; order < demands.size() + 3; order++) {
        Network network = graph.build(order);
        try {
          Evaluation value = new Evaluation(network, store, demands, true, o.maxDepthM, o);
          System.out.println("GRID_RESULT " + order + " " + JSON.writeValueAsString(value.summary));
          if (network.connected.size() != demands.size()) continue;
          assertEquals(17, network.connected.size());
          assertEquals(true, value.checks.get("originalEndpointsPreserved"));
          assertEquals(1, network.roots().size());
          complete++;
        } catch (Exception e) {
          System.out.println(
              "GRID_REJECTED "
                  + order
                  + " connected="
                  + network.connected.size()
                  + " missing="
                  + demands.stream()
                      .filter(d -> !network.connected.contains(d.id))
                      .map(d -> d.id)
                      .collect(java.util.stream.Collectors.toList())
                  + " "
                  + e.getMessage());
        }
      }
    }
    assertTrue(
        complete > 0,
        "At least one corridor tree must pass exact validation before entrance completion");
  }
}
