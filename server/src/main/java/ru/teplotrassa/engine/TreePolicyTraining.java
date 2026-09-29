package ru.teplotrassa.engine;

import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.security.*;
import java.util.*;
import java.util.function.BooleanSupplier;

/** Local preference learning from exact full networks; separate from on-policy RL/Adam. */
public final class TreePolicyTraining {
  private static final ObjectMapper JSON = new ObjectMapper();
  public static final int MAX_PAIRS = 1024;
  private ReinforcementPolicy policy;
  private double[] first, second;
  private final LinkedHashMap<String, Preference> replay = new LinkedHashMap<>();
  public long updates, acceptedModels, proposals;
  public double rateScale = 1;

  public void recordValidation(boolean accepted) {
    if (!accepted) rateScale = Math.max(1.0 / 32, rateScale / 2);
  }

  public static final class Preference {
    public String key;
    public double[] preferred, other;
    public double preferredScore, otherScore;

    public Preference() {}

    public Preference(String key, double[] preferred, double[] other, double a, double b) {
      this.key = key;
      this.preferred = preferred.clone();
      this.other = other.clone();
      preferredScore = a;
      otherScore = b;
    }
  }

  public static final class Proposal {
    public final TreePolicyTraining candidate;
    public final double lossBefore, lossAfter;
    public final int steps;

    Proposal(TreePolicyTraining candidate, double before, double after, int steps) {
      this.candidate = candidate;
      lossBefore = before;
      lossAfter = after;
      this.steps = steps;
    }
  }

  public TreePolicyTraining(ReinforcementPolicy initial) {
    if (initial == null || !initial.available()) throw new IllegalArgumentException("Tree policy");
    policy = initial;
    first = new double[new PolicyTraining(initial).parameters().length];
    second = new double[first.length];
  }

  public ReinforcementPolicy policy() {
    return policy;
  }

  public int examples() {
    return replay.size();
  }

  /** Same-state pairs only. Repeated identical observations do not inflate the buffer. */
  public int remember(Collection<Preference> pairs) {
    int added = 0;
    for (Preference pair : pairs) {
      validate(pair);
      if (Arrays.equals(pair.preferred, pair.other)) continue;
      if (!replay.containsKey(pair.key)) added++;
      replay.put(pair.key, pair);
      while (replay.size() > MAX_PAIRS) replay.remove(replay.keySet().iterator().next());
    }
    return added;
  }

  /** Fits a copy; cancellation or failed validation cannot overwrite the active policy. */
  public Proposal propose(int steps, double rate, BooleanSupplier cancelled) {
    return propose(steps, rate, .25, cancelled);
  }

