package ru.teplotrassa.engine;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.util.*;
import java.util.function.*;
import org.locationtech.jts.geom.*;
import ru.teplotrassa.data.*;

public final class Planner {
  // Accept the retired field from older clients without restoring a search deadline.
  @JsonIgnoreProperties({"timeLimitSeconds", "allowEconomicOmission"})
  public static class Options {
    public Double existingLoadPercent = null;
    public String dataMode = "strict",
        rankingProfile = "contest",
        mode = "plan",
        depthRuleProfile = "appendix",
        neuralGuidance = "baseline",
        routingStrategy = "classic";
    /** Internal exploratory objective. The public score remains the common balanced score. */
    String variantObjective = "balanced";
    public double gridM = 5, minDepthM = .7, maxDepthM = 6;
    public int maxCells = 100000, candidateLimit = 8, variantLimit = 3;
    public int rlEpisodes = 13;
    public int treeBeamWidth = 5,
        treeExpansion = 3,
        treeRepairPasses = 2,
        treeRepairCandidates = 8,
        treeGraphMaxNodes = 8000;
    public boolean treeLearning = false;
    public boolean treeSingleRootRequired = true;
    public boolean treeJunctionRepair = true;
    public boolean treeGeometricSearch = true;
    public boolean treeGroupRepair = true, treeSingleRootTrial = true, treeResumeFromBest = true;
    public int treeLearningRounds = 2, treeTrainingSteps = 16;
    public double treeTrainingRate = .001;
    public long rlSeed = 71;
    public double rlTemperature = .7;
    public boolean rlLearning = false, rlRemember = true;
    public int rlBatchSize = 4;
    public double rlLearningRate = .003;

    public Options copy() {
      Options o = new Options();
      o.existingLoadPercent = existingLoadPercent;
      o.dataMode = dataMode;
      o.rankingProfile = rankingProfile;
      o.mode = mode;
      o.depthRuleProfile = depthRuleProfile;
      o.neuralGuidance = neuralGuidance;
      o.routingStrategy = routingStrategy;
      o.variantObjective = variantObjective;
      o.gridM = gridM;
      o.minDepthM = minDepthM;
      o.maxDepthM = maxDepthM;
      o.maxCells = maxCells;
      o.candidateLimit = candidateLimit;
      o.variantLimit = variantLimit;
      o.rlEpisodes = rlEpisodes;
      o.treeBeamWidth = treeBeamWidth;
      o.treeExpansion = treeExpansion;
      o.treeRepairPasses = treeRepairPasses;
      o.treeRepairCandidates = treeRepairCandidates;
      o.treeGraphMaxNodes = treeGraphMaxNodes;
      o.treeLearning = treeLearning;
      o.treeSingleRootRequired = treeSingleRootRequired;
      o.treeJunctionRepair = treeJunctionRepair;
      o.treeGeometricSearch = treeGeometricSearch;
      o.treeGroupRepair = treeGroupRepair;
      o.treeSingleRootTrial = treeSingleRootTrial;
      o.treeResumeFromBest = treeResumeFromBest;
      o.treeLearningRounds = treeLearningRounds;
      o.treeTrainingSteps = treeTrainingSteps;
      o.treeTrainingRate = treeTrainingRate;
      o.rlSeed = rlSeed;
      o.rlTemperature = rlTemperature;
      o.rlLearning = rlLearning;
      o.rlRemember = rlRemember;
      o.rlBatchSize = rlBatchSize;
      o.rlLearningRate = rlLearningRate;
      return o;
    }

    public double score(double cost, double length) {
      return rankingProfile.equals("protocol")
          ? .3 * cost / 25e6 + .7 * length / 100
          : Rules.score(cost, length);
    }

    public void validate() {
      if (existingLoadPercent != null
              && (!Double.isFinite(existingLoadPercent)
                  || existingLoadPercent < 0
                  || existingLoadPercent > 100)
          || !List.of("strict", "scenario").contains(dataMode)
          || !List.of("contest", "appendix", "protocol").contains(rankingProfile)
          || !List.of("plan", "depth").contains(mode)
          || !List.of("appendix", "protocol").contains(depthRuleProfile)
          || !List.of("baseline", "off", "bounds", "on").contains(neuralGuidance)
          || !List.of("classic", "reinforcement", "tree").contains(routingStrategy)
          || !List.of("balanced", "earthworks", "installation").contains(variantObjective)
          || treeLearningRounds < 2
          || treeLearningRounds > 8
          || treeTrainingSteps < 1
          || treeTrainingSteps > 64
          || !Double.isFinite(treeTrainingRate)
          || treeTrainingRate <= 0
          || treeTrainingRate > .003
          || treeBeamWidth < 1
          || treeBeamWidth > 16
          || treeExpansion < 1
          || treeExpansion > 12
          || treeRepairPasses < 0
          || treeRepairPasses > 10
          || treeRepairCandidates < 1
          || treeRepairCandidates > 32
          || treeGraphMaxNodes < 500
          || treeGraphMaxNodes > 50000
          || rlEpisodes < 1
          || rlEpisodes > 10000
          || !Double.isFinite(rlTemperature)
          || rlTemperature < 0
          || rlTemperature > 5
          || rlBatchSize < 2
          || rlBatchSize > 32
          || !Double.isFinite(rlLearningRate)
          || rlLearningRate <= 0
          || rlLearningRate > .01
          || (routingStrategy.equals("reinforcement") && rlLearning && rlTemperature <= 0)
          || !Double.isFinite(minDepthM)
          || minDepthM < .7
          || minDepthM > 3
          || !Double.isFinite(gridM)
          || gridM < 1
          || gridM > 20
          || !Double.isFinite(maxDepthM)
          || maxDepthM < 3
          || maxDepthM > 100000
          || maxCells < 1000
          || maxCells > 400000
          || candidateLimit < 1
          || candidateLimit > 50
          || variantLimit < 1
          || variantLimit > 3) throw new IllegalArgumentException("Некорректные настройки поиска");
    }
  }

  public static class Result {
    public final Map<String, Object> metadata = new LinkedHashMap<>();
    public final List<Evaluation> variants = new ArrayList<>();
    public final Map<String, String> diagnostics = new LinkedHashMap<>();
    public final Map<String, Object> search = new LinkedHashMap<>();
    public long elapsedMs;
    /** Internal design pool; output still contains only selected variants. */
    final List<Evaluation> candidatePool = new ArrayList<>();
  }

  private static class Target {
    Coordinate point;
    String existingId, nodeId, edgeId;
    double distance;
    int ordinal;
    double lowerBound, priority;
    double[] features;

    Target(Coordinate p) {
      point = p.copy();
    }
  }

  private final FeatureStore store;
  private final Options options;
  private final BooleanSupplier cancelled;
  private final BiConsumer<Integer, String> progress;
  private Consumer<Map<String, Object>> candidateObserver;
  private ReinforcementPolicy reinforcementOverride;
  private PolicyTraining training;
  private TreePolicyTraining treeTraining;
  private TreeTrainingData treeData;
  private CorridorGraph treeCorridors;
  private TreeRoots treeRoots;
  private final Map<String, Map<String, Object>> rootOutcomes = new LinkedHashMap<>();

  private boolean requireSingleRoot() {
    return options.routingStrategy.equals("tree") && options.treeSingleRootRequired;
  }

  private List<Evaluation> preservedTrees = List.of();
  private BiConsumer<TreePolicyTraining, List<Evaluation>> treeCheckpoint;
  private int treeRound, treeRounds = 1;

  public Planner withTreeLearning(
      TreePolicyTraining state, BiConsumer<TreePolicyTraining, List<Evaluation>> save) {
    treeTraining = state;
    treeCheckpoint = save;
    return this;
  }

  private List<Network> incumbents = List.of();
  private BiConsumer<PolicyTraining, List<Evaluation>> checkpoint;

  public Planner withLearning(PolicyTraining state) {
    training = state;
    return this;
  }

  public Planner withExperience(
      List<Network> networks, BiConsumer<PolicyTraining, List<Evaluation>> save) {
    incumbents = networks;
    checkpoint = save;
    return this;
  }

  /** Rebuilds terminal demands and their actual chosen walls before exact evaluation. */
  public Evaluation validateIncumbent(Network saved) {
    Network network = saved.copy();
    List<Feature> demands = InputData.demands(store);
    Map<String, Feature> entries = new HashMap<>();
    for (Feature demand : demands) entries.put(demand.text("_tt_entry_id"), demand);
    network.connected.clear();
    Set<String> seen = new HashSet<>();
    for (Network.Node n : network.nodes.values()) {
      if (n.type.equals("oks")) {
        Feature d = entries.get(n.entryId);
        if (d == null
            || !seen.add(n.entryId)
            || !network.children(n.id).isEmpty()
            || network.incident(n.id).size() != 1)
          throw new IllegalArgumentException("Сохранённая сеть: неверный ввод");
        BuildingAccess access = BuildingAccess.resolve(store, store.get(n.entryId));
        n.oksId = InputData.buildingId(d);
        n.buildingId = access.buildingId();
        n.demand = d.flow();
        Network.Edge terminal = network.incident(n.id).get(0);
        n.entryWall =
            access.gate == null
                ? null
                : access.gate.forDiameter(Rules.diameter(n.demand)).wallFor(terminal.geometry);
        network.connected.add(d.id);
      } else {
        n.demand = 0;
        if (!List.of("chamber", "tie").contains(n.type))
          throw new IllegalArgumentException("Тип узла");
        if (n.type.equals("tie")) {
          Feature source = store.get(n.existingId);
          if (source == null
              || !List.of("heat_network", "heat_chamber").contains(source.type)
              || source.geometry.distance(Geo.point(n.point)) > .01)
            throw new IllegalArgumentException("Сохранённая сеть: неверная врезка");
        } else if (n.existingId != null)
          throw new IllegalArgumentException("Сохранённая сеть: чужая врезка");
      }
    }
    if (seen.size() != demands.size())
      throw new IllegalArgumentException("Сохранённая сеть неполная");
    for (Network.Edge e : network.edges.values()) {
      Network.Node a = network.nodes.get(e.start), b = network.nodes.get(e.end);
      if (a == null
          || b == null
          || e.geometry.getLength() <= 0
          || a.point.distance(e.geometry.getCoordinateN(0)) > .001
          || b.point.distance(e.geometry.getCoordinateN(e.geometry.getNumPoints() - 1)) > .001)
        throw new IllegalArgumentException("Сохранённая сеть: геометрия не совпадает с узлами");
    }
    return evaluate(network, demands);
  }

  /** Dependency injection for reproducible trained/untrained policy comparisons. */
  public Planner withReinforcementPolicy(ReinforcementPolicy policy) {
    if (policy == null || !policy.available())
      throw new IllegalArgumentException("Invalid RL policy");
    reinforcementOverride = policy;
    return this;
  }

  private long candidateGroups,
      candidatesSeen,
      candidatesPruned,
      targetsSearched,
      neuralPredictions,
      featureFailures,
      routeSearches,
      preparationNanos;

  /** Offline supervision only; observer sees full labels when guidance is off. */
  public Planner observeCandidates(Consumer<Map<String, Object>> observer) {
    this.candidateObserver = observer;
    return this;
  }

  public Planner(
      FeatureStore store,
      Options options,
      BooleanSupplier cancelled,
      BiConsumer<Integer, String> progress) {
    this.store = store;
    this.options = options;
    this.cancelled = cancelled;
    this.progress = progress;
    options.validate();
  }

  private boolean stopped() {
    return cancelled.getAsBoolean() || Thread.currentThread().isInterrupted();
  }

