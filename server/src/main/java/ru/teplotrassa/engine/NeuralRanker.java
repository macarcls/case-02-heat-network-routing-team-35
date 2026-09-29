package ru.teplotrassa.engine;

import com.fasterxml.jackson.databind.*;
import java.io.*;
import java.security.*;
import java.util.*;

/** Small trained ReLU MLP. Its output ONLY orders candidates and never establishes feasibility. */
public final class NeuralRanker {
  private static final String RESOURCE = "/models/connection-ranker.json";
  private static final NeuralRanker INSTANCE = load();
  public final String id, sha256, unavailableReason;
  private final double[] mean, scale;
  private final double[][][] weights;
  private final double[][] biases;

  private NeuralRanker(String reason) {
    id = "unavailable";
    sha256 = "";
    unavailableReason = reason;
    mean = null;
    scale = null;
    weights = null;
    biases = null;
  }

  public NeuralRanker(byte[] bytes) throws IOException {
    if (bytes.length > 2_000_000) throw new IOException("Model is too large");
    JsonNode root = new ObjectMapper().readTree(bytes);
    if (!root.path("format").asText().equals("teplotrassa-mlp-ranker-v1")
        || !root.path("activation").asText().equals("relu")
        || !root.path("target").asText().equals("log1p_score_residual"))
      throw new IOException("Unsupported neural model format");
    List<String> names = new ArrayList<>();
    root.path("feature_names").forEach(n -> names.add(n.asText()));
    if (!names.equals(CandidateFeatures.NAMES))
      throw new IOException("Model feature schema mismatch");
    ObjectMapper mapper = new ObjectMapper();
    mean = mapper.convertValue(root.get("mean"), double[].class);
    scale = mapper.convertValue(root.get("scale"), double[].class);
    weights = mapper.convertValue(root.get("weights"), double[][][].class);
    biases = mapper.convertValue(root.get("biases"), double[][].class);
    if (mean == null
        || scale == null
        || mean.length != names.size()
        || scale.length != names.size()
        || weights == null
        || biases == null
        || weights.length < 1
        || weights.length > 4
        || weights.length != biases.length) throw new IOException("Malformed model dimensions");
    int width = names.size();
    for (int i = 0; i < width; i++)
      if (!Double.isFinite(mean[i]) || !Double.isFinite(scale[i]) || scale[i] <= 0)
        throw new IOException("Invalid normalisation");
    for (int layer = 0; layer < weights.length; layer++) {
      if (weights[layer] == null
          || weights[layer].length != width
          || biases[layer] == null
          || biases[layer].length < 1
          || biases[layer].length > 128) throw new IOException("Invalid layer");
      for (double[] row : weights[layer]) {
        if (row == null || row.length != biases[layer].length)
          throw new IOException("Invalid weight shape");
        for (double v : row)
          if (!Double.isFinite(v)) throw new IOException("Nonfinite model weight");
      }
      for (double v : biases[layer])
        if (!Double.isFinite(v)) throw new IOException("Nonfinite model bias");
      width = biases[layer].length;
    }
    if (width != 1) throw new IOException("Expected one residual prediction");
    id = root.path("model_id").asText();
    if (id.isBlank() || id.length() > 160) throw new IOException("Invalid model ID");
    try {
      StringBuilder digest = new StringBuilder();
      for (byte b : MessageDigest.getInstance("SHA-256").digest(bytes))
        digest.append(String.format("%02x", b & 255));
      sha256 = digest.toString();
    } catch (NoSuchAlgorithmException e) {
      throw new IOException(e);
    }
    unavailableReason = "";
  }

  private static NeuralRanker load() {
    try (InputStream in = NeuralRanker.class.getResourceAsStream(RESOURCE)) {
      return fromBytesOrFallback(in == null ? null : in.readNBytes(2_000_001));
    } catch (Exception e) {
      return new NeuralRanker("Model validation failed: " + e.getClass().getSimpleName());
    }
  }

  public static NeuralRanker fromBytesOrFallback(byte[] bytes) {
    try {
      return bytes == null ? new NeuralRanker("Model resource is absent") : new NeuralRanker(bytes);
    } catch (Exception e) {
      return new NeuralRanker("Model validation failed: " + e.getClass().getSimpleName());
    }
  }

  public static NeuralRanker bundled() {
    return INSTANCE;
  }

  public boolean available() {
    return weights != null;
  }

  public double predictLogResidual(double[] features) {
    if (!available() || features == null || features.length != mean.length) return Double.NaN;
    double[] values = new double[features.length];
    for (int i = 0; i < values.length; i++) {
      if (!Double.isFinite(features[i])) return Double.NaN;
      values[i] = (features[i] - mean[i]) / scale[i];
      if (!Double.isFinite(values[i])) return Double.NaN;
    }
    for (int l = 0; l < weights.length; l++) {
      double[] next = biases[l].clone();
      for (int i = 0; i < values.length; i++)
        for (int j = 0; j < next.length; j++) next[j] += values[i] * weights[l][i][j];
      if (l + 1 < weights.length)
        for (int j = 0; j < next.length; j++) next[j] = Math.max(0, next[j]);
      values = next;
    }
    return values[0];
  }

  public double priority(double lowerBound, double[] features) {
    double value = predictLogResidual(features);
    return Double.isFinite(value)
        ? lowerBound + Math.expm1(Math.max(0, Math.min(20, value)))
        : lowerBound;
  }
}
