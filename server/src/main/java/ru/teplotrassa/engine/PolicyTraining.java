package ru.teplotrassa.engine;

import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.*;
import java.io.*;
import java.util.*;

/**
 * On-policy REINFORCE. A batch is generated with fixed weights; only sufficient gradients (not
 * replayed old actions) survive a restart. The greedy rollout is never training data.
 */
public final class PolicyTraining {
  private static final ObjectMapper JSON = new ObjectMapper();
  private ReinforcementPolicy policy;
  private double[] first, second;
  private final List<Sample> pending = new ArrayList<>();
  public long updates, episodes, rollouts;
  public int discardedPending;
  private int batchSize = 4;
  private double temperature = .7, rate = .003;
  public final List<Map<String, Object>> history = new ArrayList<>();

  public static class Sample {
    public double[] score, entropy;
    public double reward;
    public int decisions;

    public Sample() {}

    Sample(int n) {
      score = new double[n];
      entropy = new double[n];
    }
  }

  public PolicyTraining(ReinforcementPolicy initial) {
    if (!initial.available()) throw new IllegalArgumentException(initial.unavailableReason);
    policy = initial;
    first = new double[size()];
    second = new double[size()];
  }

  public ReinforcementPolicy policy() {
    return policy;
  }

  private int size() {
    return (policy.w1.length + 2) * policy.b1.length + 1;
  }

  public int pendingCount() {
    return pending.size();
  }

  public void configure(int batch, double temp, double learningRate) {
    if (batch < 2
        || batch > 32
        || !Double.isFinite(temp)
        || temp <= 0
        || temp > 5
        || !Double.isFinite(learningRate)
        || learningRate <= 0
        || learningRate > .01)
      throw new IllegalArgumentException("Некорректные параметры обучения RL");
    if (batch != batchSize || temp != temperature) {
      discardedPending += pending.size();
      pending.clear();
    }
    batchSize = batch;
    temperature = temp;
    rate = learningRate;
  }

  public Sample newSample() {
    return new Sample(size());
  }

  /** Adds grad log pi(a|s) and grad entropy; temperature derivative is included. */
  public void observe(Sample sample, double[][] features, int chosen) {
    int width = policy.w1.length, hidden = policy.b1.length, count = features.length;
    if (count == 0 || chosen < 0 || chosen >= count) throw new IllegalArgumentException("Action");
    double[][] activations = new double[count][hidden];
    double[] probs = new double[count];
    double max = -Double.MAX_VALUE;
    for (int a = 0; a < count; a++) {
      probs[a] = policy.logit(features[a]) / temperature;
      max = Math.max(max, probs[a]);
      for (int h = 0; h < hidden; h++) {
        double v = policy.b1[h];
        for (int i = 0; i < width; i++) v += clip(features[a][i]) * policy.w1[i][h];
        activations[a][h] = Math.tanh(v);
      }
    }
    double sum = 0, meanLog = 0;
    for (int a = 0; a < count; a++) {
      probs[a] = Math.exp(probs[a] - max);
      sum += probs[a];
    }
    for (int a = 0; a < count; a++) {
      probs[a] /= sum;
      meanLog += probs[a] * Math.log(Math.max(1e-300, probs[a]));
    }
    for (int a = 0; a < count; a++) {
      double score = ((a == chosen ? 1 : 0) - probs[a]) / temperature;
      double entropy = -probs[a] * (Math.log(Math.max(1e-300, probs[a])) - meanLog) / temperature;
      for (int h = 0; h < hidden; h++) {
        double act = activations[a][h], derivative = policy.w2[h] * (1 - act * act);
        for (int i = 0; i < width; i++) {
          int j = i * hidden + h;
          sample.score[j] += clip(features[a][i]) * derivative * score;
          sample.entropy[j] += clip(features[a][i]) * derivative * entropy;
        }
        int j = width * hidden + h;
        sample.score[j] += derivative * score;
        sample.entropy[j] += derivative * entropy;
        j += hidden;
        sample.score[j] += act * score;
        sample.entropy[j] += act * entropy;
      }
      sample.score[size() - 1] += score;
      sample.entropy[size() - 1] += entropy;
    }
    sample.decisions++;
  }

  private static double clip(double x) {
    return Math.max(-8, Math.min(8, x));
  }