  public Result calculate() {
    boolean reinforcement = options.routingStrategy.equals("reinforcement");
    boolean treeSearch = options.routingStrategy.equals("tree");
    ReinforcementPolicy rlPolicy =
        (reinforcement || treeSearch)
            ? (reinforcementOverride == null
                ? ReinforcementPolicy.bundled()
                : reinforcementOverride)
            : null;
    if ((reinforcement || treeSearch) && !rlPolicy.available())
      throw new IllegalArgumentException("RL-модель не загружена: " + rlPolicy.unavailableReason);
    if (reinforcement && options.rlLearning) {
      if (training == null) training = new PolicyTraining(rlPolicy);
      training.configure(options.rlBatchSize, options.rlTemperature, options.rlLearningRate);
      rlPolicy = training.policy();
    }
    if (treeSearch && treeTraining != null) rlPolicy = treeTraining.policy();
    if (treeSearch && options.treeLearning && treeTraining == null)
      treeTraining = new TreePolicyTraining(rlPolicy);
    final String initialModelSha = reinforcement ? rlPolicy.sha256 : "";
    final ReinforcementPolicy initialPolicy = rlPolicy;
    final long initialUpdates = training == null ? 0 : training.updates;
    final long initialEpisodes = training == null ? 0 : training.episodes;
    final long initialRollouts = training == null ? 0 : training.rollouts;
    long start = System.nanoTime();
    Result result = new Result();
    result.metadata.put("calculation_mode", options.mode);
    result.metadata.put("depth_rule_profile", options.depthRuleProfile);
    result.metadata.put("depth_minimum_m", options.minDepthM);
    result.metadata.put("depth_maximum_m", options.maxDepthM);
    result.metadata.put("ordinary_depth_m", DepthPlanner.BASE);
    result.metadata.put(
        "coordinate_z",
        options.mode.equals("depth") ? "negative_depth_from_flat_surface" : "absent");
    result.metadata.put("connection_policy", "all_required");
    result.metadata.put(
        "root_policy", requireSingleRoot() ? "single_required" : "multiple_allowed");
    result.metadata.put("building_entry_policy", "nearest_exterior_boundary_to_entry");
    result.metadata.put("ranking_profile", options.rankingProfile);
    result.metadata.put("ranking_cost_basis", options.rankingProfile.equals("contest")
        ? "new_network_only" : "complete_engineering_estimate");
    result.metadata.put(
        "routing_quality",
        Map.of(
            "turn90EquivalentM", RoutingQuality.TURN_90_M,
            "turn45EquivalentM", RoutingQuality.TURN_45_M,
            "turn135EquivalentM", RoutingQuality.TURN_135_M,
            "includedInMonetaryCost", false));
    List<Feature> demands = InputData.demands(store);
    Map<String, BuildingAccess> access = new LinkedHashMap<>();
    for (Feature demand : demands) {
      try {
        access.put(
            demand.id, BuildingAccess.resolve(store, store.get(demand.text("_tt_entry_id"))));
      } catch (IllegalArgumentException e) {
        result.diagnostics.put(demand.id, e.getMessage());
      }
    }
    List<Map<String, Object>> entryAssignments = new ArrayList<>();
    for (BuildingAccess a : access.values())
      if (a.gate != null) {
        Feature demand = demands.stream()
            .filter(d -> a.entry.id.equals(d.text("_tt_entry_id")))
            .findFirst().orElseThrow();
        a.gate.forDiameter(Rules.diameter(demand.flow()));
        List<double[][]> candidates = new ArrayList<>();
        for (LineString wall : a.gate.walls) {
          Coordinate first = Geo.ll(wall.getCoordinateN(0));
          Coordinate last = Geo.ll(wall.getCoordinateN(1));
          candidates.add(new double[][] {{first.x, first.y}, {last.x, last.y}});
        }
        Map<String, Object> assignment = new LinkedHashMap<>();
        assignment.put("sourceEntryId", a.entry.id);
        assignment.put("buildingId", a.buildingId());
        assignment.put("eligibleWallCount", candidates.size());
        assignment.put("candidateWalls", candidates);
        assignment.put("selection", "nearest_exterior_boundary_to_entry");
        entryAssignments.add(assignment);
      }
    result.search.put("buildingEntryPolicy", "nearest_exterior_boundary_to_entry");
    result.search.put("entryAssignments", entryAssignments);
    Set<String> difficult = new LinkedHashSet<>();
    Evaluation bestPartial = evaluate(new Network(), demands);
    List<Evaluation> complete = new ArrayList<>();
    int restoredNetworks = 0;
    List<String> rejectedExperience = new ArrayList<>();
    for (Network saved : incumbents) {
      if (stopped()) break;
      try {
        Evaluation v = validateIncumbent(saved);
        if (requireSingleRoot() && v.network.roots().size() != 1) {
          rejectedExperience.add(
              "ROOT_POLICY_MISMATCH: сохранённая сеть имеет "
                  + v.network.roots().size()
                  + " врезки");
          continue;
        }
        v.summary.put("routing_strategy", "experience");
        v.summary.put("source", "saved_best_revalidated");
        if (complete.stream().noneMatch(existing -> same(existing.network, v.network))) {
          complete.add(v);
          restoredNetworks++;
        }
      } catch (IllegalArgumentException e) {
        rejectedExperience.add(e.getMessage());
      }
    }
    Double bestBeforeRun = complete.stream().map(v -> v.score).min(Double::compare).orElse(null);
    List<Map<String, Object>> attempts = new ArrayList<>();
    int maximumAttempts =
        treeSearch ? 0 : reinforcement ? options.rlEpisodes : Math.max(6, options.variantLimit * 4);
    Random rlRandom = new Random(options.rlSeed);
    long rlDecisions = 0, rlRejected = 0;
    if (treeSearch)
      bestPartial =
          options.treeLearning
              ? runLearningTrees(demands, access, complete, attempts, result)
              : runTreeSearch(demands, access, complete, attempts, result, rlPolicy);
    if (treeSearch && !stopped()) improveSharedTrees(demands, access, complete, attempts, result);
    for (int attempt = 0; attempt < maximumAttempts && !stopped(); attempt++) {
      List<Feature> order = ordered(demands, attempt, difficult);
      Network network = new Network();
      List<Map<String, Object>> rlTrace = new ArrayList<>();
      PolicyTraining.Sample sample =
          reinforcement && options.rlLearning && attempt > 0 ? training.newSample() : null;
      if (reinforcement && options.rlLearning) {
        rlPolicy = training.policy();
        rlRandom = new Random(options.rlSeed + 0x9e3779b97f4a7c15L * training.rollouts);
      }
      String behaviorSha = reinforcement ? rlPolicy.sha256 : "";
      if (reinforcement) {
        RlEpisode episode = new RlEpisode(demands, access);
        while (!stopped() && !episode.actions().isEmpty()) {
          List<RlAction> actions = episode.actions();
          double[][] features = actions.stream().map(a -> a.features).toArray(double[][]::new);
          int chosen =
              rlPolicy.choose(features, attempt == 0 ? 0 : options.rlTemperature, rlRandom);
          if (sample != null) training.observe(sample, features, chosen);
          RlAction action = actions.get(chosen);
          progress.accept(
              (attempt * 85 + episode.connected() * 85 / Math.max(1, demands.size()))
                  / maximumAttempts,
              "RL: эпизод "
                  + (attempt + 1)
                  + "/"
                  + maximumAttempts
                  + ", подключено "
                  + episode.connected()
                  + "/"
                  + demands.size()
                  + ", ввод "
                  + action.entryId);
          boolean accepted = episode.step(chosen);
          rlDecisions++;
          if (!accepted) rlRejected++;
          rlTrace.add(
              Map.of(
                  "action",
                  action.id,
                  "entryId",
                  action.entryId,
                  "targetKind",
                  action.targetKind,
                  "accepted",
                  accepted));
        }
        network = episode.network;
        order = episode.order;
      } else {
        for (Feature demand : order) {
          if (stopped()) break;
          progress.accept(
              Math.min(
                  85,
                  (attempt * demands.size() + network.connected.size())
                      * 85
                      / Math.max(1, maximumAttempts * demands.size())),
              "Поиск " + (attempt + 1) + "/" + maximumAttempts + ": ОКС " + demand.id);
          if (stopped()) break;
          network =
              addDemand(network, demand, access.get(demand.id), demands, attempt % 4 != 3, attempt);
        }
        // A branch built later can make a previously unreachable consumer reachable.
        boolean added;
        do {
          added = false;
          for (Feature demand : order)
            if (!network.connected.contains(demand.id) && !stopped()) {
              int before = network.connected.size();
              network = addDemand(network, demand, access.get(demand.id), demands, true, attempt);
              added |= network.connected.size() > before;
            }
        } while (added && !stopped());
        // One-step rip-up and reconnect repairs an early branch blocking a missing
        // consumer.
        for (Feature missing : order)
          if (!network.connected.contains(missing.id) && !stopped()) {
            List<Feature> neighbours = new ArrayList<>(demands);
            Network currentNetwork = network;
            neighbours.removeIf(f -> !networkContainsEntry(currentNetwork, f));
            Coordinate point = InputData.portal(store.get(missing.text("_tt_entry_id")));
            neighbours.sort(
                Comparator.comparingDouble(
                    f -> InputData.portal(store.get(f.text("_tt_entry_id"))).distance(point)));
            for (Feature moved : neighbours.subList(0, Math.min(3, neighbours.size()))) {
              if (stopped()) break;
              Network trial = without(network, moved);
              trial = addDemand(trial, missing, access.get(missing.id), demands, true, attempt);
              if (!trial.connected.contains(missing.id)) continue;
              trial = addDemand(trial, moved, access.get(moved.id), demands, true, attempt);
              if (trial.connected.size() > network.connected.size()) {
                network = trial;
                break;
              }
            }
          }
      }
      if (stopped()) break;
      Evaluation evaluated = evaluate(network, demands);
      boolean full = network.connected.size() == demands.size();
      Map<String, Object> attemptSummary =
          new LinkedHashMap<>(
              Map.of(
                  "attempt",
                  attempt + 1,
                  "order",
                  order.stream().map(d -> d.id).collect(java.util.stream.Collectors.toList()),
                  "connected",
                  network.connected.size(),
                  "required",
                  demands.size(),
                  "complete",
                  full));
      attemptSummary.put("strategy", options.routingStrategy);
      if (reinforcement) {
        attemptSummary.put("decisions", rlTrace);
        attemptSummary.put("reward", rlReward(evaluated, demands.size()));
        attemptSummary.put("scoreBeforeSmoothing", evaluated.score);
        attemptSummary.put("behaviorModelSha256", behaviorSha);
        attemptSummary.put("usedForTraining", sample != null);
        if (options.rlLearning) {
          training.rollouts++;
          if (sample != null) training.complete(sample, rlReward(evaluated, demands.size()));
          attemptSummary.put("trainingUpdates", training.updates);
        }
      }
      attempts.add(attemptSummary);
      if (network.connected.size() > bestPartial.network.connected.size()
          || network.connected.size() == bestPartial.network.connected.size()
              && evaluated.score <= bestPartial.score) bestPartial = evaluated;
      if (full) {
        evaluated = smoothNetwork(evaluated, demands);
        evaluated.summary.put("routing_strategy", options.routingStrategy);
        evaluated.summary.put("source_attempt", attempt + 1);
        if (reinforcement) evaluated.summary.put("rl_model_id", rlPolicy.id);
        Evaluation candidate = evaluated;
        if (complete.stream().noneMatch(v -> same(v.network, candidate.network)))
          complete.add(candidate);
      } else {
        difficult.clear();
        for (Feature demand : order)
          if (!network.connected.contains(demand.id)) difficult.add(demand.id);
      }
      attemptSummary.put("scoreAfterSmoothing", full ? evaluated.score : null);
      // Persist completed episodes only; an interrupted rollout never updates weights.
      if (checkpoint != null) {
        List<Evaluation> saved = new ArrayList<>(complete);
        saved.sort(Comparator.comparingDouble(v -> v.score));
        checkpoint.accept(training, saved.subList(0, Math.min(3, saved.size())));
      }
      double currentBest = complete.stream().mapToDouble(v -> v.score).min().orElse(Double.NaN);
      progress.accept(
          (attempt + 1) * 85 / maximumAttempts,
          "Эпизод "
              + (attempt + 1)
              + "/"
              + maximumAttempts
              + " · подключено "
              + network.connected.size()
              + "/"
              + demands.size()
              + (reinforcement && options.rlLearning
                  ? " · обновлений весов за запуск: " + (training.updates - initialUpdates)
                  : "")
              + (Double.isFinite(currentBest)
                  ? " · лучший score: " + String.format(Locale.ROOT, "%.6f", currentBest)
                  : ""));
      // Each attempt exhausts its configured candidate set. There is no wall-clock cutoff.
      if (!reinforcement
          && complete.size() >= options.variantLimit
          && attempt + 1 >= options.variantLimit * 2) break;
    }
    if (requireSingleRoot()) complete.removeIf(v -> v.network.roots().size() != 1);
    complete.sort(
        Comparator.comparingDouble((Evaluation v) -> v.score)
            .thenComparingInt(v -> (int) v.checks.get("bendCount")));
    result.candidatePool.addAll(complete.subList(0, Math.min(60, complete.size())));
    Map<String, Object> variantSearch = new LinkedHashMap<>();
    if (options.variantObjective.equals("balanced") && options.variantLimit == 3 && !stopped()) {
      if (treeSearch) {
        List<Map<String, Object>> searches = new ArrayList<>();
        int stage = 0;
        for (String objective : List.of("earthworks", "installation", "earthworks")) {
          if (stopped()) break;
          boolean refineEarth = stage++ == 2;
          Map<String, Object> preview = new LinkedHashMap<>();
          List<Evaluation> provisional = VariantSelector.choose(complete, 3, preview);
          if (refineEarth) {
            if (provisional.size() < 3
                || !Boolean.FALSE.equals(preview.get("earthworkTradeoffFound"))) continue;
          } else if (provisional.stream()
              .anyMatch(v -> objective.equals(v.summary.get("variant_role")))) continue;
          Options alternative = options.copy();
          alternative.variantObjective = objective;
          alternative.variantLimit = 1;
          alternative.treeLearning = false;
          alternative.rlLearning = false;
          alternative.rlRemember = false;
          // The extra role searches are exploratory, not the primary winner
          // search. Keep their effort proportional to the user's existing beam
          // settings, unless they explicitly enabled the deep group search.
          boolean deepAlternative = options.treeGroupRepair;
          alternative.treeBeamWidth = deepAlternative ? options.treeBeamWidth
              : Math.min(2, options.treeBeamWidth);
          alternative.treeExpansion = deepAlternative ? options.treeExpansion
              : Math.min(2, options.treeExpansion);
          alternative.candidateLimit = deepAlternative ? options.candidateLimit
              : Math.min(4, options.candidateLimit);
          alternative.treeRepairPasses = deepAlternative
              ? Math.min(1, options.treeRepairPasses) : 0;
          alternative.treeGeometricSearch = deepAlternative && options.treeGeometricSearch;
          alternative.treeJunctionRepair = deepAlternative && options.treeJunctionRepair;
          ReinforcementPolicy guidance = treeTraining != null ? treeTraining.policy() : rlPolicy;
          for (int pass = 0; pass < 2 && !stopped(); pass++) {
            boolean fromScratch = pass == 1 || complete.isEmpty();
            alternative.treeResumeFromBest = !fromScratch;
            progress.accept(94 + pass, "Поиск варианта: "
                + (refineEarth ? "уточнение земляных работ"
                    : objective.equals("earthworks") ? "земляные работы" : "простота прокладки")
                + (fromScratch ? " · новые начала" : " · перестройка полной сети"));
            final long[] lastGoalProgressNs = {0};
            Planner explorer = new Planner(store, alternative, this::stopped, (p, m) -> {
              long now = System.nanoTime();
              if (now - lastGoalProgressNs[0] >= 20_000_000_000L) {
                progress.accept(95, "Вариант «" +
                    (objective.equals("earthworks") ? "земляные работы" : "прокладка")
                    + "»: " + m);
                lastGoalProgressNs[0] = now;
              }
            });
            explorer.treeCorridors = treeCorridors;
            explorer.treeRoots = treeRoots;
            explorer.withReinforcementPolicy(guidance);
            if (!fromScratch)
              explorer.withExperience(complete.stream()
                  .sorted(Comparator.comparingDouble((Evaluation v) -> ((Number) v.summary.get(
                      objective.equals("earthworks") ? "earthwork_index" : "installation_index")).doubleValue())
                      .thenComparingDouble(v -> v.score))
                  .limit(3).map(v -> v.network).collect(java.util.stream.Collectors.toList()),
                  (state, saved) -> {});
            Result alternatives = explorer.calculate();
            int added = mergeGoalCandidates(alternatives, complete);
            searches.add(Map.of("objective", objective, "refineTradeoff", refineEarth,
                "fromScratch", fromScratch,
                "deepAlternative", deepAlternative,
                "fullCandidates", alternatives.candidatePool.size(),
                "newVerifiedCandidates", added, "elapsedMs", alternatives.elapsedMs));
            Map<String, Object> after = new LinkedHashMap<>();
            List<Evaluation> now = VariantSelector.choose(complete, 3, after);
            boolean targetFound = refineEarth
                ? Boolean.TRUE.equals(after.get("earthworkTradeoffFound"))
                : now.stream().anyMatch(v -> objective.equals(v.summary.get("variant_role")));
            if (targetFound || fromScratch) break;
          }
        }
        variantSearch.put("separateGoalSearches", searches);
      }
      result.variants.addAll(VariantSelector.choose(complete, 3, variantSearch));
    } else {
      result.variants.addAll(complete.subList(0, Math.min(options.variantLimit, complete.size())));
    }
    result.search.put("variantSelection", variantSearch);
    boolean partialOnly = complete.isEmpty() && !stopped();
    if (partialOnly) {
      bestPartial.summary.put("variant_role", "partial");
      bestPartial.summary.put("variant_name", "Подключено "
          + bestPartial.network.connected.size() + " из " + demands.size());
      result.variants.add(bestPartial);
    }
    if (checkpoint != null && !stopped() && options.variantObjective.equals("balanced")
        && options.variantLimit == 3 && !result.variants.isEmpty())
      checkpoint.accept(training, result.variants);
    List<String> unresolved = new ArrayList<>();
    if (partialOnly) {
      for (Feature d : demands)
        if (!bestPartial.network.connected.contains(d.id)) {
          unresolved.add(d.text("_tt_entry_id"));
          result.diagnostics.putIfAbsent(
              d.id,
              bestPartial.network.reasons.getOrDefault(
                  d.id,
                  stopped()
                      ? "Расчёт отменён"
                      : "Полное подключение не найдено в исследованных"
                          + " топологиях и коридорах"));
        }
    }
    result.search.put(
        "status",
        stopped()
            ? "CANCELLED"
            : partialOnly
                ? "PARTIAL_SOLUTION"
                : result.variants.size() < options.variantLimit
                    ? "FEWER_VARIANTS_FOUND"
                    : "COMPLETE");
    result.search.put("requiredConnections", demands.size());
    result.search.put("requestedVariants", options.variantLimit);
    result.search.put("foundVariants", result.variants.size());
    result.search.put(
        "bestConnectedCount",
        complete.isEmpty() ? bestPartial.network.connected.size() : demands.size());
    result.search.put("unresolvedEntryIds", unresolved);
    result.search.put("attempts", attempts);
    result.search.put("globalOptimalityProven", false);
    result.metadata.put("routing_strategy", options.routingStrategy);
    result.search.put("restoredNetworks", restoredNetworks);
    result.search.put("rejectedExperience", rejectedExperience);
    result.search.put(
        "qualityProgress",
        LearningDiagnostics.quality(
            bestBeforeRun,
            attempts,
            complete.isEmpty() ? null : result.variants.get(0).score));
    if (reinforcement) {
      if (options.rlLearning) rlPolicy = training.policy();
      Map<String, Object> info = new LinkedHashMap<>();
      info.put("modelId", rlPolicy.id);
      info.put("modelSha256", rlPolicy.sha256);
      info.put("initialModelSha256", initialModelSha);
      info.put("requestedEpisodes", options.rlEpisodes);
      info.put("completedEpisodes", attempts.size());
      info.put("seed", options.rlSeed);
      info.put("temperature", options.rlTemperature);
      info.put("decisions", rlDecisions);
      info.put("rejectedActions", rlRejected);
      info.put("trainingDuringCalculation", options.rlLearning);
      info.put("greedyFirstEpisode", true);
      info.putAll(LearningDiagnostics.weights(initialPolicy, rlPolicy));
      info.put("initialUpdates", initialUpdates);
      info.put("initialTrainingEpisodes", initialEpisodes);
      if (options.rlLearning) {
        info.put("updatesThisRun", training.updates - initialUpdates);
        info.put("totalUpdates", training.updates);
        info.put("totalTrainingEpisodes", training.episodes);
        info.put("trainingEpisodesThisRun", training.episodes - initialEpisodes);
        info.put("resumedRollouts", initialRollouts);
        info.put("totalRollouts", training.rollouts);
        info.put("pendingBatchEpisodes", training.pendingCount());
        info.put("discardedPendingEpisodes", training.discardedPending);
        info.put("batchSize", options.rlBatchSize);
        info.put("learningRate", options.rlLearningRate);
        info.put("updateHistory", new ArrayList<>(training.history));
      }
      result.search.put("reinforcement", info);
    }
    Map<String, Object> guidance = new LinkedHashMap<>();
    NeuralRanker model = NeuralRanker.bundled();
    guidance.put("requestedPolicy", options.neuralGuidance);
    guidance.put(
        "effectivePolicy",
        (reinforcement || treeSearch)
            ? "off"
            : options.neuralGuidance.equals("on") && !model.available()
                ? "bounds"
                : options.neuralGuidance);
    guidance.put("modelId", model.id);
    guidance.put("modelSha256", model.sha256);
    guidance.put(
        "fallbackReason", options.neuralGuidance.equals("on") ? model.unavailableReason : "");
    guidance.put("candidates", candidatesSeen);
    guidance.put("targetsSearched", targetsSearched);
    guidance.put("prunedByAnalyticalBound", candidatesPruned);
    guidance.put("neuralPredictions", neuralPredictions);
    guidance.put("featureFailures", featureFailures);
    guidance.put("routeSearches", routeSearches);
    guidance.put("preparationMs", preparationNanos / 1_000_000d);
    guidance.put("learnedValueUsedForPruning", false);
    result.search.put("neuralGuidance", guidance);
    result.search.put(
        "message",
        partialOnly
            ? "Полное подключение всех точек не найдено. Частичный вариант учитывает"
                + " штраф за каждую неподключённую точку; это не доказывает"
                + " невозможность подключения."
            : result.variants.isEmpty() ? "Расчёт отменён."
            : "Все возвращённые варианты подключают каждую заданную точку. Найдено"
                + " различных решений: "
                + result.variants.size()
                + ".");
    result.metadata.put("search", result.search);
    double bestScore = result.variants.isEmpty() ? 0 : result.variants.get(0).score;
    result.metadata.put(
        "ranking",
        Map.of(
            "score_direction",
            "lower_is_better",
            "rating_direction",
            "higher_is_better",
            "rating_scale",
            100,
            "rating_basis",
            partialOnly ? "best_partial_variant" : "best_complete_variant",
            "reference_score",
            bestScore));
    List<Evaluation> rankOrder = new ArrayList<>(result.variants);
    rankOrder.sort(Comparator.comparingDouble(v -> v.score));
    for (int i = 0; i < result.variants.size(); i++) {
      Evaluation v = result.variants.get(i);
      v.summary.put("rank", rankOrder.indexOf(v) + 1);
      v.summary.put("rating", Rules.relativeRating(v.score, bestScore));
      v.describeRoutes(store);
    }
    result.elapsedMs = (System.nanoTime() - start) / 1000000;
    progress.accept(98, "Проверка полного подключения и запись результата");
    return result;
  }

  private int mergeGoalCandidates(Result alternatives, List<Evaluation> complete) {
    int added = 0;
    for (Evaluation candidate : alternatives.candidatePool) {
      if (stopped()) break;
      try {
        Evaluation checked = validateIncumbent(candidate.network);
        if (complete.stream().noneMatch(previous -> same(previous.network, checked.network))) {
          complete.add(checked);
          added++;
        }
      } catch (IllegalArgumentException ignored) {
        // Only networks satisfying the original engineering checks enter the final pool.
      }
    }
    return added;
  }

  private static final class TreeState {
    final Evaluation value;
    final double priority;
    final String signature;
    final TreeTrainingData.Trace trace;

    TreeState(Evaluation value, double priority) {
      this(value, priority, null);
    }

    TreeState(Evaluation value, double priority, TreeTrainingData.Trace trace) {
      this.trace = trace;
      this.value = value;
      this.priority = priority;
      signature = treeSignature(value.network);
    }
  }

  private static String treeSignature(Network network) {
    List<String> parts = new ArrayList<>();
    for (Network.Edge edge : network.edges.values()) parts.add(edge.geometry.norm().toText());
    for (Network.Node node : network.nodes.values())
      if (node.type.equals("tie") || node.type.equals("oks"))
        parts.add(
            node.type + ":" + node.existingId + ":" + node.entryId + ":" + Geo.key(node.point));
    Collections.sort(parts);
    return String.join("|", parts);
  }

