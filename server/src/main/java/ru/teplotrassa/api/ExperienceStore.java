package ru.teplotrassa.api;

import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.*;
import java.io.*;
import java.nio.channels.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.*;
import java.util.*;
import java.util.function.*;
import ru.teplotrassa.engine.*;

/** Durable per-workspace experience. Atomic checkpoints and an OS lock also cover two JVMs. */
public final class ExperienceStore {
  private static final String PREVIOUS_RULES = "LCT-2026-09-27-v1.5-network-walls";
  private final Path root;
  private final Path previousRoot;
  private final ObjectMapper json;

  public ExperienceStore(Path storage, ObjectMapper json) throws IOException {
    this.json = json;
    Path experience = storage.resolve("experience-v1");
    root =
        experience
            .resolve(
                hash((Rules.VERSION + ":active-search-v1:" + ReinforcementPolicy.bundled().sha256)
                        .getBytes(StandardCharsets.UTF_8))
                    .substring(0, 16));
    previousRoot = experience.resolve(
        hash((PREVIOUS_RULES + ":active-search-v1:" + ReinforcementPolicy.bundled().sha256)
            .getBytes(StandardCharsets.UTF_8)).substring(0, 16));
    Files.createDirectories(root);
  }

  public Path ownerDirectory(String owner) {
    if (owner == null || !owner.matches("[a-f0-9]{64}"))
      throw new IllegalArgumentException("Workspace owner");
    return root.resolve(owner);
  }

  public Lease lock(String owner, BooleanSupplier cancelled) throws IOException {
    Path dir = ownerDirectory(owner);
    Files.createDirectories(dir);
    FileChannel channel =
        FileChannel.open(
            dir.resolve("workspace.lock"), StandardOpenOption.CREATE, StandardOpenOption.WRITE);
    try {
      while (!cancelled.getAsBoolean() && !Thread.currentThread().isInterrupted()) {
        try {
          FileLock lock = channel.tryLock();
          if (lock != null) return new Lease(channel, lock);
        } catch (OverlappingFileLockException busy) {
          /* Another worker owns this workspace. */
        }
        try {
          Thread.sleep(50);
        } catch (InterruptedException e) {
          Thread.currentThread().interrupt();
          break;
        }
      }
      throw new java.util.concurrent.CancellationException("Ожидание опыта отменено");
    } catch (Exception e) {
      channel.close();
      throw e;
    }
  }

  public static final class Lease implements AutoCloseable {
    private final FileChannel channel;
    private final FileLock lock;

    Lease(FileChannel c, FileLock l) {
      channel = c;
      lock = l;
    }

    public void close() throws IOException {
      try {
        lock.release();
      } finally {
        channel.close();
      }
    }
  }

  /** Search randomness and learning settings do not alter engineering compatibility. */
  public String key(String inputSha, Planner.Options options) {
    ObjectNode n = json.valueToTree(options);
    n.remove(
        List.of(
            "routingStrategy",
            "neuralGuidance",
            "rlEpisodes",
            "rlSeed",
            "rlTemperature",
            "rlLearning",
            "rlRemember",
            "rlBatchSize",
            "rlLearningRate",
            "variantLimit",
            "treeBeamWidth",
            "treeExpansion",
            "treeRepairPasses",
            "treeRepairCandidates",
            "treeGraphMaxNodes",
            "treeGroupRepair",
            "treeJunctionRepair",
            "treeGeometricSearch",
            "treeSingleRootTrial",
            "treeResumeFromBest",
            "treeLearning",
            "treeLearningRounds",
            "treeTrainingSteps",
            "treeTrainingRate"));
    if (!options.treeSingleRootRequired || !options.routingStrategy.equals("tree"))
      n.remove("treeSingleRootRequired");
    n.put("inputSha256", inputSha);
    return hash(n.toString().getBytes(StandardCharsets.UTF_8));
  }