  public void complete(Sample sample, double reward) {
    if (!Double.isFinite(reward) || reward < -3 || reward > 0)
      throw new IllegalArgumentException("Reward");
    validateSample(sample);
    sample.reward = reward;
    episodes++;
    pending.add(sample);
    if (pending.size() < batchSize) return;
    double mean = pending.stream().mapToDouble(s -> s.reward).average().orElseThrow();
    double variance =
        pending.stream().mapToDouble(s -> Math.pow(s.reward - mean, 2)).sum() / batchSize;
    double scale = Math.max(.025, Math.sqrt(variance));
    double[] gradient = new double[size()];
    for (Sample s : pending) {
      double advantage = (s.reward - (mean * batchSize - s.reward) / (batchSize - 1)) / scale;
      for (int j = 0; j < gradient.length; j++)
        gradient[j] += (advantage * s.score[j] + .01 * s.entropy[j]) / batchSize;
    }
    double norm = 0;
    for (double g : gradient) norm = Math.hypot(norm, g);
    if (!Double.isFinite(norm)) throw new IllegalStateException("Nonfinite gradient");
    double[] weights = parameters();
    updates++;
    for (int j = 0; j < weights.length; j++) {
      double g = gradient[j] / Math.max(1, norm);
      first[j] = .9 * first[j] + .1 * g;
      second[j] = .999 * second[j] + .001 * g * g;
      weights[j] +=
          rate
              * (first[j] / (1 - Math.pow(.9, updates)))
              / (Math.sqrt(second[j] / (1 - Math.pow(.999, updates))) + 1e-8);
    }
    try {
      policy = withParameters(policy, weights, "active-search-u" + updates + "-e" + episodes);
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
    history.add(
        Map.of(
            "update",
            updates,
            "episodes",
            episodes,
            "meanReward",
            mean,
            "gradientNorm",
            norm,
            "modelSha256",
            policy.sha256));
    if (history.size() > 100) history.remove(0);
    pending.clear();
  }

  public double[] parameters() {
    double[] out = new double[size()];
    int k = 0;
    for (double[] row : policy.w1) for (double v : row) out[k++] = v;
    for (double v : policy.b1) out[k++] = v;
    for (double v : policy.w2) out[k++] = v;
    out[k] = policy.b2;
    return out;
  }

  public static ReinforcementPolicy withParameters(
      ReinforcementPolicy template, double[] p, String id) throws IOException {
    ObjectNode n = (ObjectNode) JSON.readTree(template.bytes());
    int width = template.w1.length, hidden = template.b1.length, k = 0;
    if (p.length != (width + 2) * hidden + 1) throw new IOException("Weights size");
    double[][] w = new double[width][hidden];
    double[] b = new double[hidden], v = new double[hidden];
    for (int i = 0; i < width; i++) for (int h = 0; h < hidden; h++) w[i][h] = p[k++];
    for (int h = 0; h < hidden; h++) b[h] = p[k++];
    for (int h = 0; h < hidden; h++) v[h] = p[k++];
    n.put("model_id", id);
    n.set("w1", JSON.valueToTree(w));
    n.set("b1", JSON.valueToTree(b));
    n.set("w2", JSON.valueToTree(v));
    n.put("b2", p[k]);
    return new ReinforcementPolicy(JSON.writeValueAsBytes(n));
  }

  public ObjectNode snapshot() {
    ObjectNode n = JSON.createObjectNode();
    try {
      n.set("model", JSON.readTree(policy.bytes()));
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
    n.put("format", "active-search-v1");
    n.put("updates", updates);
    n.put("episodes", episodes);
    n.put("rollouts", rollouts);
    n.put("batchSize", batchSize);
    n.put("temperature", temperature);
    n.put("learningRate", rate);
    n.set("first", JSON.valueToTree(first));
    n.set("second", JSON.valueToTree(second));
    n.set("pending", JSON.valueToTree(pending));
    n.set("history", JSON.valueToTree(history));
    return n;
  }

  public static PolicyTraining restore(JsonNode n) throws IOException {
    if (!n.path("format").asText().equals("active-search-v1"))
      throw new IOException("Training format");
    PolicyTraining t =
        new PolicyTraining(new ReinforcementPolicy(JSON.writeValueAsBytes(n.path("model"))));
    t.configure(
        n.path("batchSize").asInt(),
        n.path("temperature").asDouble(),
        n.path("learningRate").asDouble());
    t.updates = n.path("updates").asLong(-1);
    t.episodes = n.path("episodes").asLong(-1);
    t.rollouts = n.path("rollouts").asLong(-1);
    if (t.updates < 0 || t.episodes < 0 || t.rollouts < 0)
      throw new IOException("Training counters");
    t.first = JSON.convertValue(n.path("first"), double[].class);
    t.second = JSON.convertValue(n.path("second"), double[].class);
    t.validateVector(t.first);
    t.validateVector(t.second);
    for (double v : t.second) if (v < 0) throw new IOException("Adam variance");
    for (JsonNode s : n.path("pending")) {
      Sample sample = JSON.treeToValue(s, Sample.class);
      t.validateSample(sample);
      t.pending.add(sample);
    }
    if (t.pending.size() >= t.batchSize) throw new IOException("Uncommitted training batch");
    for (JsonNode record : n.path("history"))
      t.history.add(
          JSON.convertValue(
              record, new com.fasterxml.jackson.core.type.TypeReference<Map<String, Object>>() {}));
    if (t.history.size() > 100) throw new IOException("Oversized training history");
    return t;
  }

  private void validateVector(double[] v) {
    if (v == null || v.length != size()) throw new IllegalArgumentException("Gradient size");
    for (double x : v)
      if (!Double.isFinite(x)) throw new IllegalArgumentException("Nonfinite gradient");
  }

  private void validateSample(Sample s) {
    validateVector(s.score);
    validateVector(s.entropy);
    if (s.decisions < 0 || !Double.isFinite(s.reward) || s.reward < -3 || s.reward > 0)
      throw new IllegalArgumentException("Training sample");
  }
}