  /** Exact IDs, iteration order and counter matter when replaying an engineering transition. */
  private static String transitionSignature(Network n) {
    StringBuilder s = new StringBuilder().append(n.sequence()).append('|').append(n.connected);
    for (Network.Node v : n.nodes.values())
      s.append('|')
          .append(v.id)
          .append(':')
          .append(v.type)
          .append(':')
          .append(v.existingId)
          .append(':')
          .append(v.entryId)
          .append(':')
          .append(v.buildingId)
          .append(':')
          .append(Double.toHexString(v.demand))
          .append(':')
          .append(v.point.x)
          .append(':')
          .append(v.point.y)
          .append(':')
          .append(v.entryWall);
    for (Network.Edge e : n.edges.values())
      s.append('|')
          .append(e.id)
          .append(':')
          .append(e.start)
          .append(':')
          .append(e.end)
          .append(':')
          .append(e.geometry);
    return TreePolicyTraining.digest(s.toString());
  }

  /**
   * Neural ordering + a territory graph + a beam of exact engineering states. Weights are frozen
   * throughout each pass; optional preference training occurs between complete passes.
   */
  private void treeProgress(int percent, String text) {
    if (!options.treeLearning) progress.accept(percent, text);
    else
      progress.accept(
          (treeRound * 90 + Math.min(90, percent)) / treeRounds,
          "Дерево: проход " + (treeRound + 1) + "/" + treeRounds + " · " + text);
  }

  private Evaluation runLearningTrees(
      List<Feature> demands,
      Map<String, BuildingAccess> access,
      List<Evaluation> complete,
      List<Map<String, Object>> attempts,
      Result result) {
    ReinforcementPolicy initial = treeTraining.policy();
    long initialUpdates = treeTraining.updates, initialModels = treeTraining.acceptedModels;
    int initialPairs = treeTraining.examples(), observed = 0, added = 0, gradientSteps = 0;
    int rejectedModels = 0, noGainPasses = 0;
    long trainingNanos = 0;
    treeRounds = options.treeLearningRounds;
    preservedTrees = complete;
    List<Map<String, Object>> passes = new ArrayList<>(), updates = new ArrayList<>();
    Map<String, Object> aggregate = new LinkedHashMap<>();
    List<Map<String, Object>> allRejections = new ArrayList<>();
    Map<String, Integer> byEntry = new LinkedHashMap<>();
    int tested = 0, rejected = 0, repairs = 0, duplicates = 0;
    Double championScore = null, baselineScore = null;
    TreePolicyTraining.Proposal proposal = null;
    Map<String, Object> proposalInfo = null;
    Evaluation partial = evaluate(new Network(), demands);
    String stopReason = "requested_passes_completed";
    for (treeRound = 0; treeRound < treeRounds && !stopped(); treeRound++) {
      ReinforcementPolicy behavior =
          proposal == null ? treeTraining.policy() : proposal.candidate.policy();
      treeData = new TreeTrainingData();
      List<Evaluation> fresh = new ArrayList<>();
      Result passResult = new Result();
      int firstAttempt = attempts.size();
      long started = System.nanoTime();
      Evaluation value = runTreeSearch(demands, access, fresh, attempts, passResult, behavior);
      long elapsed = (System.nanoTime() - started) / 1000000;
      if (value.network.connected.size() > partial.network.connected.size()
          || value.network.connected.size() == partial.network.connected.size()
              && value.score < partial.score) partial = value;
      Double freshScore = fresh.stream().map(v -> v.score).min(Double::compare).orElse(null);
      for (Evaluation v : fresh) retainBetter(complete, v);
      for (int i = firstAttempt; i < attempts.size(); i++) {
        attempts.get(i).put("searchPass", treeRound + 1);
        attempts.get(i).put("behaviorModelSha256", behavior.sha256);
      }
      @SuppressWarnings("unchecked")
      Map<String, Object> diagnostics = (Map<String, Object>) passResult.search.get("treeSearch");
      if (aggregate.isEmpty()) aggregate.putAll(diagnostics);
      tested += ((Number) diagnostics.get("testedExtensions")).intValue();
      rejected += ((Number) diagnostics.get("rejectedExtensions")).intValue();
      repairs += ((Number) diagnostics.get("acceptedRepairs")).intValue();
      duplicates += ((Number) diagnostics.get("duplicateStates")).intValue();
      @SuppressWarnings("unchecked")
      Map<String, Integer> rejectedEntries =
          (Map<String, Integer>) diagnostics.get("rejectedByEntry");
      rejectedEntries.forEach((k, v) -> byEntry.merge(k, v, Integer::sum));
      @SuppressWarnings("unchecked")
      List<Map<String, Object>> reasons = (List<Map<String, Object>>) diagnostics.get("rejections");
      for (Map<String, Object> reason : reasons)
        if (allRejections.size() < 500) {
          Map<String, Object> item = new LinkedHashMap<>(reason);
          item.put("searchPass", treeRound + 1);
          allRejections.add(item);
        }
      boolean accepted = false;
      if (proposal == null) {
        championScore = freshScore;
        baselineScore = freshScore;
      } else {
        boolean improved =
            freshScore != null && (championScore == null || freshScore < championScore - 1e-8);
        noGainPasses = improved ? 0 : noGainPasses + 1;
        accepted = !stopped() && TreePolicyTraining.passesNetworkCheck(championScore, freshScore);
        proposalInfo.put("validatedBySearch", !stopped());
        proposalInfo.put("validationScore", freshScore);
        proposalInfo.put("referenceScore", championScore);
        proposalInfo.put("accepted", accepted);
        proposalInfo.put(
            "reason",
            stopped()
                ? "cancelled"
                : accepted ? "complete_and_no_worse_on_this_map" : "incomplete_or_worse");
        if (accepted) {
          treeTraining.accept(proposal);
          championScore = freshScore;
          aggregate.putAll(diagnostics);
        } else rejectedModels++;
        if (!stopped()) treeTraining.recordValidation(accepted);
        proposalInfo.put("nextRateScale", treeTraining.rateScale);
      }
      List<TreePolicyTraining.Preference> labels = treeData.preferences();
      int newlyAdded = 0;
      if (!stopped()) {
        observed += labels.size();
        newlyAdded = treeTraining.remember(labels);
        added += newlyAdded;
      }
      Map<String, Object> pass = new LinkedHashMap<>();
      pass.put("pass", treeRound + 1);
      pass.put("role", proposal == null ? "baseline" : "trained_candidate");
      pass.put("modelSha256", behavior.sha256);
      pass.put("freshBestScore", freshScore);
      pass.put("complete", freshScore != null);
      pass.put("modelAccepted", accepted);
      pass.put("elapsedMs", elapsed);
      pass.put("preferencePairs", labels.size());
      pass.put("newReplayPairs", newlyAdded);
      pass.put("fullPathsForLabels", treeData.fullPaths);
      pass.put("testedExtensions", diagnostics.get("testedExtensions"));
      pass.put("acceptedRepairs", diagnostics.get("acceptedRepairs"));
      passes.add(pass);
      treeData = null;
      saveTrees(complete);
      if (treeCheckpoint != null) treeCheckpoint.accept(treeTraining, bestTrees(complete));
      if (stopped()) {
        stopReason = "cancelled";
        break;
      }
      if (treeRound + 1 >= treeRounds) break;
      if (noGainPasses >= 2) {
        stopReason = "two_validation_passes_without_quality_gain";
        break;
      }
      treeProgress(90, "Дообучение на сравнениях полных сетей: " + treeTraining.examples());
      long trainingStart = System.nanoTime();
      proposal =
          treeTraining.propose(
              options.treeTrainingSteps,
              options.treeTrainingRate * treeTraining.rateScale,
              .25 * treeTraining.rateScale,
              this::stopped);
      trainingNanos += System.nanoTime() - trainingStart;
      if (proposal == null) {
        stopReason = stopped() ? "cancelled" : "no_informative_full_network_pairs";
        break;
      }
      gradientSteps += proposal.steps;
      proposalInfo = new LinkedHashMap<>();
      proposalInfo.put("afterPass", treeRound + 1);
      proposalInfo.put("gradientSteps", proposal.steps);
      proposalInfo.put("learningRate", options.treeTrainingRate * treeTraining.rateScale);
      proposalInfo.put("maximumWeightMovement", .25 * treeTraining.rateScale);
      proposalInfo.put("lossBefore", proposal.lossBefore);
      proposalInfo.put("lossAfter", proposal.lossAfter);
      proposalInfo.put("candidateModelSha256", proposal.candidate.policy().sha256);
      proposalInfo.put("validatedBySearch", false);
      proposalInfo.put("accepted", false);
      proposalInfo.put("reason", "awaiting_validation");
      updates.add(proposalInfo);
      if (!Double.isFinite(proposal.lossAfter)
          || proposal.lossAfter >= proposal.lossBefore - 1e-10) {
        proposalInfo.put("reason", "preference_loss_did_not_improve");
        rejectedModels++;
        stopReason = "preference_loss_did_not_improve";
        break;
      }
    }
    treeData = null;
    if (stopped()) {
      stopReason = "cancelled";
      if (proposalInfo != null && "awaiting_validation".equals(proposalInfo.get("reason")))
        proposalInfo.put("reason", "cancelled");
    }
    if (treeCheckpoint != null) treeCheckpoint.accept(treeTraining, bestTrees(complete));
    aggregate.put("passes", passes);
    aggregate.put("testedExtensions", tested);
    aggregate.put("rejectedExtensions", rejected);
    aggregate.put("acceptedRepairs", repairs);
    aggregate.put("duplicateStates", duplicates);
    aggregate.put("rejectedByEntry", byEntry);
    aggregate.put("rejections", allRejections);
    aggregate.put("rejectionsTruncated", rejected > allRejections.size());
    aggregate.put("trainingDuringCalculation", gradientSteps > 0);
    aggregate.put("policyModelId", treeTraining.policy().id);
    aggregate.put("policyModelSha256", treeTraining.policy().sha256);
    aggregate.put("searchVersion", "joint-junctions-1.8.0");
    aggregate.put("cancelled", stopped());
    result.search.put("treeSearch", aggregate);
    Map<String, Object> learning = new LinkedHashMap<>();
    learning.put("enabled", true);
    learning.put("method", "same_state_full_network_pairwise_preferences");
    learning.put("scope", "this_territory_and_engineering_settings");
    learning.put("requestedSearchPasses", treeRounds);
    learning.put("completedSearchPasses", passes.size());
    learning.put("baselineFreshScore", baselineScore);
    learning.put("acceptedPolicyFreshScore", championScore);
    learning.put("initialModelSha256", initial.sha256);
    learning.put("modelSha256", treeTraining.policy().sha256);
    learning.put("modelId", treeTraining.policy().id);
    learning.put("initialReplayPairs", initialPairs);
    learning.put("observedPreferencePairs", observed);
    learning.put("newReplayPairs", added);
    learning.put("replayPairs", treeTraining.examples());
    learning.put("replayCapacity", TreePolicyTraining.MAX_PAIRS);
    learning.put("gradientStepsThisRun", gradientSteps);
    learning.put("acceptedGradientStepsThisRun", treeTraining.updates - initialUpdates);
    learning.put("totalAcceptedGradientSteps", treeTraining.updates);
    learning.put("acceptedModelsThisRun", treeTraining.acceptedModels - initialModels);
    learning.put("rejectedModelsThisRun", rejectedModels);
    learning.put("trainingMs", trainingNanos / 1000000);
    learning.put("stopReason", stopReason);
    learning.put("nextRateScale", treeTraining.rateScale);
    learning.put("updateHistory", updates);
    learning.putAll(LearningDiagnostics.weights(initial, treeTraining.policy()));
    result.search.put("treeLearning", learning);
    return partial;
  }

  private Evaluation runTreeSearch(
      List<Feature> demands,
      Map<String, BuildingAccess> access,
      List<Evaluation> complete,
      List<Map<String, Object>> attempts,
      Result result,
      ReinforcementPolicy policy) {
    List<Coordinate> terminals = new ArrayList<>();
    for (Feature d : demands) terminals.add(InputData.portal(store.get(d.text("_tt_entry_id"))));
    treeProgress(1, "Карта коридоров: обзор всей территории и удаление лишних связей");
    if (treeCorridors == null)
      treeCorridors = new CorridorGraph(store, terminals, options, this::stopped);
    CorridorGraph graph = treeCorridors;
    Map<String, Object> info = new LinkedHashMap<>();
    info.put("graph", graph.diagnostics());
    info.put("beamWidth", options.treeBeamWidth);
    info.put("expansionPerTree", options.treeExpansion);
    info.put("policyModelId", policy.id);
    info.put("policyModelSha256", policy.sha256);
    info.put("trainingDuringCalculation", false);
    info.put("diffusionModelUsed", false);
    info.put("selection", "exact_partial_score_plus_advisory_remaining_estimate");
    info.put("searchVersion", "geometric-steiner-1.9.0");
    result.search.put("treeSearch", info);
    info.put("singleRootRequired", requireSingleRoot());
    rootOutcomes.clear();
    for (Evaluation saved : complete) trackRoot(saved, demands);
    if (requireSingleRoot()) {
      treeProgress(3, "Проверка направлений врезок для всех вводов");
      if (treeRoots == null)
        treeRoots = new TreeRoots(store, demands, graph, options.treeBeamWidth, this::stopped);
      info.put("rootSelection", treeRoots.diagnostics);
    }
    boolean continuedSaved =
        options.treeResumeFromBest && !options.treeLearning && !complete.isEmpty();
    if (requireSingleRoot() && !options.treeLearning && !continuedSaved)
      runCorridorTrees(demands, complete, attempts, info);
    boolean resume = options.treeResumeFromBest && !options.treeLearning && !complete.isEmpty();
    info.put("resumedFromBestNetwork", continuedSaved);
    info.put("beamSkippedAfterCorridorTree", resume && !continuedSaved);
    Map<String, String> failures = new HashMap<>();
    Map<String, Integer> rejectedByEntry = new LinkedHashMap<>();
    List<Map<String, Object>> rejections = new ArrayList<>(), levels = new ArrayList<>();
    Evaluation partial = evaluate(new Network(), demands);
    List<TreeState> beam = new ArrayList<>(List.of(new TreeState(partial, 0)));
    int trials = 0, rejected = 0, masked = 0, duplicates = 0;
    for (int depth = 0; depth < demands.size() && !stopped() && !resume; depth++) {
      List<TreeState> next = new ArrayList<>();
      Set<String> signatures = new HashSet<>();
      for (TreeState state : beam) {
        if (stopped()) break;
        RlEpisode environment = new RlEpisode(demands, access);
        environment.network = state.value.network.copy();
        List<RlAction> actions = new ArrayList<>(environment.actions());
        Map<String, Double> priorities = new HashMap<>();
        for (RlAction action : actions) {
          Coordinate from = InputData.portal(access.get(action.demand.id).entry);
          double corridor = graph.distance(from, action.target.point);
          // Only ordering: neither this learned score nor the coarse graph rejects
          // geometry.
          priorities.put(
              action.id,
              -policy.logit(action.features)
                  + .005 * corridor
                  - (graph.onSkeleton(action.target.point) ? .15 : 0));
        }
        actions.sort(
            Comparator.comparingDouble((RlAction a) -> priorities.get(a.id))
                .thenComparing(a -> a.id));
        Map<String, Integer> acceptedPerEntry = new HashMap<>();
        Set<String> initialRoots = new HashSet<>();
        int successes = 0, checked = 0;
        for (RlAction action : actions) {
          if (stopped()
              || successes
                  >= (depth == 0 && requireSingleRoot()
                      ? options.treeBeamWidth
                      : options.treeExpansion)) break;
          if (depth == 0 && requireSingleRoot() && initialRoots.contains(targetKey(action.target)))
            continue;
          if (!(depth == 0 && requireSingleRoot())
              && acceptedPerEntry.getOrDefault(action.entryId, 0) >= (depth == 0 ? 1 : 2)) continue;
          String key = state.signature + "|" + action.id;
          if (failures.containsKey(key)) {
            masked++;
            continue;
          }
          // A finite expansion budget is explicit and never reported as proof of
          // infeasibility.
          if (!(depth == 0 && requireSingleRoot())
              && checked >= options.treeExpansion * 4
              && successes > 0) break;
          checked++;
          trials++;
          treeProgress(
              10 + depth * 60 / Math.max(1, demands.size()),
              "Дерево: уровень "
                  + (depth + 1)
                  + "/"
                  + demands.size()
                  + ", ввод "
                  + action.entryId
                  + ", проверено продолжений "
                  + trials);
          Network trial =
              addDemand(
                  state.value.network.copy(),
                  action.demand,
                  access.get(action.demand.id),
                  demands,
                  true,
                  0,
                  action.target);
          if (trial.connected.size() <= state.value.network.connected.size()) {
            String reason = trial.reasons.getOrDefault(action.demand.id, "Подключение отклонено");
            failures.put(key, reason);
            rejected++;
            rejectedByEntry.merge(action.entryId, 1, Integer::sum);
            if (rejections.size() < 500)
              rejections.add(
                  Map.of(
                      "entryId",
                      action.entryId,
                      "targetKind",
                      action.targetKind,
                      "action",
                      action.id,
                      "reason",
                      reason,
                      "level",
                      depth + 1));
            continue;
          }
          Evaluation value = evaluate(trial, demands);
          trackRoot(value, demands);
          TreeState child =
              new TreeState(
                  value,
                  value.score + remainingEstimate(trial, demands, graph),
                  treeData == null
                      ? null
                      : new TreeTrainingData.Trace(
                          state.trace,
                          TreePolicyTraining.digest(state.signature),
                          action.id,
                          action.features));
          if (!signatures.add(child.signature)) {
            duplicates++;
            continue;
          }
          next.add(child);
          successes++;
          initialRoots.add(targetKey(action.target));
          acceptedPerEntry.merge(action.entryId, 1, Integer::sum);
          if (trial.connected.size() > partial.network.connected.size()
              || trial.connected.size() == partial.network.connected.size()
                  && value.score < partial.score) partial = value;
        }
      }
      next.sort(
          Comparator.comparingDouble((TreeState s) -> s.priority).thenComparing(s -> s.signature));
      if (next.isEmpty()) break;
      beam = new ArrayList<>();
      Set<Set<String>> connectedSets = new HashSet<>();
      if (requireSingleRoot()) {
        Set<String> keptRoots = new HashSet<>();
        for (TreeState candidate : next) {
          Network.Node root = candidate.value.network.roots().get(0);
          String key = root.existingId + ":" + Geo.key(root.point);
          if (keptRoots.add(key)) beam.add(candidate);
          if (beam.size() == options.treeBeamWidth) break;
        }
      }
      for (TreeState candidate : next) {
        if (beam.size() == options.treeBeamWidth) break;
        if (beam.contains(candidate)) continue;
        if (connectedSets.add(new TreeSet<>(candidate.value.network.connected)))
          beam.add(candidate);
        if (beam.size() == options.treeBeamWidth) break;
      }
      for (TreeState candidate : next) {
        if (beam.size() == options.treeBeamWidth) break;
        if (!beam.contains(candidate)) beam.add(candidate);
      }
      levels.add(
          Map.of(
              "level",
              depth + 1,
              "generatedStates",
              next.size(),
              "retainedStates",
              beam.size(),
              "bestPartialScore",
              beam.stream().mapToDouble(s -> s.value.score).min().orElse(0),
              "bestPriority",
              beam.get(0).priority));
      if (depth + 1 == demands.size())
        for (TreeState candidate : next) {
          Evaluation finalized = recordTree(candidate.value, demands, complete, attempts, "beam");
          if (treeData != null && !stopped()) treeData.complete(candidate.trace, finalized.score);
        }
    }
    info.put("rootOutcomes", new ArrayList<>(rootOutcomes.values()));
    info.put("levels", levels);
    info.put("testedExtensions", trials);
    info.put("rejectedExtensions", rejected);
    info.put("maskedKnownFailures", masked);
    info.put("duplicateStates", duplicates);
    info.put("rejectedByEntry", rejectedByEntry);
    info.put("rejections", rejections);
    info.put("rejectionsTruncated", rejected > rejections.size());
    // If all beam states stall, first repair a partial tree by moving a neighbouring leaf.
    if (complete.isEmpty() && !stopped()) {
      Network current = partial.network;
      for (Feature missing : demands) {
        if (current.connected.contains(missing.id) || stopped()) continue;
        Network direct =
            addDemand(current.copy(), missing, access.get(missing.id), demands, true, 0);
        if (direct.connected.size() > current.connected.size()) {
          current = direct;
          continue;
        }
        List<Feature> neighbours = new ArrayList<>(demands);
        Network fixed = current;
        neighbours.removeIf(d -> !fixed.connected.contains(d.id));
        neighbours.sort(Comparator.comparingDouble(d -> d.geometry.distance(missing.geometry)));
        for (Feature moved :
            neighbours.subList(0, Math.min(options.treeRepairCandidates, neighbours.size()))) {
          if (stopped()) break;
          Network trial = without(current, moved);
          trial = addDemand(trial, missing, access.get(missing.id), demands, true, 0);
          if (!trial.connected.contains(missing.id)) continue;
          trial = addDemand(trial, moved, access.get(moved.id), demands, true, 0);
          if (trial.connected.size() > current.connected.size()) {
            current = trial;
            break;
          }
        }
      }
      partial = evaluate(current, demands);
      recordTree(partial, demands, complete, attempts, "repair_incomplete");
    }
    complete.sort(Comparator.comparingDouble(v -> v.score));
    List<Map<String, Object>> repairs = new ArrayList<>();
    int improvements = 0;
    if (!complete.isEmpty()
        && !stopped()
        && !(resume && options.treeGroupRepair)
        && (!requireSingleRoot() || options.treeGroupRepair)) {
      Evaluation best = complete.get(0);
      int completedRepairPasses = 0;
      for (int pass = 0; pass < options.treeRepairPasses && !stopped(); pass++) {
        int previousImprovements = improvements;
        List<Feature> leaves = new ArrayList<>(demands);
        Map<String, Double> contribution = new HashMap<>();
        for (Feature demand : demands) {
          if (stopped()) break;
          contribution.put(
              demand.id, best.score - evaluate(without(best.network, demand), demands).score);
        }
        if (stopped()) break;
        leaves.sort(
            Comparator.comparingDouble((Feature d) -> contribution.get(d.id))
                .reversed()
                .thenComparing(d -> d.id));
        for (Feature demand :
            leaves.subList(0, Math.min(options.treeRepairCandidates, leaves.size()))) {
          if (stopped()) break;
          treeProgress(
              75 + pass * 15 / Math.max(1, options.treeRepairPasses),
              "Перестройка дерева: проход "
                  + (pass + 1)
                  + ", ветвь к вводу "
                  + demand.text("_tt_entry_id"));
          double before = best.score;
          Network reduced = without(best.network, demand);
          Network rebuilt = addDemand(reduced, demand, access.get(demand.id), demands, true, 0);
          boolean full = rebuilt.connected.size() == demands.size();
          Evaluation candidate = full ? smoothNetwork(evaluate(rebuilt, demands), demands) : null;
          boolean accepted = candidate != null && candidate.score < before - 1e-8;
          Map<String, Object> change = new LinkedHashMap<>();
          change.put("pass", pass + 1);
          change.put("entryId", demand.text("_tt_entry_id"));
          change.put("beforeScore", before);
          change.put("candidateScore", candidate == null ? null : candidate.score);
          change.put("accepted", accepted);
          change.put("complete", full);
          if (!full)
            change.put(
                "reason", rebuilt.reasons.getOrDefault(demand.id, "Восстановление не завершено"));
          repairs.add(change);
          if (accepted) {
            best = candidate;
            improvements++;
            recordTree(best, demands, complete, attempts, "branch_replacement");
          }
        }
        completedRepairPasses++;
        if (!stopped() && improvements == previousImprovements) {
          info.put("repairStopReason", "unchanged_network_and_candidate_set");
          break;
        }
      }
      info.put("completedRepairPasses", completedRepairPasses);
    }
    info.put("repairs", repairs);
    info.put("acceptedRepairs", improvements);
    info.put("completedLevels", levels.size());
    info.put("cancelled", stopped());
    if (attempts.isEmpty() && !resume)
      recordTree(partial, demands, complete, attempts, "beam_partial");
    saveTrees(complete);
    return partial;
  }