  public Session open(String owner, String inputSha, UUID dataset, Planner.Options options)
      throws IOException {
    Path dir = ownerDirectory(owner).resolve("territories").resolve(key(inputSha, options));
    Files.createDirectories(dir);
    ObjectNode description = json.createObjectNode();
    description.put("key", dir.getFileName().toString());
    description.put("dataset", dataset.toString());
    description.put("inputSha256", inputSha);
    description.set("options", json.valueToTree(options));
    // A stable map-level split: every settings variant of an input belongs to the same group.
    description.put("split", split(inputSha));
    write(dir.resolve("descriptor.json"), description);
    Session session = new Session(owner, dir);
    if (session.networks.isEmpty() && options.rankingProfile.equals("contest")) {
      // Reuse only the old route geometry, never its reward or trained weights.
      // Planner.validateIncumbent checks every imported network on today's data.
      Planner.Options previous = options.copy();
      previous.rankingProfile = "appendix";
      Path prior = previousRoot.resolve(owner).resolve("territories")
          .resolve(key(inputSha, previous)).resolve("best.json");
      try {
        JsonNode saved = read(prior);
        if (saved != null)
          for (JsonNode network : saved.path("networks"))
            session.networks.add(NetworkSnapshot.read(network));
        if (!session.networks.isEmpty())
          session.notes.add("Прежние полные трассы загружены для повторной проверки; "
              + "обученные веса и старые оценки не перенесены");
      } catch (IOException | IllegalArgumentException invalid) {
        session.notes.add("Прежняя сеть повреждена и не использована: "
            + invalid.getClass().getSimpleName());
      }
    }
    return session;
  }

  public static String split(String sha) {
    return Integer.parseInt(sha.substring(0, 2), 16) % 5 == 0 ? "validation" : "train";
  }

  public ReinforcementPolicy generalPolicy(String owner) throws IOException {
    JsonNode n = read(ownerDirectory(owner).resolve("general.json"));
    return n == null
        ? ReinforcementPolicy.bundled()
        : new ReinforcementPolicy(json.writeValueAsBytes(n.path("model")));
  }

  public List<JsonNode> territories(String owner) throws IOException {
    Path dir = ownerDirectory(owner).resolve("territories");
    List<JsonNode> out = new ArrayList<>();
    if (Files.isDirectory(dir))
      try (var paths = Files.list(dir)) {
        for (Path p : paths.sorted().toArray(Path[]::new)) {
          JsonNode n = read(p.resolve("descriptor.json"));
          if (n != null) out.add(n);
        }
      }
    return out;
  }

  public Map<String, Object> status(String owner) throws IOException {
    List<JsonNode> maps = territories(owner);
    Set<String> train = new HashSet<>(), validation = new HashSet<>();
    for (JsonNode n : maps)
      (n.path("split").asText().equals("train") ? train : validation)
          .add(n.path("inputSha256").asText());
    Map<String, Object> out = new LinkedHashMap<>();
    out.put("territories", maps.size());
    out.put("trainingMaps", train.size());
    out.put("validationMaps", validation.size());
    out.put("canTrainGeneral", train.size() >= 2 && validation.size() >= 2);
    out.put("generalModelId", generalPolicy(owner).id);
    int treeTerritories = 0;
    long treeSteps = 0;
    for (JsonNode map : maps) {
      String key = map.path("key").asText();
      if (!key.matches("[a-f0-9]{64}")) continue;
      JsonNode tree =
          read(
              ownerDirectory(owner)
                  .resolve("territories")
                  .resolve(key)
                  .resolve("tree-learning.json"));
      if (tree != null) {
        treeTerritories++;
        treeSteps += tree.path("updates").asLong();
      }
    }
    out.put("treeLearningTerritories", treeTerritories);
    out.put("treeAcceptedGradientSteps", treeSteps);
    JsonNode report = read(ownerDirectory(owner).resolve("general-report.json"));
    if (report != null) out.put("lastGeneralTraining", report);
    return out;
  }

  public void generalReport(String owner, JsonNode report) throws IOException {
    write(ownerDirectory(owner).resolve("general-report.json"), report);
  }

  public void promote(String owner, ReinforcementPolicy policy, JsonNode report)
      throws IOException {
    ObjectNode n = json.createObjectNode();
    n.set("model", json.readTree(policy.bytes()));
    n.set("validation", report);
    write(ownerDirectory(owner).resolve("general.json"), n);
  }

  public final class Session {
    public final Path directory;
    public final List<String> notes = new ArrayList<>();
    public final List<Network> networks = new ArrayList<>();
    public PolicyTraining training;
    public final boolean resumed, treeResumed;
    public TreePolicyTraining treeTraining;

    Session(String owner, Path dir) throws IOException {
      directory = dir;
      JsonNode saved = read(dir.resolve("training.json"), notes);
      resumed = saved != null;
      training =
          saved == null ? new PolicyTraining(generalPolicy(owner)) : PolicyTraining.restore(saved);
      JsonNode treeSaved = read(dir.resolve("tree-learning.json"), notes);
      treeResumed = treeSaved != null;
      treeTraining =
          treeSaved == null
              ? new TreePolicyTraining(training.policy())
              : TreePolicyTraining.restore(treeSaved);
      JsonNode best = read(dir.resolve("best.json"), notes);
      if (best != null)
        for (JsonNode n : best.path("networks")) networks.add(NetworkSnapshot.read(n));
    }