  public Proposal propose(
      int steps, double rate, double maximumMovement, BooleanSupplier cancelled) {
    if (steps < 1 || steps > 64 || !Double.isFinite(rate) || rate <= 0 || rate > .003)
      throw new IllegalArgumentException("Tree training settings");
    if (!Double.isFinite(maximumMovement) || maximumMovement <= 0 || maximumMovement > .25)
      throw new IllegalArgumentException("Tree movement bound");
    if (replay.isEmpty() || cancelled.getAsBoolean()) return null;
    TreePolicyTraining copy;
    try {
      copy = restore(snapshot());
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
    double before = loss(policy, replay.values());
    double[] anchor = new PolicyTraining(policy).parameters();
    for (int step = 0; step < steps; step++) {
      if (cancelled.getAsBoolean()) return null;
      PolicyTraining gradients = new PolicyTraining(copy.policy);
      gradients.configure(2, 1, rate);
      double[] g = new double[first.length];
      for (Preference pair : replay.values()) {
        if (cancelled.getAsBoolean()) return null;
        PolicyTraining.Sample sample = gradients.newSample();
        // grad log sigmoid(logit(preferred) - logit(other)); not an RL rollout.
        gradients.observe(sample, new double[][] {pair.preferred, pair.other}, 0);
        for (int j = 0; j < g.length; j++) g[j] += sample.score[j] / replay.size();
      }
      double[] p = gradients.parameters();
      double norm = 0;
      for (int j = 0; j < g.length; j++) {
        g[j] -= .01 * (p[j] - anchor[j]);
        norm = Math.hypot(norm, g[j]);
      }
      if (!Double.isFinite(norm)) throw new IllegalStateException("Tree gradient");
      copy.updates++;
      double movement = 0;
      for (int j = 0; j < p.length; j++) {
        double gradient = g[j] / Math.max(1, norm);
        copy.first[j] = .9 * copy.first[j] + .1 * gradient;
        copy.second[j] = .999 * copy.second[j] + .001 * gradient * gradient;
        p[j] +=
            rate
                * (copy.first[j] / (1 - Math.pow(.9, copy.updates)))
                / (Math.sqrt(copy.second[j] / (1 - Math.pow(.999, copy.updates))) + 1e-8);
        movement = Math.hypot(movement, p[j] - anchor[j]);
      }
      // Bound one proposed adaptation, even if a tiny replay buffer is very confident.
      if (movement > maximumMovement)
        for (int j = 0; j < p.length; j++)
          p[j] = anchor[j] + (p[j] - anchor[j]) * maximumMovement / movement;
      try {
        copy.policy =
            PolicyTraining.withParameters(copy.policy, p, "tree-preferences-u" + copy.updates);
      } catch (IOException e) {
        throw new UncheckedIOException(e);
      }
    }
    if (cancelled.getAsBoolean()) return null;
    proposals++;
    return new Proposal(copy, before, loss(copy.policy, replay.values()), steps);
  }

  public void accept(Proposal proposal) {
    policy = proposal.candidate.policy;
    first = proposal.candidate.first.clone();
    second = proposal.candidate.second.clone();
    updates = proposal.candidate.updates;
    acceptedModels++;
  }

  public static boolean passesNetworkCheck(Double baseline, Double candidate) {
    return candidate != null
        && Double.isFinite(candidate)
        && (baseline == null || candidate <= baseline + 1e-8);
  }

  public static double loss(ReinforcementPolicy p, Collection<Preference> examples) {
    if (examples.isEmpty()) return 0;
    double total = 0;
    for (Preference pair : examples) {
      double delta = p.logit(pair.preferred) - p.logit(pair.other);
      total += Math.max(0, -delta) + Math.log1p(Math.exp(-Math.abs(delta)));
    }
    return total / examples.size();
  }

  public ObjectNode snapshot() {
    ObjectNode out = JSON.createObjectNode();
    out.put("format", "tree-preferences-v1");
    try {
      out.set("model", JSON.readTree(policy.bytes()));
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
    out.put("updates", updates);
    out.put("acceptedModels", acceptedModels);
    out.put("proposals", proposals);
    out.put("rateScale", rateScale);
    out.set("first", JSON.valueToTree(first));
    out.set("second", JSON.valueToTree(second));
    out.set("replay", JSON.valueToTree(replay.values()));
    return out;
  }

  public static TreePolicyTraining restore(JsonNode n) throws IOException {
    if (!n.path("format").asText().equals("tree-preferences-v1"))
      throw new IOException("Tree learning format");
    TreePolicyTraining state =
        new TreePolicyTraining(new ReinforcementPolicy(JSON.writeValueAsBytes(n.path("model"))));
    state.updates = n.path("updates").asLong(-1);
    state.acceptedModels = n.path("acceptedModels").asLong(-1);
    state.proposals = n.path("proposals").asLong(-1);
    state.rateScale = n.path("rateScale").asDouble(1);
    if (!Double.isFinite(state.rateScale) || state.rateScale < 1.0 / 32 || state.rateScale > 1)
      throw new IOException("Tree adaptive rate");
    if (state.updates < 0 || state.acceptedModels < 0 || state.proposals < 0)
      throw new IOException("Tree counters");
    state.first = JSON.convertValue(n.path("first"), double[].class);
    state.second = JSON.convertValue(n.path("second"), double[].class);
    int size = new PolicyTraining(state.policy).parameters().length;
    if (!finite(state.first, size) || !finite(state.second, size))
      throw new IOException("Tree optimizer");
    for (double x : state.second) if (x < 0) throw new IOException("Tree optimizer variance");
    if (!n.path("replay").isArray() || n.path("replay").size() > MAX_PAIRS)
      throw new IOException("Tree replay size");
    try {
      for (JsonNode item : n.path("replay"))
        state.remember(List.of(JSON.treeToValue(item, Preference.class)));
    } catch (IllegalArgumentException e) {
      throw new IOException("Tree replay", e);
    }
    return state;
  }

  private static void validate(Preference pair) {
    if (pair == null
        || pair.key == null
        || !pair.key.matches("[a-f0-9]{64}")
        || !finite(pair.preferred, ReinforcementFeatures.NAMES.size())
        || !finite(pair.other, ReinforcementFeatures.NAMES.size())
        || !Double.isFinite(pair.preferredScore)
        || !Double.isFinite(pair.otherScore)
        || pair.preferredScore < 0
        || pair.otherScore <= pair.preferredScore + 1e-8)
      throw new IllegalArgumentException("Invalid full-network preference");
  }

  private static boolean finite(double[] v, int size) {
    return v != null && v.length == size && Arrays.stream(v).allMatch(Double::isFinite);
  }

  public static String digest(String text) {
    try {
      StringBuilder out = new StringBuilder();
      for (byte b :
          MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8)))
        out.append(String.format(Locale.ROOT, "%02x", b & 255));
      return out.toString();
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException(e);
    }
  }
}