  /**
   * Group optimization is separate from fresh-policy validation: saved winners cannot mask a bad
   * model.
   */
  private void improveSharedTrees(
      List<Feature> demands,
      Map<String, BuildingAccess> access,
      List<Evaluation> complete,
      List<Map<String, Object>> attempts,
      Result result) {
    if (requireSingleRoot()) {
      improveTrunks(demands, access, complete, attempts, result);
      return;
    }
    @SuppressWarnings("unchecked")
    Map<String, Object> info = (Map<String, Object>) result.search.get("treeSearch");
    Map<String, Object> shared = new LinkedHashMap<>();
    List<Map<String, Object>> groups = new ArrayList<>(), roots = new ArrayList<>();
    shared.put("groupRepairEnabled", options.treeGroupRepair);
    shared.put("singleRootComparisonEnabled", options.treeSingleRootTrial);
    shared.put("groupTrials", groups);
    shared.put("singleRootTrials", roots);
    shared.put("trainingValidationUsesFreshSearchOnly", true);
    info.put("sharedOptimization", shared);
    Evaluation best = complete.stream().min(Comparator.comparingDouble(v -> v.score)).orElse(null);
    shared.put("beforeScore", best == null ? null : best.score);
    long started = System.nanoTime();
    TreeTrainingData previousData = treeData;
    treeData = options.treeLearning ? new TreeTrainingData() : null;
    int acceptedGroups = 0;
    try {
      if (options.treeGroupRepair && best != null && demands.size() > 1) {
        List<List<Feature>> clusters = demandGroups(best.network, demands);
        shared.put("plannedGroups", clusters.size());
        Set<String> tried = new HashSet<>();
        for (List<Feature> group : clusters) {
          if (stopped()) break;
          String key = treeSignature(best.network) + "|" + groupIds(group);
          if (!tried.add(key)) continue;
          progress.accept(91, "Совместная перестройка вводов " + groupIds(group));
          double before = best.score;
          Network reduced = best.network;
          for (Feature demand : group) reduced = without(reduced, demand);
          targetGroup = group;
          Coordinate center = groupCenter(group);
          List<Feature> ordered = new ArrayList<>(group);
          ordered.sort(
              Comparator.comparingDouble((Feature d) -> entryPoint(d).distance(center))
                  .reversed()
                  .thenComparing(d -> d.id));
          // Opposite ends provide distinct prospective trunks for the same nearby
          // consumers.
          for (int order = 0; order < Math.min(2, group.size()) && !stopped(); order++) {
            Feature first = ordered.get(order == 0 ? 0 : ordered.size() - 1);
            BuildingAccess building = access.get(first.id);
            if (building == null) continue;
            SpatialRules spatial = spatialFor(first, building);
            List<Target> anchors =
                targets(
                    reduced,
                    entryPoint(first),
                    Rules.diameter(first.flow()),
                    true,
                    building,
                    spatial);
            anchors.sort(Comparator.comparingDouble(t -> groupDistance(group, t.point)));
            // A bounded neighborhood; no statement of global infeasibility follows from
            // this limit.
            for (Target anchor : anchors.subList(0, Math.min(1, anchors.size()))) {
              if (stopped()) break;
              Network trial = addDemand(reduced.copy(), first, building, demands, true, 0, anchor);
              if (!trial.connected.contains(first.id)) {
                groups.add(groupTrial(group, before, null, false, trial.reasons.get(first.id)));
                continue;
              }
              joinedRoot = demandRoot(trial, first);
              List<Feature> rest = new ArrayList<>(group);
              rest.remove(first);
              rest.sort(
                  Comparator.comparingDouble((Feature d) -> entryPoint(d).distance(anchor.point))
                      .thenComparing(d -> d.id));
              trial = connectPending(trial, rest, access, demands, false);
              joinedRoot = null;
              Evaluation candidate =
                  trial.connected.size() == demands.size() && !stopped()
                      ? smoothNetwork(evaluate(trial, demands), demands)
                      : null;
              boolean accepted = candidate != null && candidate.score < best.score - 1e-8;
              groups.add(
                  groupTrial(
                      group,
                      best.score,
                      candidate,
                      accepted,
                      candidate == null ? "group_not_completed_in_checked_routes" : null));
              if (accepted) {
                best = recordTree(candidate, demands, complete, attempts, "group_replacement");
                acceptedGroups++;
              }
            }
          }
          targetGroup = List.of();
        }
      }
      if (options.treeSingleRootTrial && !requireSingleRoot() && !demands.isEmpty() && !stopped()) {
        targetGroup = demands;
        List<Target> candidates = commonRootCandidates(best, demands, access);
        shared.put("singleRootCandidateLimit", Math.max(2, options.treeBeamWidth));
        for (Target root : candidates) {
          if (stopped()) break;
          singleRoot = root;
          List<Feature> ordered = new ArrayList<>(demands);
          ordered.sort(
              Comparator.comparingDouble((Feature d) -> entryPoint(d).distance(root.point))
                  .thenComparing(d -> d.id));
          // Try near-to-far and far-to-near, always attaching subsequent entries to this
          // tree.
          for (int order = 0; order < Math.min(2, demands.size()) && !stopped(); order++) {
            progress.accept(94, "Общая врезка: сравнение дерева для всех вводов");
            List<Feature> sequence = new ArrayList<>(ordered);
            if (order == 1) Collections.reverse(sequence);
            Network trial = new Network();
            joinedRoot = null;
            trial = connectPending(trial, sequence, access, demands, true);
            String failure = trial.reasons.values().stream().findFirst().orElse(null);
            joinedRoot = null;
            Evaluation candidate =
                !stopped() && trial.connected.size() == demands.size() && trial.roots().size() == 1
                    ? smoothNetwork(evaluate(trial, demands), demands)
                    : null;
            Map<String, Object> row =
                groupTrial(
                    demands,
                    best == null ? null : best.score,
                    candidate,
                    candidate != null && (best == null || candidate.score < best.score - 1e-8),
                    failure);
            Coordinate ll = Geo.ll(root.point);
            row.put("existingId", root.existingId);
            row.put("coordinates", new double[] {ll.x, ll.y});
            row.put("order", order == 0 ? "near_to_far" : "far_to_near");
            row.put("connected", trial.connected.size());
            roots.add(row);
            if (candidate != null) {
              candidate =
                  recordTree(candidate, demands, complete, attempts, "single_root_comparison");
              if (best == null || candidate.score < best.score - 1e-8) best = candidate;
            }
          }
        }
      }
    } finally {
      singleRoot = null;
      joinedRoot = null;
      targetGroup = List.of();
      int pairs =
          treeData != null && treeTraining != null && !stopped()
              ? treeTraining.remember(treeData.preferences())
              : 0;
      shared.put("newPreferencePairsForNextRun", pairs);
      if (result.search.get("treeLearning") instanceof Map) {
        @SuppressWarnings("unchecked")
        Map<String, Object> learning = (Map<String, Object>) result.search.get("treeLearning");
        learning.put("postSearchPreferencePairs", pairs);
        learning.put(
            "newReplayPairs", ((Number) learning.get("newReplayPairs")).intValue() + pairs);
        learning.put("replayPairs", treeTraining.examples());
      }
      treeData = previousData;
    }
    shared.put("acceptedGroups", acceptedGroups);
    shared.put("afterScore", best == null ? null : best.score);
    shared.put("selectedTieIns", best == null ? null : best.network.roots().size());
    shared.put("elapsedMs", (System.nanoTime() - started) / 1000000);
    shared.put("singleRootPolicy", "compare_complete_networks_keep_best_score");
    info.put("transitionCacheHits", transitionCacheHits);
    info.put("computedTransitions", transitionComputations);
    info.put("cachedFailuresByEntry", cachedFailuresByEntry);
    info.put("searchVersion", "geometric-steiner-1.9.0");
    saveTrees(complete);
    if (treeCheckpoint != null && treeTraining != null)
      treeCheckpoint.accept(treeTraining, bestTrees(complete));
  }

  private void runCorridorTrees(
      List<Feature> demands,
      List<Evaluation> complete,
      List<Map<String, Object>> attempts,
      Map<String, Object> info) {
    if (treeRoots == null) return;
    Map<String, Object> details = new LinkedHashMap<>();
    List<Map<String, Object>> trials = new ArrayList<>();
    List<Evaluation> partials = new ArrayList<>();
    Map<Network, Map<String, Object>> partialRows = new IdentityHashMap<>();
    details.put("trials", trials);
    details.put("method", "multi_source_shortest_attachment_on_rotated_corridor_graph");
    details.put("partialEntranceCompletion", "only_if_no_complete_candidate");
    info.put("corridorTrees", details);
    int full = 0;
    long started = System.nanoTime();
    for (TreeRoots.Candidate root : treeRoots.selected) {
      if (stopped()) break;
      progress.accept(8, "Карта общих проходов: врезка " + root.existingId);
      for (int model = 0; model < (options.treeGeometricSearch ? 2 : 1) && !stopped(); model++) {
        int leafDn = Rules.diameter(demands.stream().mapToDouble(Feature::flow).max().orElse(0));
        TrunkGrid grid =
            model == 0
                ? new TrunkGrid(store, demands, root, options, this::stopped)
                : new TrunkGrid(store, demands, root, options, this::stopped, leafDn, true);
        int orderCount = demands.size() + 3 + (model == 1 ? 2 : 0);
        for (int order = 0; order < orderCount && !stopped(); order++) {
          progress.accept(
              12,
              "Общее дерево: врезка "
                  + root.existingId
                  + ", начало "
                  + (order + 1)
                  + "/"
                  + orderCount);
          Map<String, Object> row = new LinkedHashMap<>(grid.diagnostics);
          row.put("existingId", root.existingId);
          Coordinate ll = Geo.ll(root.point);
          row.put("coordinates", new double[] {ll.x, ll.y});
          row.put(
              "order",
              order == 0
                  ? "nearest_to_tree"
                  : order == 1
                      ? "farthest_first"
                      : order == 2 ? "largest_flow_first" : "seed_then_nearest");
          if (order >= 3 && order < demands.size() + 3)
            row.put("firstEntryId", demands.get(order - 3).text("_tt_entry_id"));
          if (order >= demands.size() + 3)
            row.put("order", "metric_closure_mst_" + (order - demands.size() - 3));
          row.put("complete", false);
          row.put("exactEntranceSearchCount", 0);
          try {
            Network tree =
                order < demands.size() + 3
                    ? grid.build(order)
                    : grid.buildMetricTree(order - demands.size() - 3);
            row.put("connected", tree.connected.size());
            if (tree.connected.isEmpty())
              throw new IllegalArgumentException("no_entries_reached_in_corridor_graph");
            Evaluation value = evaluate(tree, demands);
            if (tree.connected.size() == demands.size()) {
              value = recordTree(value, demands, complete, attempts, "corridor_tree");
              row.put("complete", true);
              row.put("score", value.score);
              row.put("newLengthM", value.summary.get("new_network_length"));
              full++;
            } else {
              row.put("reason", "not_all_entries_reached_in_corridor_graph");
              partials.add(value);
              partialRows.put(tree, row);
              partials.sort(
                  Comparator.comparingInt((Evaluation e) -> e.network.connected.size())
                      .reversed()
                      .thenComparingDouble(e -> e.score));
              while (partials.size() > 3) {
                Evaluation removed = partials.remove(partials.size() - 1);
                partialRows.remove(removed.network);
              }
            }
          } catch (IllegalArgumentException | TopologyException error) {
            row.put("reason", error.getMessage());
          }
          trials.add(row);
        }
      }
    }
    // Expensive exact completion is a fallback, after all inexpensive full-tree proposals.
    if (full == 0 && complete.isEmpty()) {
      Map<String, BuildingAccess> access = new LinkedHashMap<>();
      for (Feature d : demands)
        access.put(d.id, BuildingAccess.resolve(store, store.get(d.text("_tt_entry_id"))));
      for (Evaluation partial : partials) {
        if (stopped()) break;
        Network tree = partial.network;
        List<Feature> pending = new ArrayList<>();
        for (Feature d : demands) if (!tree.connected.contains(d.id)) pending.add(d);
        Map<String, Object> row = partialRows.get(tree);
        row.put("exactEntranceSearchCount", pending.size());
        progress.accept(14, "Точный подход к вводам: " + groupIds(pending));
        tree = connectPending(tree, pending, access, demands, false);
        row.put("connected", tree.connected.size());
        if (tree.connected.size() == demands.size())
          try {
            Evaluation value =
                recordTree(
                    evaluate(tree, demands),
                    demands,
                    complete,
                    attempts,
                    "corridor_tree_completed");
            row.remove("reason");
            row.put("complete", true);
            row.put("score", value.score);
            row.put("newLengthM", value.summary.get("new_network_length"));
            full++;
          } catch (IllegalArgumentException | TopologyException error) {
            row.put("reason", error.getMessage());
          }
        else row.put("failures", entryFailures(tree, demands));
      }
    }
    details.put("completeCandidates", full);
    details.put("elapsedMs", (System.nanoTime() - started) / 1000000);
    details.put("allCandidatesExactlyRevalidated", true);
    info.put("rootOutcomes", new ArrayList<>(rootOutcomes.values()));
  }

