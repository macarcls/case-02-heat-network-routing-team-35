package ru.teplotrassa.engine;

import java.util.*;

/**
 * Observational labels; unvisited actions, partial trees and failed searches are never negatives.
 */
public final class TreeTrainingData {
  private final Map<String, Map<String, Outcome>> groups = new LinkedHashMap<>();
  public int fullPaths;

  public static final class Trace {
    final Trace parent;
    final String state, action;
    final double[] features;

    public Trace(Trace parent, String state, String action, double[] features) {
      this.parent = parent;
      this.state = state;
      this.action = action;
      this.features = features.clone();
    }
  }

  private static final class Outcome {
    final double[] features;
    double score;

    Outcome(double[] features, double score) {
      this.features = features.clone();
      this.score = score;
    }
  }

  public void complete(Trace trace, double finalScore) {
    if (trace == null || !Double.isFinite(finalScore)) return;
    fullPaths++;
    for (Trace item = trace; item != null; item = item.parent)
      observe("beam:" + item.state, item.action, item.features, finalScore);
  }

  public void observe(String state, String action, double[] features, double fullScore) {
    if (!Double.isFinite(fullScore) || fullScore < 0 || features == null) return;
    Map<String, Outcome> actions = groups.computeIfAbsent(state, k -> new LinkedHashMap<>());
    Outcome previous = actions.get(action);
    if (previous == null) actions.put(action, new Outcome(features, fullScore));
    else previous.score = Math.min(previous.score, fullScore);
  }

  public List<TreePolicyTraining.Preference> preferences() {
    List<TreePolicyTraining.Preference> out = new ArrayList<>();
    for (var group : groups.entrySet()) {
      List<Map.Entry<String, Outcome>> actions = new ArrayList<>(group.getValue().entrySet());
      actions.sort(
          Comparator.comparingDouble((Map.Entry<String, Outcome> e) -> e.getValue().score)
              .thenComparing(Map.Entry::getKey));
      // Several good alternatives, not just an unconditional imitation of the single winner.
      int added = 0;
      for (int i = 0; i < Math.min(3, actions.size()); i++)
        for (int j = i + 1; j < actions.size() && added < 32; j++) {
          var a = actions.get(i);
          var b = actions.get(j);
          if (b.getValue().score <= a.getValue().score + 1e-8
              || Arrays.equals(a.getValue().features, b.getValue().features)) continue;
          String left = a.getKey().compareTo(b.getKey()) < 0 ? a.getKey() : b.getKey();
          String right = a.getKey().compareTo(b.getKey()) < 0 ? b.getKey() : a.getKey();
          String key = TreePolicyTraining.digest(group.getKey() + "|" + left + "|" + right);
          out.add(
              new TreePolicyTraining.Preference(
                  key,
                  a.getValue().features,
                  b.getValue().features,
                  a.getValue().score,
                  b.getValue().score));
          added++;
        }
    }
    return out;
  }
}
