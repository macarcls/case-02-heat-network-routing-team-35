package ru.teplotrassa.engine;

import com.fasterxml.jackson.databind.*;
import java.io.*;
import java.nio.file.*;
import java.security.*;
import java.util.*;

/** REINFORCE-trained shared action MLP; Java inference needs no Python or GPU. */
public final class ReinforcementPolicy {
  private static final ReinforcementPolicy INSTANCE = load();
  public final String id, sha256, unavailableReason;
  final double[][] w1;
  final double[] b1, w2;
  final double b2;
  private final byte[] source;

  private ReinforcementPolicy(String reason) {
    w1 = null;
    b1 = null;
    w2 = null;
    b2 = 0;
    source = null;
    id = "unavailable";
    sha256 = "";
    unavailableReason = reason;
  }

  public ReinforcementPolicy(byte[] bytes) throws IOException {
    if (bytes == null || bytes.length > 2_000_000)
      throw new IOException("Missing or oversized RL model");
    source = bytes.clone();
    ObjectMapper mapper = new ObjectMapper();
    JsonNode root = mapper.readTree(bytes);
    if (!root.path("format").asText().equals("teplotrassa-reinforce-v1")
        || !root.path("activation").asText().equals("tanh")
        || !root.path("reward").asText().equals("complete-network-v1"))
      throw new IOException("Unsupported RL schema");
    List<String> names = new ArrayList<>();
    root.path("feature_names").forEach(n -> names.add(n.asText()));
    if (!names.equals(ReinforcementFeatures.NAMES))
      throw new IOException("RL feature schema mismatch");
    w1 = mapper.convertValue(root.get("w1"), double[][].class);
    b1 = mapper.convertValue(root.get("b1"), double[].class);
    w2 = mapper.convertValue(root.get("w2"), double[].class);
    if (!root.path("b2").isNumber()) throw new IOException("Missing bias");
    b2 = root.path("b2").asDouble();
    if (w1 == null
        || w1.length != names.size()
        || b1 == null
        || b1.length < 1
        || b1.length > 128
        || w2 == null
        || w2.length != b1.length
        || !Double.isFinite(b2)) throw new IOException("Malformed RL dimensions");
    for (double[] row : w1) {
      if (row == null || row.length != b1.length) throw new IOException("Malformed RL matrix");
      for (double v : row) if (!Double.isFinite(v)) throw new IOException("Nonfinite RL weight");
    }
    for (double v : b1) if (!Double.isFinite(v)) throw new IOException("Nonfinite RL bias");
    for (double v : w2) if (!Double.isFinite(v)) throw new IOException("Nonfinite RL output");
    id = root.path("model_id").asText();
    if (id.isBlank() || id.length() > 160) throw new IOException("Invalid RL model ID");
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

  private static ReinforcementPolicy load() {
    try {
      String external = System.getenv("TEPLOTRASSA_RL_MODEL");
      if (external != null && !external.isBlank()) {
        try (InputStream in = Files.newInputStream(Path.of(external))) {
          return new ReinforcementPolicy(in.readNBytes(2_000_001));
        }
      }
      try (InputStream in =
          ReinforcementPolicy.class.getResourceAsStream("/models/reinforcement-policy.json")) {
        return new ReinforcementPolicy(in == null ? null : in.readNBytes(2_000_001));
      }
    } catch (Exception e) {
      return new ReinforcementPolicy(e.getClass().getSimpleName() + ": " + e.getMessage());
    }
  }

  public static ReinforcementPolicy bundled() {
    return INSTANCE;
  }

  public boolean available() {
    return w1 != null;
  }

  public byte[] bytes() {
    return source.clone();
  }

  public double logit(double[] features) {
    if (!available() || features == null || features.length != w1.length)
      throw new IllegalArgumentException("Invalid RL features or unavailable model");
    for (double v : features)
      if (!Double.isFinite(v)) throw new IllegalArgumentException("Nonfinite RL input");
    double out = b2;
    for (int j = 0; j < b1.length; j++) {
      double sum = b1[j];
      for (int i = 0; i < features.length; i++)
        sum += Math.max(-8, Math.min(8, features[i])) * w1[i][j];
      out += Math.tanh(sum) * w2[j];
    }
    return out;
  }

  public int choose(double[][] features, double temperature, Random random) {
    if (features.length == 0 || !Double.isFinite(temperature) || temperature < 0)
      throw new IllegalArgumentException("Invalid RL choices or temperature");
    double[] values = new double[features.length];
    int best = 0;
    for (int i = 0; i < values.length; i++) {
      values[i] = logit(features[i]);
      if (values[i] > values[best]) best = i;
    }
    if (temperature == 0) return best;
    double sum = 0, maximum = values[best];
    for (int i = 0; i < values.length; i++) {
      values[i] = Math.exp((values[i] - maximum) / temperature);
      sum += values[i];
    }
    double threshold = random.nextDouble() * sum;
    for (int i = 0; i < values.length; i++) {
      threshold -= values[i];
      if (threshold < 0) return i;
    }
    return values.length - 1;
  }
}