  /** Plan shared stems before terminal branches, then move complete subtrees as units. */
  private void improveTrunks(
      List<Feature> demands,
      Map<String, BuildingAccess> access,
      List<Evaluation> complete,
      List<Map<String, Object>> attempts,
      Result result) {
    @SuppressWarnings("unchecked")
    Map<String, Object> info = (Map<String, Object>) result.search.get("treeSearch");
    Map<String, Object> shared = new LinkedHashMap<>();
    List<Map<String, Object>> layouts = new ArrayList<>(),
        groups = new ArrayList<>(),
        stems = new ArrayList<>();
    shared.put("groupRepairEnabled", options.treeGroupRepair);
    shared.put("singleRootRequired", true);
    shared.put("singleRootComparisonEnabled", false);
    shared.put("singleRootTrials", layouts);
    shared.put("groupTrials", groups);
    shared.put("stemTrials", stems);
    shared.put("method", "corridor_tree_joint_junctions_and_subtree_transplant");
    shared.put("trainingValidationUsesFreshSearchOnly", true);
    info.put("sharedOptimization", shared);
    if (!info.containsKey("corridorTrees")
        && !Boolean.TRUE.equals(info.get("resumedFromBestNetwork")))
      runCorridorTrees(demands, complete, attempts, info);
    Evaluation best = complete.stream().min(Comparator.comparingDouble(v -> v.score)).orElse(null);
    shared.put("beforeScore", best == null ? null : best.score);
    long started = System.nanoTime();
    int acceptedGroups = 0, acceptedStems = 0, acceptedLayouts = 0;
    TreeTrainingData previousData = treeData;
    treeData = options.treeLearning ? new TreeTrainingData() : null;
    try {
      boolean resumed = Boolean.TRUE.equals(info.get("resumedFromBestNetwork"));
      @SuppressWarnings("unchecked")
      Map<String, Object> corridor =
          (Map<String, Object>) info.getOrDefault("corridorTrees", Map.of());
      boolean corridorComplete =
          ((Number) corridor.getOrDefault("completeCandidates", 0)).intValue() > 0;
      if (options.treeGroupRepair && treeRoots != null && !corridorComplete) {
        List<Target> roots = new ArrayList<>();
        if (best != null)
          for (Network.Node n : best.network.roots()) {
            Target t = new Target(n.point);
            t.existingId = n.existingId;
            roots.add(t);
          }
        for (TreeRoots.Candidate c : treeRoots.selected) {
          if (roots.size() >= Math.min(2, options.treeBeamWidth)) break;
          if (roots.stream()
              .anyMatch(t -> t.existingId.equals(c.existingId) && t.point.distance(c.point) < .01))
            continue;
          Target t = new Target(c.point);
          t.existingId = c.existingId;
          roots.add(t);
        }
        for (Target root : roots)
          for (int layout = 0; layout < 2 && !stopped(); layout++) {
            progress.accept(
                88, "Общий ствол: врезка " + root.existingId + ", схема " + (layout + 1));
            Network trial =
                buildSharedGroup(new Network(), demands, access, demands, root, layout == 1);
            Evaluation candidate = finishTree(trial, demands);
            boolean better =
                candidate != null && (best == null || candidate.score < best.score - 1e-8);
            Map<String, Object> row =
                groupTrial(
                    demands,
                    best == null ? null : best.score,
                    candidate,
                    better,
                    candidate == null ? "shared_trunk_not_completed_in_checked_routes" : null);
            row.put("existingId", root.existingId);
            Coordinate ll = Geo.ll(root.point);
            row.put("coordinates", new double[] {ll.x, ll.y});
            row.put("layout", layout == 0 ? "shared_hub" : "hierarchical_hubs");
            row.put("connected", trial.connected.size());
            row.put("failures", entryFailures(trial, demands));
            layouts.add(row);
            if (candidate != null) {
              candidate = recordTree(candidate, demands, complete, attempts, "shared_trunk_layout");
              if (better) {
                best = candidate;
                acceptedLayouts++;
              }
            }
          }
      }
      if (options.treeGeometricSearch && best != null) {
        GeometricOptimizer geometry =
            new GeometricOptimizer(
                store, options, this::stopped, n -> evaluate(n, demands), progress);
        best =
            geometry.improve(
                best,
                v -> recordTree(v, demands, complete, attempts, "geometric_topology_replacement"));
        shared.put("geometricOptimization", geometry.diagnostics);
      }
      if (options.treeJunctionRepair && best != null) {
        JunctionOptimizer optimizer =
            new JunctionOptimizer(
                store, options, this::stopped, n -> evaluate(n, demands), progress);
        best =
            optimizer.improve(
                best,
                v -> recordTree(v, demands, complete, attempts, "joint_junction_replacement"));
        shared.put("junctionOptimization", optimizer.diagnostics);
        if (options.treeGeometricSearch
            && options.treeRepairPasses > 0
            && ((Number) optimizer.diagnostics.getOrDefault("acceptedPatches", 0)).intValue() > 0) {
          GeometricOptimizer polish =
              new GeometricOptimizer(
                  store, options, this::stopped, n -> evaluate(n, demands), progress);
          best =
              polish.improve(
                  best,
                  v -> recordTree(v, demands, complete, attempts, "geometric_final_replacement"),
                  1);
          shared.put("geometricFinalOptimization", polish.diagnostics);
        }
      }
      if (options.treeGroupRepair && best != null) {
        for (int pass = 0; pass < options.treeRepairPasses && !stopped(); pass++) {
          int beforeChanges = acceptedGroups + acceptedStems;
          List<String> branches = new ArrayList<>();
          for (Network.Edge e : best.network.edges.values())
            if (!best.network.nodes.get(e.end).type.equals("oks")) branches.add(e.id);
          Evaluation current = best;
          branches.sort(
              Comparator.comparingDouble(
                      (String id) -> current.network.edges.get(id).geometry.getLength())
                  .reversed());
          for (String id :
              branches.subList(0, Math.min(options.treeRepairCandidates, branches.size()))) {
            if (stopped()) break;
            Network.Edge edge = best.network.edges.get(id);
            if (edge == null) continue;
            progress.accept(91, "Перенос общего ствола: проход " + (pass + 1));
            double before = best.score;
            Evaluation candidate = transplantStem(best, edge, demands);
            boolean accepted = candidate != null && candidate.score < before - 1e-8;
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("edgeId", id);
            row.put("pass", pass + 1);
            row.put("beforeScore", before);
            row.put("candidateScore", candidate == null ? null : candidate.score);
            row.put("accepted", accepted);
            row.put(
                "preservedDescendantEntries",
                descendantDemands(best.network, edge.end, demands).size());
            stems.add(row);
            if (accepted) {
              best = recordTree(candidate, demands, complete, attempts, "subtree_stem_replacement");
              acceptedStems++;
            }
          }
          List<List<Feature>> neighborhoods = trunkGroups(best.network, demands);
          for (List<Feature> group : neighborhoods) {
            if (stopped()) break;
            progress.accept(94, "Общий ствол группы вводов " + groupIds(group));
            Network reduced = best.network;
            for (Feature d : group) reduced = without(reduced, d);
            Network rebuilt =
                buildSharedGroup(reduced, group, access, demands, null, group.size() > 5);
            Evaluation candidate = finishTree(rebuilt, demands);
            Evaluation alternate = rebuildSmallGroup(reduced, group, access, demands);
            if (alternate != null && (candidate == null || alternate.score < candidate.score))
              candidate = alternate;
            boolean accepted = candidate != null && candidate.score < best.score - 1e-8;
            Map<String, Object> row =
                groupTrial(
                    group,
                    best.score,
                    candidate,
                    accepted,
                    candidate == null ? "shared_group_not_completed" : null);
            row.put("pass", pass + 1);
            groups.add(row);
            if (accepted) {
              best = recordTree(candidate, demands, complete, attempts, "shared_group_replacement");
              acceptedGroups++;
            }
          }
          shared.put("completedPasses", pass + 1);
          if (beforeChanges == acceptedGroups + acceptedStems) {
            shared.put("stopReason", "no_improving_stem_or_group");
            break;
          }
        }
      }
      if (options.treeGeometricSearch && best != null && !stopped()) {
        NetworkFacingWallOptimizer wall = new NetworkFacingWallOptimizer(
            store, options, this::stopped, n -> evaluate(n, demands));
        Evaluation redirected = wall.improve(best);
        if (redirected != best)
          best = recordTree(redirected, demands, complete, attempts, "network_facing_wall");
        shared.put("networkFacingWall", wall.diagnostics);
        FacadeAlignment align =
            new FacadeAlignment(store, this::stopped, n -> evaluate(n, demands));
        Evaluation aligned = align.improve(best);
        if (aligned != best)
          best = recordTree(aligned, demands, complete, attempts, "facade_aligned_route");
        shared.put("facadeAlignment", align.diagnostics);
      }
    } finally {
      targetGroup = List.of();
      singleRoot = null;
      joinedRoot = null;
      int pairs =
          treeData != null && treeTraining != null && !stopped()
              ? treeTraining.remember(treeData.preferences())
              : 0;
      shared.put("newPreferencePairsForNextRun", pairs);
      if (result.search.get("treeLearning") instanceof Map) {
        @SuppressWarnings("unchecked")
        Map<String, Object> learning = (Map<String, Object>) result.search.get("treeLearning");
        learning.put("postSearchPreferencePairs", pairs);
        learning.put(
            "newReplayPairs", ((Number) learning.get("newReplayPairs")).intValue() + pairs);
        learning.put("replayPairs", treeTraining.examples());
      }
      treeData = previousData;
    }
    info.put("rootOutcomes", new ArrayList<>(rootOutcomes.values()));
    shared.put("acceptedGroups", acceptedGroups);
    shared.put("acceptedStems", acceptedStems);
    shared.put("acceptedLayouts", acceptedLayouts);
    shared.put("afterScore", best == null ? null : best.score);
    shared.put("selectedTieIns", best == null ? null : best.network.roots().size());
    shared.put("elapsedMs", (System.nanoTime() - started) / 1000000);
    shared.put("singleRootPolicy", "one_root_required_no_forest_fallback");
    info.put("transitionCacheHits", transitionCacheHits);
    info.put("computedTransitions", transitionComputations);
    info.put("cachedFailuresByEntry", cachedFailuresByEntry);
    info.put("searchVersion", "geometric-steiner-1.9.0");
    saveTrees(complete);
    if (treeCheckpoint != null && treeTraining != null)
      treeCheckpoint.accept(treeTraining, bestTrees(complete));
  }

  private Evaluation rebuildSmallGroup(
      Network reduced,
      List<Feature> group,
      Map<String, BuildingAccess> access,
      List<Feature> demands) {
    if (group.size() > 4) return null;
    Evaluation best = null;
    List<Feature> ordered = new ArrayList<>(group);
    Coordinate center = groupCenter(group);
    ordered.sort(
        Comparator.comparingDouble((Feature d) -> entryPoint(d).distance(center))
            .thenComparing(d -> d.id));
    List<Feature> previous = targetGroup;
    targetGroup = group;
    try {
      for (Feature first : List.of(ordered.get(0), ordered.get(ordered.size() - 1))) {
        if (stopped()) break;
        BuildingAccess building = access.get(first.id);
        if (building == null) continue;
        List<Target> anchors =
            targets(
                reduced,
                entryPoint(first),
                Rules.diameter(first.flow()),
                true,
                building,
                spatialFor(first, building));
        anchors.sort(Comparator.comparingDouble(t -> groupDistance(group, t.point)));
        for (Target anchor : anchors.subList(0, Math.min(2, anchors.size()))) {
          if (stopped()) break;
          Network trial = addDemand(reduced.copy(), first, building, demands, true, 0, anchor);
          if (!trial.connected.contains(first.id)) continue;
          List<Feature> rest = new ArrayList<>(group);
          rest.remove(first);
          trial = connectPending(trial, rest, access, demands, false);
          Evaluation candidate = finishTree(trial, demands);
          if (candidate != null && (best == null || candidate.score < best.score)) best = candidate;
        }
      }
    } finally {
      targetGroup = previous;
    }
    return best;
  }

  private Evaluation finishTree(Network network, List<Feature> demands) {
    if (stopped() || network.connected.size() != demands.size() || network.roots().size() != 1)
      return null;
    try {
      return smoothNetwork(evaluate(trimUnused(network), demands), demands);
    } catch (IllegalArgumentException | TopologyException ignored) {
      return null;
    }
  }

  private Map<String, String> entryFailures(Network network, List<Feature> demands) {
    Map<String, String> out = new LinkedHashMap<>();
    for (Feature d : demands)
      if (!network.connected.contains(d.id))
        out.put(
            d.text("_tt_entry_id"),
            network.reasons.getOrDefault(d.id, "no_route_in_checked_candidates"));
    return out;
  }

  private Network buildSharedGroup(
      Network original,
      List<Feature> group,
      Map<String, BuildingAccess> access,
      List<Feature> demands,
      Target fixedRoot,
      boolean hierarchy) {
    if (stopped() || group.isEmpty()) return original;
    Coordinate center = groupCenter(group);
    int dn = Rules.diameter(group.stream().mapToDouble(Feature::flow).sum());
    SpatialRules spatial =
        routingRules(store.near(SpatialRules.expand(new Envelope(center), 3000)));
    List<Target> anchors =
        fixedRoot == null ? trunkTargets(original, center, dn, spatial) : List.of(fixedRoot);
    if (anchors.isEmpty()) return original;
    Network bestPartial = original;
    for (Target anchor : anchors.subList(0, Math.min(2, anchors.size()))) {
      Double bearing = targetAxis(anchor, original);
      if (bearing == null) continue;
      List<Coordinate> points = new ArrayList<>();
      for (Feature d : group) points.add(entryPoint(d));
      List<Coordinate> hubs = TrunkLayout.junctions(points, anchor.point, bearing, dn, spatial, 2);
      for (Coordinate hub : hubs) {
        if (stopped()) return bestPartial;
        Network trial = appendTrunk(original, hub, anchor, dn, spatial);
        if (trial == null) continue;
        int initial = trial.connected.size();
        // A real consumer supplies the provisional stem; no fictitious demand is
        // introduced.
        List<Feature> leaders = new ArrayList<>(group);
        leaders.sort(Comparator.comparingDouble(Feature::flow).reversed().thenComparing(d -> d.id));
        for (Feature d : leaders) {
          if (trial.connected.contains(d.id)) continue;
          Network next = addDemand(trial, d, access.get(d.id), demands, true, 0);
          if (next.connected.size() > initial) {
            trial = next;
            break;
          }
          trial.reasons.put(d.id, next.reasons.get(d.id));
        }
        if (trial.connected.size() == initial) continue;
        if (hierarchy && group.size() >= 6) {
          for (List<Feature> subgroup : TrunkLayout.split(group, bearing)) {
            List<Feature> pending = new ArrayList<>();
            for (Feature d : subgroup) if (!trial.connected.contains(d.id)) pending.add(d);
            if (pending.size() >= 2) {
              Network next = buildSharedGroup(trial, pending, access, demands, null, false);
              if (next.connected.size() > trial.connected.size()) trial = next;
            }
          }
        }
        List<Feature> order = new ArrayList<>(group);
        Network seed = trial;
        order.removeIf(d -> seed.connected.contains(d.id));
        order.sort(
            Comparator.comparingDouble((Feature d) -> distanceToNetwork(seed, entryPoint(d)))
                .thenComparing(d -> d.id));
        targetGroup = List.of();
        trial = connectPending(trial, order, access, demands, false);
        trial = trimUnused(trial);
        if (trial.connected.size() > bestPartial.connected.size()) bestPartial = trial;
        boolean fullGroup = true;
        for (Feature d : group) fullGroup &= trial.connected.contains(d.id);
        if (fullGroup) return trial;
      }
    }
    return bestPartial;
  }

  private static double distanceToNetwork(Network n, Coordinate p) {
    double distance = Double.POSITIVE_INFINITY;
    for (Network.Edge e : n.edges.values())
      distance = Math.min(distance, e.geometry.distance(Geo.point(p)));
    return distance;
  }

  private List<Target> trunkTargets(Network n, Coordinate from, int dn, SpatialRules spatial) {
    Map<String, Target> unique = new LinkedHashMap<>();
    for (Network.Edge e : n.edges.values())
      for (double at : new double[] {Geo.index(e.geometry, from), 0, e.geometry.getLength()}) {
        Target t = new Target(Geo.at(e.geometry, at));
        if (at < .05) t.nodeId = e.start;
        else if (at > e.geometry.getLength() - .05) t.nodeId = e.end;
        else t.edgeId = e.id;
        if (t.nodeId != null
            && (n.nodes.get(t.nodeId).type.equals("oks") || n.incident(t.nodeId).size() >= 4))
          continue;
        if (t.nodeId != null
            && n.nodes.get(t.nodeId).existingId != null
            && available(store.get(n.nodes.get(t.nodeId).existingId), n) <= 0) continue;
        if (!spatial.targetClear(t.point, dn, null) || t.point.distance(from) < .01) continue;
        unique.putIfAbsent(Geo.key(t.point), t);
      }
    List<Target> out = new ArrayList<>(unique.values());
    out.sort(
        Comparator.comparingDouble((Target t) -> t.point.distance(from))
            .thenComparing(Planner::targetKey));
    return out;
  }

  private Network appendTrunk(
      Network original, Coordinate hub, Target anchor, int dn, SpatialRules spatial) {
    List<LineString> occupied = new ArrayList<>();
    for (Network.Edge e : original.edges.values()) occupied.add(e.geometry);
    LineString path =
        new RouteFinder(spatial, this::stopped, occupied)
            .route(
                hub,
                anchor.point,
                dn,
                null,
                0,
                options.mode.equals("depth"),
                options.gridM,
                options.maxCells,
                targetAxis(anchor, original));
    if (path == null) return null;
    Network trial = original.copy();
    String parent = attachParent(trial, anchor);
    Network.Node junction = new Network.Node(trial.next("hub"), hub, "chamber");
    trial.nodes.put(junction.id, junction);
    Network.Edge edge =
        new Network.Edge(trial.next("stem"), parent, junction.id, (LineString) path.reverse());
    trial.edges.put(edge.id, edge);
    return trial;
  }