    public void saveTree(TreePolicyTraining state, List<Evaluation> best) {
      save(null, best);
      try {
        write(directory.resolve("tree-learning.json"), state.snapshot());
        treeTraining = state;
      } catch (IOException e) {
        throw new UncheckedIOException("Не удалось сохранить обучение дерева", e);
      }
    }

    public void save(PolicyTraining state, List<Evaluation> best) {
      try {
        if (!best.isEmpty()) {
          ObjectNode n = json.createObjectNode();
          ArrayNode arr = n.putArray("networks");
          for (Evaluation v : best) arr.add(NetworkSnapshot.write(v.network, json));
          n.put("bestScore", best.get(0).score);
          write(directory.resolve("best.json"), n);
        }
        if (state != null) {
          write(directory.resolve("training.json"), state.snapshot());
          training = state;
        }
      } catch (IOException e) {
        throw new UncheckedIOException("Не удалось сохранить опыт", e);
      }
    }
  }

  public JsonNode read(Path path) throws IOException {
    return read(path, new ArrayList<>());
  }

  private JsonNode read(Path path, List<String> notes) throws IOException {
    if (!Files.exists(path) && !Files.exists(backup(path))) return null;
    try {
      return checked(path);
    } catch (IOException | RuntimeException first) {
      try {
        JsonNode n = checked(backup(path));
        notes.add("Восстановлена резервная копия " + path.getFileName());
        return n;
      } catch (IOException | RuntimeException second) {
        throw new IOException(
            "Повреждено сохранение " + path.getFileName() + "; обе копии недоступны", first);
      }
    }
  }

  private JsonNode checked(Path path) throws IOException {
    if (Files.size(path) > 64 * 1024 * 1024) throw new IOException("Oversized checkpoint");
    JsonNode envelope = json.readTree(path.toFile());
    String data = envelope.path("data").asText();
    if (!hash(data.getBytes(StandardCharsets.UTF_8)).equals(envelope.path("sha256").asText()))
      throw new IOException("Checkpoint checksum");
    JsonNode n = json.readTree(data);
    if (n == null || !n.isObject()) throw new IOException("Checkpoint payload");
    return n;
  }

  public void write(Path path, JsonNode value) throws IOException {
    Files.createDirectories(path.getParent());
    String data = json.writeValueAsString(value);
    byte[] bytes =
        json.writeValueAsBytes(
            Map.of("sha256", hash(data.getBytes(StandardCharsets.UTF_8)), "data", data));
    if (bytes.length > 64 * 1024 * 1024) throw new IOException("Checkpoint exceeds 64 MiB");
    if (Files.exists(path)) {
      boolean valid;
      try {
        checked(path);
        valid = true;
      } catch (IOException | RuntimeException e) {
        valid = false;
      }
      if (valid) atomic(backup(path), Files.readAllBytes(path));
    }
    atomic(path, bytes);
  }

  private static Path backup(Path p) {
    return p.resolveSibling(p.getFileName() + ".bak");
  }

  private static void atomic(Path path, byte[] bytes) throws IOException {
    Path tmp = Files.createTempFile(path.getParent(), "checkpoint-", ".tmp");
    try {
      try (FileChannel out =
          FileChannel.open(tmp, StandardOpenOption.WRITE, StandardOpenOption.TRUNCATE_EXISTING)) {
        java.nio.ByteBuffer buffer = java.nio.ByteBuffer.wrap(bytes);
        while (buffer.hasRemaining()) out.write(buffer);
        out.force(true);
      }
      Files.move(tmp, path, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
      // Linux Docker filesystems support directory fsync; checkpoint replacement must survive a
      // restart.
      try (FileChannel dir = FileChannel.open(path.getParent(), StandardOpenOption.READ)) {
        dir.force(true);
      } catch (AccessDeniedException | UnsupportedOperationException e) {
        /* Windows directory fsync. */
      }
    } finally {
      Files.deleteIfExists(tmp);
    }
  }

  public static String hash(byte[] bytes) {
    try {
      StringBuilder s = new StringBuilder();
      for (byte b : MessageDigest.getInstance("SHA-256").digest(bytes))
        s.append(String.format("%02x", b & 255));
      return s.toString();
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException(e);
    }
  }
}