  private String attachParent(Network network, Target target) {
    if (target.nodeId != null) return target.nodeId;
    if (target.edgeId != null) return network.split(target.edgeId, target.point);
    for (Network.Node n : network.roots())
      if (n.existingId.equals(target.existingId) && n.point.distance(target.point) < .01)
        return n.id;
    Network.Node n = new Network.Node(network.next("tie"), target.point, "tie");
    n.existingId = target.existingId;
    network.nodes.put(n.id, n);
    return n.id;
  }

  private Evaluation transplantStem(Evaluation original, Network.Edge stem, List<Feature> demands) {
    Set<String> descendants = descendants(original.network, stem.end);
    Network remaining = original.network.copy();
    remaining.edges.remove(stem.id);
    remaining.edges.values().removeIf(e -> descendants.contains(e.start));
    remaining.nodes.keySet().removeIf(descendants::contains);
    Coordinate from = original.network.nodes.get(stem.end).point;
    SpatialRules spatial = routingRules(store.near(SpatialRules.expand(new Envelope(from), 3000)));
    List<Target> targets = trunkTargets(remaining, from, stem.dn, spatial);
    Evaluation best = null;
    for (Target target : targets.subList(0, Math.min(3, targets.size()))) {
      if (stopped()) break;
      if (target.point.distance(original.network.nodes.get(stem.start).point) < .01
          && stem.geometry.getNumPoints() == 2) continue;
      Network trial = original.network.copy();
      trial.edges.remove(stem.id);
      List<LineString> occupied = new ArrayList<>();
      for (Network.Edge e : trial.edges.values()) occupied.add(e.geometry);
      LineString path =
          new RouteFinder(spatial, this::stopped, occupied)
              .route(
                  from,
                  target.point,
                  stem.dn,
                  null,
                  0,
                  options.mode.equals("depth"),
                  options.gridM,
                  options.maxCells,
                  targetAxis(target, remaining));
      if (path == null) continue;
      String parent = attachParent(trial, target);
      Network.Edge replacement =
          new Network.Edge(trial.next("stem"), parent, stem.end, (LineString) path.reverse());
      trial.edges.put(replacement.id, replacement);
      Evaluation candidate = finishTree(trial, demands);
      if (candidate != null && (best == null || candidate.score < best.score)) best = candidate;
    }
    return best;
  }

  private static Set<String> descendants(Network n, String start) {
    Set<String> seen = new HashSet<>();
    Deque<String> todo = new ArrayDeque<>();
    todo.add(start);
    while (!todo.isEmpty()) {
      String id = todo.remove();
      if (!seen.add(id)) continue;
      for (Network.Edge e : n.children(id)) todo.add(e.end);
    }
    return seen;
  }

  private static List<Feature> descendantDemands(Network n, String start, List<Feature> demands) {
    Set<String> nodes = descendants(n, start), entries = new HashSet<>();
    for (String id : nodes) {
      String entry = n.nodes.get(id).entryId;
      if (entry != null) entries.add(entry);
    }
    List<Feature> out = new ArrayList<>();
    for (Feature d : demands) if (entries.contains(d.text("_tt_entry_id"))) out.add(d);
    return out;
  }

  private List<List<Feature>> trunkGroups(Network n, List<Feature> demands) {
    Map<String, List<Feature>> groups = new LinkedHashMap<>();
    for (Network.Edge e : n.edges.values()) {
      List<Feature> group = descendantDemands(n, e.end, demands);
      if (group.size() >= 2 && group.size() <= 6 && group.size() < demands.size())
        groups.putIfAbsent(groupIds(group).toString(), group);
    }
    for (List<Feature> group : demandGroups(n, demands))
      groups.putIfAbsent(groupIds(group).toString(), group);
    List<List<Feature>> sorted = new ArrayList<>(groups.values());
    Map<String, Double> removed = new HashMap<>();
    double total = n.edges.values().stream().mapToDouble(e -> e.geometry.getLength()).sum();
    for (List<Feature> group : sorted) {
      Network reduced = n;
      for (Feature d : group) reduced = without(reduced, d);
      removed.put(
          groupIds(group).toString(),
          total - reduced.edges.values().stream().mapToDouble(e -> e.geometry.getLength()).sum());
    }
    sorted.sort(
        Comparator.comparingDouble((List<Feature> g) -> removed.get(groupIds(g).toString()))
            .reversed());
    List<List<Feature>> out = new ArrayList<>();
    Set<String> covered = new HashSet<>();
    for (List<Feature> group : sorted) {
      if (!addsCoverage(group, covered)) continue;
      out.add(group);
      for (Feature d : group) covered.add(d.id);
      if (out.size() >= Math.min(4, Math.max(1, options.treeRepairCandidates / 2))) break;
    }
    return out;
  }

  private static Network trimUnused(Network original) {
    Network n = original.copy();
    boolean changed;
    do {
      changed = false;
      for (Network.Node node : new ArrayList<>(n.nodes.values())) {
        if (node.type.equals("oks") || !n.children(node.id).isEmpty()) continue;
        n.edges.values().removeIf(e -> e.end.equals(node.id));
        n.nodes.remove(node.id);
        changed = true;
      }
    } while (changed);
    boolean merged;
    do {
      merged = false;
      for (Network.Node node : new ArrayList<>(n.nodes.values())) {
        if (!node.type.equals("chamber")
            || n.incident(node.id).size() != 2
            || n.children(node.id).size() != 1) continue;
        Network.Edge incoming =
            n.edges.values().stream().filter(e -> e.end.equals(node.id)).findFirst().orElse(null);
        if (incoming == null) continue;
        Network.Edge outgoing = n.children(node.id).get(0);
        List<Coordinate> points =
            new ArrayList<>(Arrays.asList(incoming.geometry.getCoordinates()));
        Coordinate[] tail = outgoing.geometry.getCoordinates();
        for (int i = 1; i < tail.length; i++) points.add(tail[i]);
        n.edges.remove(incoming.id);
        n.edges.remove(outgoing.id);
        n.nodes.remove(node.id);
        n.edges.put(
            incoming.id,
            new Network.Edge(
                incoming.id,
                incoming.start,
                outgoing.end,
                Geo.line(points.toArray(new Coordinate[0]))));
        merged = true;
        break;
      }
    } while (merged);
    return n;
  }

  private static Map<String, Object> groupTrial(
      List<Feature> group, Double before, Evaluation candidate, boolean accepted, String reason) {
    Map<String, Object> row = new LinkedHashMap<>();
    row.put("entryIds", groupIds(group));
    row.put("beforeScore", before);
    row.put("candidateScore", candidate == null ? null : candidate.score);
    row.put("complete", candidate != null);
    row.put("accepted", accepted);
    if (candidate != null) {
      row.put("tieIns", candidate.network.roots().size());
      row.put("newLengthM", candidate.summary.get("new_network_length"));
      row.put("calculatedCost", candidate.summary.get("calculated_cost"));
    }
    if (reason != null) row.put("reason", reason);
    return row;
  }

  private static List<String> groupIds(List<Feature> group) {
    List<String> ids = new ArrayList<>();
    for (Feature d : group) ids.add(d.text("_tt_entry_id"));
    Collections.sort(ids);
    return ids;
  }

  private Coordinate entryPoint(Feature d) {
    return InputData.portal(store.get(d.text("_tt_entry_id")));
  }

  private SpatialRules spatialFor(Feature d, BuildingAccess building) {
    return demandSpatial.computeIfAbsent(
        d.id,
        ignored -> {
          Envelope area = new Envelope(entryPoint(d));
          area.expandBy(
              Rules.LENGTH[Math.min(Rules.DN.length - 1, Rules.index(Rules.diameter(d.flow())) + 1)]
                  + 1200);
          return routingRules(store.near(area));
        });
  }

  private List<List<Feature>> demandGroups(Network network, List<Feature> demands) {
    Map<String, List<Feature>> unique = new LinkedHashMap<>();
    // Existing small subtrees and nearby terminals are complementary neighborhoods.
    for (Network.Edge edge : network.edges.values()) {
      Set<String> descendants = new HashSet<>();
      Deque<String> pending = new ArrayDeque<>();
      pending.add(edge.end);
      while (!pending.isEmpty()) {
        String id = pending.remove();
        if (!descendants.add(id)) continue;
        for (Network.Edge next : network.children(id)) pending.add(next.end);
      }
      List<Feature> group = new ArrayList<>();
      for (Feature d : demands)
        if (network.nodes.values().stream()
            .anyMatch(n -> descendants.contains(n.id) && d.text("_tt_entry_id").equals(n.entryId)))
          group.add(d);
      if (group.size() >= 2 && group.size() <= 4)
        unique.putIfAbsent(groupIds(group).toString(), group);
    }
    for (Feature seed : demands) {
      List<Feature> nearest = new ArrayList<>(demands);
      nearest.sort(
          Comparator.comparingDouble((Feature d) -> entryPoint(seed).distance(entryPoint(d)))
              .thenComparing(d -> d.id));
      for (int count : new int[] {2, 3, 4})
        if (count <= nearest.size()) {
          List<Feature> group = new ArrayList<>(nearest.subList(0, count));
          unique.putIfAbsent(groupIds(group).toString(), group);
        }
    }
    List<List<Feature>> groups = new ArrayList<>(unique.values());
    Map<String, Double> opportunity = new HashMap<>();
    double totalLength =
        network.edges.values().stream().mapToDouble(e -> e.geometry.getLength()).sum();
    for (List<Feature> group : groups) {
      Network reduced = network;
      for (Feature d : group) reduced = without(reduced, d);
      double removed =
          totalLength
              - reduced.edges.values().stream().mapToDouble(e -> e.geometry.getLength()).sum();
      Coordinate center = groupCenter(group);
      double nearest = Double.POSITIVE_INFINITY;
      for (Network.Edge e : reduced.edges.values())
        nearest = Math.min(nearest, e.geometry.distance(Geo.point(center)));
      for (Feature e : store.all("heat_network"))
        nearest = Math.min(nearest, e.geometry.distance(Geo.point(center)));
      Set<String> rootKeys = new HashSet<>();
      for (Feature d : group) rootKeys.add(demandRoot(network, d));
      // An ordering heuristic only; exact full-network score decides acceptance.
      opportunity.put(
          groupIds(group).toString(),
          removed - nearest - groupDistance(group, center) + 50 * Math.max(0, rootKeys.size() - 1));
    }
    groups.sort(
        Comparator.comparingDouble((List<Feature> g) -> opportunity.get(groupIds(g).toString()))
            .reversed()
            .thenComparing(g -> groupIds(g).toString()));
    List<List<Feature>> selected = new ArrayList<>();
    Set<String> covered = new HashSet<>();
    int quota = Math.min(6, Math.max(2, options.treeRepairCandidates / 2));
    for (int size : new int[] {4, 3, 2}) {
      int taken = 0;
      for (List<Feature> group : groups)
        if (group.size() == size
            && addsCoverage(group, covered)
            && taken++ < Math.max(1, quota / 3)
            && selected.size() < quota) {
          selected.add(group);
          for (Feature d : group) covered.add(d.id);
        }
    }
    for (List<Feature> group : groups)
      if (selected.size() < quota && !selected.contains(group) && addsCoverage(group, covered)) {
        selected.add(group);
        for (Feature d : group) covered.add(d.id);
      }
    return selected;
  }

  private static boolean addsCoverage(List<Feature> group, Set<String> covered) {
    return group.stream().filter(d -> !covered.contains(d.id)).count() >= (group.size() + 1) / 2;
  }

  private Network connectPending(
      Network trial,
      List<Feature> order,
      Map<String, BuildingAccess> access,
      List<Feature> demands,
      boolean commonRoot) {
    List<Feature> pending = new ArrayList<>(order);
    for (int sweep = 0; sweep < 3 && !pending.isEmpty() && !stopped(); sweep++) {
      int before = trial.connected.size();
      for (Feature demand : new ArrayList<>(pending)) {
        if (stopped()) break;
        progress.accept(
            commonRoot ? 94 : 91,
            (commonRoot ? "Общая врезка" : "Группа " + groupIds(order))
                + ": подключено "
                + trial.connected.size()
                + "/"
                + demands.size()
                + ", ввод "
                + demand.text("_tt_entry_id")
                + (sweep > 0 ? " (повтор после роста дерева)" : ""));
        if (commonRoot) targetGroup = pendingNeighbours(trial, demand, demands);
        Network next = addDemand(trial, demand, access.get(demand.id), demands, true, 0);
        if (next.connected.contains(demand.id)) {
          trial = next;
          pending.remove(demand);
          if (commonRoot) joinedRoot = demandRoot(trial, demand);
        } else
          trial.reasons.put(demand.id, next.reasons.getOrDefault(demand.id, "route_not_found"));
      }
      if (trial.connected.size() == before) break;
    }
    return trial;
  }

  private List<Target> commonRootCandidates(
      Evaluation best, List<Feature> demands, Map<String, BuildingAccess> access) {
    TreeRoots screened =
        new TreeRoots(
            store, demands, treeCorridors, Math.max(2, options.treeBeamWidth), this::stopped);
    List<Target> roots = new ArrayList<>();
    for (TreeRoots.Candidate candidate : screened.selected) {
      Target t = new Target(candidate.point);
      t.existingId = candidate.existingId;
      roots.add(t);
    }
    return roots;
  }

  private void trackRoot(Evaluation value, List<Feature> demands) {
    if (value.network.roots().size() != 1) return;
    Network.Node root = value.network.roots().get(0);
    String key = root.existingId + ":" + Geo.key(root.point);
    Map<String, Object> previous = rootOutcomes.get(key);
    int connected = value.network.connected.size();
    if (previous != null && ((Number) previous.get("connected")).intValue() > connected) return;
    if (previous != null
        && ((Number) previous.get("connected")).intValue() == connected
        && ((Number) previous.get("score")).doubleValue() <= value.score) return;
    Map<String, Object> row = new LinkedHashMap<>();
    Coordinate ll = Geo.ll(root.point);
    row.put("existingId", root.existingId);
    row.put("coordinates", new double[] {ll.x, ll.y});
    row.put("connected", connected);
    row.put("complete", connected == demands.size());
    row.put("score", value.score);
    row.put("unresolvedEntryIds", new ArrayList<>(entryFailures(value.network, demands).keySet()));
    rootOutcomes.put(key, row);
  }

  private List<Feature> pendingNeighbours(Network network, Feature demand, List<Feature> demands) {
    List<Feature> pending = new ArrayList<>();
    for (Feature d : demands) if (!network.connected.contains(d.id)) pending.add(d);
    pending.sort(
        Comparator.comparingDouble((Feature d) -> entryPoint(demand).distance(entryPoint(d)))
            .thenComparing(d -> d.id));
    return new ArrayList<>(pending.subList(0, Math.min(4, pending.size())));
  }

  private double remainingEstimate(Network network, List<Feature> demands, CorridorGraph graph) {
    List<Feature> remaining = new ArrayList<>();
    for (Feature d : demands) if (!network.connected.contains(d.id)) remaining.add(d);
    double total = 0;
    List<Feature> existing = store.all("heat_network");
    for (Feature demand : remaining) {
      if (stopped()) return total;
      Coordinate from = InputData.portal(store.get(demand.text("_tt_entry_id")));
      double nearest = Double.POSITIVE_INFINITY;
      for (Network.Edge edge : network.edges.values())
        nearest =
            Math.min(
                nearest,
                graph.distance(from, Geo.at(edge.geometry, Geo.index(edge.geometry, from))));
      for (Feature edge :
          requireSingleRoot() && !network.roots().isEmpty()
              ? Collections.<Feature>emptyList()
              : existing) {
        LineString line = (LineString) edge.geometry;
        nearest = Math.min(nearest, graph.distance(from, Geo.at(line, Geo.index(line, from))));
      }
      for (Feature other : remaining)
        if (other != demand)
          nearest =
              Math.min(
                  nearest,
                  graph.distance(from, InputData.portal(store.get(other.text("_tt_entry_id")))));
      if (Double.isFinite(nearest)) {
        double length = nearest / 2; // heuristic only, not an admissible engineering bound
        total +=
            options.score(length * Rules.NEW[Rules.index(Rules.diameter(demand.flow()))], length);
      }
    }
    return total;
  }

  private Evaluation recordTree(
      Evaluation value,
      List<Feature> demands,
      List<Evaluation> complete,
      List<Map<String, Object>> attempts,
      String stage) {
    trackRoot(value, demands);
    boolean full = value.network.connected.size() == demands.size();
    if (full && !stopped()) value = smoothNetwork(value, demands);
    Map<String, Object> attempt = new LinkedHashMap<>();
    attempt.put("attempt", attempts.size() + 1);
    attempt.put("strategy", "tree");
    attempt.put("stage", stage);
    attempt.put("connected", value.network.connected.size());
    attempt.put("required", demands.size());
    attempt.put("complete", full);
    attempt.put("usedForTraining", false);
    attempt.put("scoreAfterSmoothing", full ? value.score : null);
    attempts.add(attempt);
    if (full) {
      value.summary.put("routing_strategy", "tree");
      value.summary.put("source", stage);
      value.summary.put("source_attempt", attempts.size());
      Evaluation candidate = value;
      retainBetter(complete, candidate);
      saveTrees(complete);
    }
    return value;
  }

  private List<Evaluation> bestTrees(List<Evaluation> complete) {
    List<Evaluation> sorted = new ArrayList<>(preservedTrees);
    for (Evaluation v : complete) retainBetter(sorted, v);
    sorted.sort(Comparator.comparingDouble(v -> v.score));
    return new ArrayList<>(sorted.subList(0, Math.min(3, sorted.size())));
  }

  private static void retainBetter(List<Evaluation> values, Evaluation candidate) {
    for (int i = 0; i < values.size(); i++)
      if (same(values.get(i).network, candidate.network)) {
        if (candidate.score < values.get(i).score - 1e-8) values.set(i, candidate);
        return;
      }
    values.add(candidate);
  }

  private void saveTrees(List<Evaluation> complete) {
    if (checkpoint != null) checkpoint.accept(null, bestTrees(complete));
  }

  private static boolean networkContainsEntry(Network network, Feature demand) {
    return network.connected.contains(demand.id);
  }

  private List<Feature> ordered(List<Feature> demands, int attempt, Set<String> difficult) {
    List<Feature> order = new ArrayList<>(demands);
    Comparator<Feature> comparator;
    switch (attempt % 6) {
      case 1:
        comparator = Comparator.comparingDouble(this::distanceToExisting);
        break;
      case 2:
        comparator = Comparator.comparingDouble(this::distanceToExisting).reversed();
        break;
      case 3:
        comparator = Comparator.comparingDouble(f -> f.geometry.getCoordinate().x);
        break;
      case 4:
        comparator = Comparator.comparingDouble(f -> f.geometry.getCoordinate().y);
        break;
      case 5:
        comparator = Comparator.comparingDouble(Feature::flow);
        break;
      default:
        comparator = Comparator.comparingDouble(Feature::flow).reversed();
    }
    comparator = comparator.thenComparing(f -> f.id);
    if (attempt >= 6) comparator = comparator.reversed();
    if (attempt % 2 == 1 && !difficult.isEmpty())
      comparator =
          Comparator.comparingInt((Feature f) -> difficult.contains(f.id) ? 0 : 1)
              .thenComparing(comparator);
    order.sort(comparator);
    return order;
  }

  private final Map<String, Double> distances = new HashMap<>();

  /** One action selects BOTH an unconnected consumer and a place on the growing network. */
  public static final class RlAction {
    public final String id, entryId, targetKind;
    public final double[] features;
    private final Feature demand;
    private final Target target;

    private RlAction(Feature demand, Target target, double[] features) {
      this.demand = demand;
      this.target = target;
      this.features = features;
      entryId = demand.text("_tt_entry_id");
      targetKind =
          target.existingId != null ? "existing" : target.edgeId != null ? "branch" : "node";
      id =
          demand.id
              + "@"
              + targetKind
              + ":"
              + Objects.toString(target.existingId, "")
              + Objects.toString(target.edgeId, "")
              + Objects.toString(target.nodeId, "")
              + ":"
              + Double.toHexString(target.point.x)
              + ":"
              + Double.toHexString(target.point.y);
    }
  }

  // Memoization accelerates repeated training rollouts, without changing transitions or rewards.
  private final Map<String, Optional<Network>> rlTransitions =
      new LinkedHashMap<String, Optional<Network>>(128, .75f, true) {
        protected boolean removeEldestEntry(Map.Entry<String, Optional<Network>> e) {
          return size() > 512;
        }
      };

  public RlEpisode newRlEpisode() {
    if (!options.routingStrategy.equals("reinforcement"))
      throw new IllegalStateException("RL environment requires routingStrategy=reinforcement");
    List<Feature> demands = InputData.demands(store);
    Map<String, BuildingAccess> access = new LinkedHashMap<>();
    for (Feature demand : demands) {
      try {
        access.put(
            demand.id, BuildingAccess.resolve(store, store.get(demand.text("_tt_entry_id"))));
      } catch (IllegalArgumentException ignored) {
        /* Missing access makes the episode incomplete. */
      }
    }
    return new RlEpisode(demands, access);
  }

  public static double rlReward(Evaluation evaluated, int required) {
    double normalized = evaluated.score / Math.max(1, required);
    double boundedCost = normalized / (1 + normalized);
    return evaluated.network.connected.size() == required
        ? -boundedCost
        : -2 - (required - evaluated.network.connected.size()) / (double) Math.max(1, required);
  }

  /** Exact engineering transitions, shared by offline training and production inference. */
  public final class RlEpisode {
    private final List<Feature> demands;
    private final Map<String, BuildingAccess> access;
    private Network network = new Network();
    private final List<Feature> order = new ArrayList<>();
    private List<RlAction> available;
    private String history = "";

    private RlEpisode(List<Feature> demands, Map<String, BuildingAccess> access) {
      this.demands = demands;
      this.access = access;
    }

    public int connected() {
      return network.connected.size();
    }

    public int required() {
      return demands.size();
    }

    public Evaluation evaluation() {
      return evaluate(network.copy(), demands);
    }

    public double reward() {
      return rlReward(evaluation(), demands.size());
    }

    public List<RlAction> actions() {
      if (available != null) return Collections.unmodifiableList(available);
      available = new ArrayList<>();
      if (stopped()) return Collections.unmodifiableList(available);
      double currentScore = evaluation().score;
      for (Feature demand : demands) {
        if (stopped()) break;
        if (network.connected.contains(demand.id) || !access.containsKey(demand.id)) continue;
        BuildingAccess building = access.get(demand.id);
        try {
          int dn = Rules.diameter(demand.flow());
          Coordinate from = InputData.portal(building.entry);
          Envelope box = new Envelope(from);
          box.expandBy(Rules.LENGTH[Math.min(Rules.DN.length - 1, Rules.index(dn) + 1)] + 1200);
          SpatialRules spatial =
              demandSpatial.computeIfAbsent(demand.id, id -> routingRules(store.near(box)));
          for (Target target : targets(network, from, dn, true, building, spatial)) {
            if (stopped()) break;
            double[] local =
                CandidateFeatures.extract(
                    store,
                    network,
                    building,
                    target.point,
                    targetAxis(target, network),
                    dn,
                    demand.flow(),
                    target.existingId != null,
                    target.edgeId != null,
                    currentScore);
            double[] features =
                ReinforcementFeatures.context(
                    local, store, network, demands, demand, target.point, currentScore);
            available.add(new RlAction(demand, target, features));
          }
        } catch (IllegalArgumentException | TopologyException e) {
          network.reasons.put(demand.id, e.getMessage());
        }
      }
      return Collections.unmodifiableList(available);
    }

    public boolean step(int index) {
      List<RlAction> choices = actions();
      if (index < 0 || index >= choices.size())
        throw new IllegalArgumentException("Invalid RL action");
      if (stopped()) return false;
      RlAction action = choices.get(index);
      String key = history + "|" + action.id;
      Optional<Network> cached = rlTransitions.get(key);
      Network next;
      if (cached != null) next = cached.isPresent() ? cached.get().copy() : network;
      else {
        next =
            addDemand(
                network.copy(),
                action.demand,
                access.get(action.demand.id),
                demands,
                true,
                0,
                action.target);
        if (!stopped())
          rlTransitions.put(
              key,
              next.connected.size() > network.connected.size()
                  ? Optional.of(next.copy())
                  : Optional.empty());
      }
      if (next.connected.size() > network.connected.size()) {
        network = next;
        order.add(action.demand);
        history = key;
        available = null;
        return true;
      }
      network.reasons.put(
          action.demand.id,
          next.reasons.getOrDefault(
              action.demand.id,
              "RL: выбранное подключение не прошло геометрическую или инженерную" + " проверку"));
      // Mask only this failed action in THIS state. Adding a branch resets the mask.
      available.remove(index);
      return false;
    }
  }

  private double distanceToExisting(Feature demand) {
    return distances.computeIfAbsent(
        demand.id,
        id -> {
          Geometry point = store.get(demand.text("_tt_entry_id")).geometry;
          double distance = Double.POSITIVE_INFINITY;
          for (Feature line : store.all("heat_network"))
            distance = Math.min(distance, line.geometry.distance(point));
          return distance;
        });
  }

  private Evaluation evaluate(Network network, List<Feature> demands) {
    return new Evaluation(
        network, store, demands, options.mode.equals("depth"), options.maxDepthM, options);
  }

  private final Map<String, SpatialRules> demandSpatial = new HashMap<>();
  private final Map<String, Optional<LineString>> routeCache =
      new LinkedHashMap<String, Optional<LineString>>(128, .75f, true) {
        protected boolean removeEldestEntry(Map.Entry<String, Optional<LineString>> e) {
          return size() > 4096;
        }
      };

  private SpatialRules routingRules(List<Feature> objects) {
    return new SpatialRules(
        objects,
        !options.routingStrategy.equals("classic") || !options.neuralGuidance.equals("baseline"));
  }

  private List<Feature> targetGroup = List.of();
  private Target singleRoot;
  private String joinedRoot;
  private long transitionCacheHits, transitionComputations;
  private final Map<String, Integer> cachedFailuresByEntry = new LinkedHashMap<>();
  private List<PreferenceObservation> emittedPreferences;

  private static final class PreferenceObservation {
    final String state, action;
    final double[] features;
    final double score;

    PreferenceObservation(String state, String action, double[] features, double score) {
      this.state = state;
      this.action = action;
      this.features = features.clone();
      this.score = score;
    }
  }

  private static final class CachedTransition {
    final Network network;
    final List<PreferenceObservation> preferences;

    CachedTransition(Network network, List<PreferenceObservation> preferences) {
      this.network = network.copy();
      this.preferences = new ArrayList<>(preferences);
    }
  }

  private final Map<String, CachedTransition> treeTransitions =
      new LinkedHashMap<String, CachedTransition>(128, .75f, true) {
        protected boolean removeEldestEntry(Map.Entry<String, CachedTransition> e) {
          return size() > 2048;
        }
      };

  private void observeTreePreference(String state, String action, double[] features, double score) {
    if (treeData != null) treeData.observe(state, action, features, score);
    if (emittedPreferences != null)
      emittedPreferences.add(new PreferenceObservation(state, action, features, score));
  }

  private static String targetKey(Target t) {
    return t == null
        ? "all"
        : t.existingId
            + ":"
            + t.nodeId
            + ":"
            + t.edgeId
            + ":"
            + Double.toHexString(t.point.x)
            + ":"
            + Double.toHexString(t.point.y);
  }

  private Network addDemand(
      Network network,
      Feature demand,
      BuildingAccess access,
      List<Feature> demands,
      boolean join,
      int attempt) {
    return addDemand(network, demand, access, demands, join, attempt, null);
  }

  private Network addDemand(
      Network network,
      Feature demand,
      BuildingAccess access,
      List<Feature> demands,
      boolean join,
      int attempt,
      Target forcedTarget) {
    if (!options.routingStrategy.equals("tree"))
      return computeDemand(network, demand, access, demands, join, attempt, forcedTarget);
    String group = targetGroup.stream().map(d -> d.id).reduce("", (a, b) -> a + ":" + b);
    String key =
        transitionSignature(network)
            + "|"
            + demand.id
            + "|"
            + join
            + "|"
            + targetKey(forcedTarget)
            + "|"
            + targetKey(singleRoot)
            + "|"
            + joinedRoot
            + "|"
            + group;
    CachedTransition cached = treeTransitions.get(key);
    if (cached != null) {
      transitionCacheHits++;
      if (!cached.network.connected.contains(demand.id))
        cachedFailuresByEntry.merge(demand.text("_tt_entry_id"), 1, Integer::sum);
      if (treeData != null)
        for (PreferenceObservation p : cached.preferences)
          treeData.observe(p.state, p.action, p.features, p.score);
      return cached.network.copy();
    }
    transitionComputations++;
    List<PreferenceObservation> previous = emittedPreferences;
    emittedPreferences = new ArrayList<>();
    try {
      Network value = computeDemand(network, demand, access, demands, join, 0, forcedTarget);
      if (!stopped()) treeTransitions.put(key, new CachedTransition(value, emittedPreferences));
      return value;
    } finally {
      emittedPreferences = previous;
    }
  }

  private Network computeDemand(
      Network network,
      Feature demand,
      BuildingAccess access,
      List<Feature> demands,
      boolean join,
      int attempt,
      Target forcedTarget) {
    if (access == null) {
      network.reasons.put(demand.id, "Неоднозначный ввод здания");
      return network;
    }
    Evaluation best = null;
    int bestTarget = Integer.MAX_VALUE, bestMode = Integer.MAX_VALUE;
    String last = "Коридор не найден среди проверенных кандидатов и областей поиска";
    int dn;
    try {
      dn = Rules.diameter(demand.flow());
    } catch (IllegalArgumentException e) {
      network.reasons.put(demand.id, e.getMessage());
      return network;
    }
    Coordinate sourcePoint = InputData.portal(access.entry);
    // A new demand edge cannot exceed the largest same-DN component allowed
    // after the single permitted diameter promotion. Existing shared edges may
    // have stricter limits; Evaluation still checks the whole network.
    double maximumLength = Rules.LENGTH[Math.min(Rules.DN.length - 1, Rules.index(dn) + 1)];
    SpatialRules spatial =
        demandSpatial.computeIfAbsent(
            demand.id,
            id -> {
              Envelope box = new Envelope(sourcePoint);
              box.expandBy(Rules.LENGTH[Math.min(Rules.DN.length - 1, Rules.index(dn) + 1)] + 1200);
              return routingRules(store.near(box));
            });
    List<Target> targets;
    try {
      targets =
          forcedTarget == null
              ? targets(network, sourcePoint, dn, join, access, spatial)
              : new ArrayList<>(List.of(forcedTarget));
    } catch (IllegalArgumentException e) {
      network.reasons.put(demand.id, e.getMessage());
      return network;
    }
    List<LineString> occupied = new ArrayList<>();
    for (Network.Edge edge : network.edges.values()) occupied.add(edge.geometry);
    boolean bounded =
        forcedTarget == null
            && (options.routingStrategy.equals("tree")
                || List.of("bounds", "on").contains(options.neuralGuidance));
    boolean learned =
        forcedTarget == null
            && options.neuralGuidance.equals("on")
            && NeuralRanker.bundled().available();
    String preferenceState =
        (treeData != null || emittedPreferences != null)
                && network.connected.size() == demands.size() - 1
            ? "final:" + TreePolicyTraining.digest(treeSignature(network))
            : null;
    double preferenceCurrentScore = preferenceState == null ? 0 : evaluate(network, demands).score;
    long group = ++candidateGroups, preparedAt = System.nanoTime();
    for (int i = 0; i < targets.size(); i++) {
      if (stopped()) break;
      Target target = targets.get(i);
      target.ordinal = i;
      if (bounded || candidateObserver != null) {
        try {
          Attachment template =
              attach(
                  network,
                  demand,
                  access,
                  target,
                  Geo.line(sourcePoint, target.point),
                  spatial,
                  dn,
                  true);
          target.lowerBound =
              CandidateBound.calculate(template.network, template.edgeId, store, options).score;
        } catch (IllegalArgumentException | TopologyException e) {
          // A failed estimate cannot remove a candidate from the ordinary search.
          target.lowerBound = 0;
          featureFailures++;
        }
        target.priority = target.lowerBound;
        if (learned || candidateObserver != null) {
          try {
            target.features =
                CandidateFeatures.extract(
                    store,
                    network,
                    access,
                    target.point,
                    targetAxis(target, network),
                    dn,
                    demand.flow(),
                    target.existingId != null,
                    target.edgeId != null,
                    target.lowerBound);
            if (learned) {
              target.priority = NeuralRanker.bundled().priority(target.lowerBound, target.features);
              neuralPredictions++;
            }
          } catch (IllegalArgumentException | TopologyException e) {
            featureFailures++;
          }
        }
      }
    }
    if (bounded)
      targets.sort(
          Comparator.comparingDouble((Target t) -> t.priority).thenComparingInt(t -> t.ordinal));
    preparationNanos += System.nanoTime() - preparedAt;
    for (Target target : targets) {
      if (stopped()) break;
      candidatesSeen++;
      if (bounded && best != null && target.lowerBound > best.score + 1e-8) {
        candidatesPruned++;
        continue;
      }
      targetsSearched++;
      double targetScore = Double.POSITIVE_INFINITY;
      int modeOrdinal = 0;
      RouteFinder finder =
          new RouteFinder(spatial, this::stopped, occupied).withMaxLength(maximumLength);
      Set<String> seen = new HashSet<>();
      for (Coordinate from : access.candidates(target.point))
        for (int mode = 0; mode < 3 && !stopped(); mode++) {
          int originalMode = modeOrdinal++;
          int routeMode = (mode + attempt) % 3;
          Double axis = targetAxis(target, network);
          String key =
              demand.id
                  + ":"
                  + Double.toHexString(from.x)
                  + ":"
                  + Double.toHexString(from.y)
                  + ":"
                  + Double.toHexString(target.point.x)
                  + ":"
                  + Double.toHexString(target.point.y)
                  + ":"
                  + dn
                  + ":"
                  + routeMode
                  + ":"
                  + axis;
          Optional<LineString> cached = routeCache.get(key);
          if (cached == null) {
            routeSearches++;
            cached =
                Optional.ofNullable(
                    new RouteFinder(spatial, this::stopped)
                        .withMaxLength(maximumLength)
                        .route(
                            from,
                            target.point,
                            dn,
                            access.buildingId(),
                            routeMode,
                            options.mode.equals("depth"),
                            options.gridM,
                            options.maxCells,
                            axis));
            if (!stopped()) routeCache.put(key, cached);
          }
          if (cached.isEmpty()) {
            last =
                "ROUTE_SEARCH_EXHAUSTED: геометрический поиск не нашёл коридор для"
                    + " выбранной врезки и направления";
            continue;
          }
          LineString path = cached.get();
          if (!finder.canReuse(path, dn, access.buildingId(), from, target.point, axis)) {
            routeSearches++;
            path =
                finder.route(
                    from,
                    target.point,
                    dn,
                    access.buildingId(),
                    routeMode,
                    options.mode.equals("depth"),
                    options.gridM,
                    options.maxCells,
                    axis);
          }
          if (path == null) {
            last =
                "OCCUPIED_ROUTE_SEARCH_EXHAUSTED: не найден коридор с учётом уже"
                    + " построенных ветвей";
            continue;
          }
          if (!seen.add(path.toText())) continue;
          try {
            Network trial =
                attach(network, demand, access, target, path, spatial, dn, false).network;
            Evaluation candidate;
            try {
              candidate = evaluate(trial, demands);
            } catch (IllegalArgumentException e) {
              if (!e.getMessage().contains("габарит")
                  && !e.getMessage().contains("BUILDING_INTERSECTION")) throw e;
              candidate = repairClearances(trial, demands);
              if (candidate == null) throw e;
            }
            targetScore = Math.min(targetScore, candidate.score);
            if (best == null
                || candidate.score < best.score - 1e-9
                || Math.abs(candidate.score - best.score) < 1e-9
                    && ((int) candidate.checks.get("bendCount") < (int) best.checks.get("bendCount")
                        || candidate.checks.get("bendCount").equals(best.checks.get("bendCount"))
                            && (target.ordinal < bestTarget
                                || target.ordinal == bestTarget && originalMode < bestMode))) {
              best = candidate;
              bestTarget = target.ordinal;
              bestMode = originalMode;
            }
          } catch (IllegalArgumentException | org.locationtech.jts.geom.TopologyException e) {
            last = e.getMessage();
          }
        }
      if (preferenceState != null && Double.isFinite(targetScore) && !stopped()) {
        try {
          double[] local =
              CandidateFeatures.extract(
                  store,
                  network,
                  access,
                  target.point,
                  targetAxis(target, network),
                  dn,
                  demand.flow(),
                  target.existingId != null,
                  target.edgeId != null,
                  preferenceCurrentScore);
          double[] context =
              ReinforcementFeatures.context(
                  local, store, network, demands, demand, target.point, preferenceCurrentScore);
          observeTreePreference(
              preferenceState, new RlAction(demand, target, context).id, context, targetScore);
        } catch (IllegalArgumentException | TopologyException ignored) {
          // Missing learning features never invalidate an otherwise valid engineering
          // solution.
        }
      }
      if (candidateObserver != null && !stopped() && target.features != null) {
        Map<String, Object> record = new LinkedHashMap<>();
        record.put("group", group);
        record.put("attempt", attempt);
        record.put("candidate", target.ordinal);
        record.put("features", target.features);
        record.put("lowerBound", target.lowerBound);
        record.put("feasible", Double.isFinite(targetScore));
        record.put("score", Double.isFinite(targetScore) ? targetScore : null);
        candidateObserver.accept(record);
      }
    }
    if (best != null) return best.network;
    if (access.gate != null)
      last =
          "NEAREST_WALL_ROUTE_NOT_FOUND: ввод "
              + access.entry.id
              + ", здание "
              + access.buildingId()
              + ": допустимый маршрут через закреплённую ближайшую стену не найден."
              + " Другие стены запрещены. Последняя проверка: "
              + last;
    network.reasons.put(demand.id, last);
    return network;
  }

  private static final class Attachment {
    final Network network;
    final String edgeId;

    Attachment(Network network, String edgeId) {
      this.network = network;
      this.edgeId = edgeId;
    }
  }

  private Attachment attach(
      Network network,
      Feature demand,
      BuildingAccess access,
      Target target,
      LineString path,
      SpatialRules spatial,
      int dn,
      boolean boundOnly) {
    Network trial = network.copy();
    String parent;
    if (target.nodeId != null) parent = target.nodeId;
    else if (target.edgeId != null) parent = trial.split(target.edgeId, target.point);
    else {
      Network.Node existingRoot =
          trial.roots().stream()
              .filter(
                  r ->
                      r.existingId.equals(target.existingId)
                          && r.point.distance(target.point) < .01)
              .findFirst()
              .orElse(null);
      if (existingRoot != null) parent = existingRoot.id;
      else {
        Network.Node root = new Network.Node(trial.next("tie"), target.point, "tie");
        root.existingId = target.existingId;
        trial.nodes.put(root.id, root);
        parent = root.id;
      }
    }
    Coordinate from = path.getCoordinateN(0);
    Network.Node end = new Network.Node(trial.next("oks"), from, "oks");
    end.oksId = InputData.buildingId(demand);
    end.buildingId = access.buildingId();
    end.entryId = access.entry.id;
    BuildingAccess.Gate permission = spatial.entryGate(access.buildingId(), from, dn);
    end.entryWall = permission == null ? null : permission.wallFor(path);
    end.demand = demand.flow();
    trial.nodes.put(end.id, end);
    Network.Edge edge =
        new Network.Edge(trial.next("edge"), parent, end.id, (LineString) path.reverse());
    trial.edges.put(edge.id, edge);
    trial.connected.add(demand.id);
    trial.reasons.remove(demand.id);
    return new Attachment(trial, edge.id);
  }

  private Evaluation repairClearances(Network network, List<Feature> demands) {
    for (Network.Edge edge : new ArrayList<>(network.edges.values())) {
      if (stopped()) return null;
      Network.Node end = network.nodes.get(edge.end), start = network.nodes.get(edge.start);
      String leaf = end.buildingId == null ? end.oksId : end.buildingId;
      SpatialRules spatial =
          routingRules(store.near(SpatialRules.expand(edge.geometry.getEnvelopeInternal(), 1200)));
      if (spatial
          .assess(
              edge.geometry,
              edge.dn,
              leaf,
              start.type.equals("tie") ? start.point : null,
              end.type.equals("oks") ? end.point : null)
          .valid()) continue;
      List<LineString> others = new ArrayList<>();
      for (Network.Edge other : network.edges.values())
        if (!other.id.equals(edge.id)) others.add(other.geometry);
      Double axis = axisAtStart(edge.geometry);
      LineString route =
          new RouteFinder(spatial, this::stopped, others)
              .route(
                  end.point,
                  start.point,
                  edge.dn,
                  leaf,
                  0,
                  options.mode.equals("depth"),
                  options.gridM,
                  options.maxCells,
                  axis);
      if (route == null) return null;
      if (end.type.equals("oks")) {
        BuildingAccess.Gate permission = spatial.entryGate(leaf, end.point, edge.dn);
        end.entryWall = permission == null ? null : permission.wallFor(route);
      }
      network.edges.put(
          edge.id, new Network.Edge(edge.id, edge.start, edge.end, (LineString) route.reverse()));
    }
    return evaluate(network, demands);
  }

  private Evaluation smoothNetwork(Evaluation evaluation, List<Feature> demands) {
    Evaluation best = evaluation;
    for (Network.Edge edge : new ArrayList<>(evaluation.network.edges.values())) {
      if (stopped()) return best;
      if (edge.geometry.getNumPoints() < 3) continue;
      Network network = best.network.copy();
      Network.Edge current = network.edges.get(edge.id);
      Network.Node end = network.nodes.get(current.end), start = network.nodes.get(current.start);
      List<LineString> other = new ArrayList<>();
      for (Network.Edge e : network.edges.values())
        if (!e.id.equals(edge.id)) other.add(e.geometry);
      SpatialRules spatial =
          routingRules(store.near(SpatialRules.expand(current.geometry.getEnvelopeInternal(), 20)));
      LineString route =
          new RouteFinder(spatial, this::stopped, other)
              .simplify(
                  (LineString) current.geometry.reverse(),
                  edge.dn,
                  end.buildingId == null ? end.oksId : end.buildingId,
                  end.point,
                  start.point,
                  axisAtStart(current.geometry));
      network.edges.put(
          current.id,
          new Network.Edge(current.id, current.start, current.end, (LineString) route.reverse()));
      try {
        Evaluation candidate = evaluate(network, demands);
        if (candidate.score <= best.score + 1e-9
            && (int) candidate.checks.get("bendCount") <= (int) best.checks.get("bendCount"))
          best = candidate;
      } catch (IllegalArgumentException | org.locationtech.jts.geom.TopologyException ignored) {
      }
    }
    return best;
  }

  private static Double axisAtStart(LineString line) {
    Coordinate a = line.getCoordinateN(0), b = line.getCoordinateN(1);
    return Math.atan2(b.y - a.y, b.x - a.x);
  }

  private static Network without(Network original, Feature demand) {
    Network network = original.copy();
    Network.Node leaf =
        network.nodes.values().stream()
            .filter(n -> demand.text("_tt_entry_id").equals(n.entryId))
            .findFirst()
            .orElse(null);
    if (leaf == null) return network;
    String node = leaf.id;
    while (node != null) {
      final String current = node;
      Network.Edge incoming =
          network.edges.values().stream()
              .filter(e -> e.end.equals(current))
              .findFirst()
              .orElse(null);
      if (!network.children(node).isEmpty()) break;
      network.nodes.remove(node);
      if (incoming == null) break;
      network.edges.remove(incoming.id);
      node = incoming.start;
    }
    network.connected.remove(demand.id);
    network.reasons.remove(demand.id);
    boolean merged;
    do {
      merged = false;
      for (Network.Node chamber : new ArrayList<>(network.nodes.values())) {
        if (!chamber.type.equals("chamber")
            || network.incident(chamber.id).size() != 2
            || network.children(chamber.id).size() != 1) continue;
        Network.Edge before =
            network.edges.values().stream()
                .filter(e -> e.end.equals(chamber.id))
                .findFirst()
                .orElse(null);
        if (before == null) continue;
        Network.Edge after = network.children(chamber.id).get(0);
        List<Coordinate> coordinates =
            new ArrayList<>(Arrays.asList(before.geometry.getCoordinates()));
        Coordinate[] tail = after.geometry.getCoordinates();
        for (int i = 1; i < tail.length; i++) coordinates.add(tail[i]);
        network.edges.remove(before.id);
        network.edges.remove(after.id);
        network.nodes.remove(chamber.id);
        network.edges.put(
            before.id,
            new Network.Edge(
                before.id,
                before.start,
                after.end,
                Geo.line(coordinates.toArray(new Coordinate[0]))));
        merged = true;
        break;
      }
    } while (merged);
    return network;
  }

  private List<Target> targets(
      Network network,
      Coordinate from,
      int dn,
      boolean join,
      BuildingAccess access,
      SpatialRules spatial) {
    double radius = Rules.LENGTH[Math.min(Rules.DN.length - 1, Rules.index(dn) + 1)];
    Envelope area = new Envelope(from);
    area.expandBy(radius);
    List<Target> raw = new ArrayList<>();
    List<Feature> nearby = store.near(area);
    List<Feature> chambers = new ArrayList<>();
    for (Feature f : nearby) if (f.type.equals("heat_chamber")) chambers.add(f);
    for (Feature f : nearby) {
      if (f.type.equals("heat_chamber")) {
        if (available(f, network) > 0) {
          Target t = new Target(f.geometry.getCoordinate());
          t.existingId = f.id;
          raw.add(t);
        }
        continue;
      }
      if (!f.type.equals("heat_network")) continue;
      LineString line = (LineString) f.geometry;
      double x = Geo.index(line, from), length = line.getLength();
      TreeSet<Double> positions =
          new TreeSet<>(
              List.of(x, 0d, length, Math.max(0, x - 15), Math.min(length, x + 15), length / 2));
      if (!targetGroup.isEmpty()) positions.add(Geo.index(line, groupCenter(targetGroup)));
      for (double at : positions) {
        Coordinate p = Geo.at(line, at);
        if (p.distance(from) > radius) continue;
        List<Feature> eligible = new ArrayList<>();
        for (Feature c : chambers)
          if (c.geometry.distance(Geo.point(p)) <= 10.000001 && available(c, network) > 0)
            eligible.add(c);
        if (!eligible.isEmpty()) {
          for (Feature camera : eligible) {
            Target t = new Target(camera.geometry.getCoordinate());
            t.existingId = camera.id;
            raw.add(t);
          }
        } else if (chambers.stream().noneMatch(c -> c.geometry.distance(Geo.point(p)) < .26)) {
          Target t = new Target(p);
          t.existingId = f.id;
          raw.add(t);
        }
      }
    }
    List<Target> joint = new ArrayList<>();
    if (join)
      for (Network.Edge e : network.edges.values()) {
        double closest = Geo.index(e.geometry, from);
        TreeSet<Double> branchPositions = new TreeSet<>(List.of(closest));
        if (options.routingStrategy.equals("tree")) {
          double shift = Math.max(5, Math.min(20, options.gridM));
          branchPositions.add(Math.max(0, closest - shift));
          branchPositions.add(Math.min(e.geometry.getLength(), closest + shift));
          branchPositions.add(0d);
          branchPositions.add(e.geometry.getLength());
          if (!targetGroup.isEmpty())
            branchPositions.add(Geo.index(e.geometry, groupCenter(targetGroup)));
        }
        for (double at : branchPositions) {
          Target t = new Target(Geo.at(e.geometry, at));
          // A terminal building entry must not turn into a chamber or a transit trunk.
          if (store.near(new Envelope(t.point)).stream()
              .anyMatch(
                  f -> BuildingAccess.isBuilding(f) && f.geometry.contains(Geo.point(t.point))))
            continue;
          if (at < .05) t.nodeId = e.start;
          else if (at > e.geometry.getLength() - .05) t.nodeId = e.end;
          else t.edgeId = e.id;
          if (t.nodeId != null) {
            Network.Node n = network.nodes.get(t.nodeId);
            if (n.type.equals("oks") || network.incident(n.id).size() >= 4) continue;
            if (n.existingId != null && available(store.get(n.existingId), network) <= 0) continue;
          }
          if (joinedRoot != null
              && !joinedRoot.equals(rootKey(network, t.nodeId != null ? t.nodeId : e.start)))
            continue;
          joint.add(t);
        }
      }
    Comparator<Target> comparator =
        Comparator.comparingDouble(
                (Target t) ->
                    targetGroup.isEmpty()
                        ? t.point.distance(from)
                        : groupDistance(targetGroup, t.point))
            .thenComparing(t -> Geo.key(t.point));
    if (requireSingleRoot() && singleRoot == null) {
      raw.clear();
      if (network.roots().isEmpty() && treeRoots != null)
        for (TreeRoots.Candidate root : treeRoots.selected) {
          Target t = new Target(root.point);
          t.existingId = root.existingId;
          raw.add(t);
        }
    }
    if (singleRoot != null) {
      raw.clear();
      if (joinedRoot == null) {
        Target t = new Target(singleRoot.point);
        t.existingId = singleRoot.existingId;
        raw.add(t);
      }
    } else if (joinedRoot != null) raw.clear();
    raw.sort(comparator);
    joint.sort(comparator);
    raw.removeIf(t -> !spatial.targetClear(t.point, dn, access.buildingId()));
    joint.removeIf(t -> !spatial.targetClear(t.point, dn, access.buildingId()));
    BuildingAccess.Gate entrance = BuildingAccess.gate(access.building, from);
    if (entrance != null) {
      entrance.forDiameter(dn);
      // With standard 45/90-degree bends every segment has one of the eight
      // receiving-axis bearings. Do not spend the candidate quota on targets
      // whose bearings cannot cross the fixed entrance window at all.
      raw.removeIf(t -> !compatibleEntry(entrance, from, targetAxis(t, network)));
      joint.removeIf(t -> !compatibleEntry(entrance, from, targetAxis(t, network)));
    }
    LinkedHashMap<String, Target> unique = new LinkedHashMap<>();
    int limit =
        targetGroup.isEmpty() ? options.candidateLimit : Math.min(3, options.candidateLimit);
    for (Target t : joint) {
      if (t.point.distance(from) > .1 && t.point.distance(from) <= radius)
        unique.putIfAbsent("j:" + Geo.key(t.point), t);
      if (unique.size() >= Math.max(2, limit)) break;
    }
    int n = 0;
    for (Target t : raw) {
      if (t.point.distance(from) > .1
          && t.point.distance(from) <= radius
          && unique.putIfAbsent("e:" + t.existingId + ":" + Geo.key(t.point), t) == null) n++;
      if (n >= (requireSingleRoot() && network.roots().isEmpty() ? options.treeBeamWidth : limit))
        break;
    }
    return new ArrayList<>(unique.values());
  }

  private Coordinate groupCenter(List<Feature> group) {
    double x = 0, y = 0;
    for (Feature d : group) {
      Coordinate p = InputData.portal(store.get(d.text("_tt_entry_id")));
      x += p.x;
      y += p.y;
    }
    return new Coordinate(x / group.size(), y / group.size());
  }

  private double groupDistance(List<Feature> group, Coordinate p) {
    double sum = 0;
    for (Feature d : group) {
      Coordinate q = InputData.portal(store.get(d.text("_tt_entry_id")));
      sum += treeCorridors == null ? q.distance(p) : treeCorridors.distance(q, p);
    }
    return sum / group.size();
  }

  private static String rootKey(Network n, String node) {
    Set<String> visited = new HashSet<>();
    while (node != null && visited.add(node)) {
      Network.Node v = n.nodes.get(node);
      if (v == null) return null;
      if (v.type.equals("tie"))
        return v.existingId
            + ":"
            + Double.toHexString(v.point.x)
            + ":"
            + Double.toHexString(v.point.y);
      String parent = null;
      for (Network.Edge e : n.edges.values())
        if (e.end.equals(node)) {
          parent = e.start;
          break;
        }
      node = parent;
    }
    return null;
  }

  private static String demandRoot(Network n, Feature d) {
    for (Network.Node v : n.nodes.values())
      if (d.text("_tt_entry_id").equals(v.entryId)) return rootKey(n, v.id);
    return null;
  }

  private static boolean compatibleEntry(BuildingAccess.Gate gate, Coordinate entry, Double axis) {
    return !gate.walls.isEmpty();
  }

  private Double targetAxis(Target target, Network network) {
    LineString line = null;
    if (target.edgeId != null) line = network.edges.get(target.edgeId).geometry;
    else if (target.nodeId != null) {
      for (Network.Edge e : network.edges.values())
        if (e.end.equals(target.nodeId)) {
          line = e.geometry;
          break;
        }
    } else if (target.existingId != null) {
      Feature f = store.get(target.existingId);
      Set<String> seen = new HashSet<>();
      while (f != null && f.type.equals("heat_chamber") && seen.add(f.id))
        f = store.get(f.text("upstream_object_id"));
      if (f != null && f.type.equals("heat_network")) line = (LineString) f.geometry;
    }
    if (line != null && (target.edgeId != null || target.nodeId != null))
      return RoutingQuality.upstreamBearing(line, target.point);
    if (line == null && target.nodeId != null) {
      String existingId = network.nodes.get(target.nodeId).existingId;
      Feature f = existingId == null ? null : store.get(existingId);
      Set<String> seen = new HashSet<>();
      while (f != null && f.type.equals("heat_chamber") && seen.add(f.id))
        f = store.get(f.text("upstream_object_id"));
      if (f != null && f.type.equals("heat_network")) line = (LineString) f.geometry;
    }
    if (line == null) return null;
    double x = Geo.index(line, target.point);
    Coordinate a = Geo.at(line, Math.max(0, x - .1)),
        b = Geo.at(line, Math.min(line.getLength(), x + .1));
    return a.distance(b) < 1e-8 ? null : Math.atan2(b.y - a.y, b.x - a.x);
  }

  private int available(Feature camera, Network network) {
    int existing =
        camera.type.equals("heat_chamber") ? InputValidator.existingDegree(camera, store) : 2;
    for (Network.Node n : network.roots())
      if (n.existingId.equals(camera.id) && n.point.distance(camera.geometry.getCoordinate()) < .26)
        existing += network.children(n.id).size();
    return 4 - existing;
  }

  private static boolean same(Network a, Network b) {
    if (!a.connected.equals(b.connected)
        || a.roots().size() != b.roots().size()
        || a.edges.size() != b.edges.size()) return false;
    List<Geometry> aa = new ArrayList<>(), bb = new ArrayList<>();
    for (Network.Edge e : a.edges.values()) aa.add(e.geometry);
    for (Network.Edge e : b.edges.values()) bb.add(e.geometry);
    Geometry ga = Geo.GF.buildGeometry(aa), gb = Geo.GF.buildGeometry(bb);
    if (ga.isEmpty() && gb.isEmpty()) return true;
    for (Network.Node root : a.roots())
      if (b.roots().stream()
          .noneMatch(r -> r.existingId.equals(root.existingId) && r.point.distance(root.point) < 2))
        return false;
    return ga.difference(gb.buffer(2)).isEmpty() && gb.difference(ga.buffer(2)).isEmpty();
  }
}
