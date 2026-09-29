package ru.teplotrassa.engine;

import java.util.*;
import org.locationtech.jts.geom.*;

public final class Network {
  public static class Node {
    public final String id;
    public final Coordinate point;
    public String type, existingId, oksId, entryId, buildingId;
    public double demand, depth = Double.NaN;
    public LineString entryWall;

    public Node(String id, Coordinate point, String type) {
      this.id = id;
      this.point = point.copy();
      this.type = type;
    }

    Node copy() {
      Node n = new Node(id, point, type);
      n.existingId = existingId;
      n.oksId = oksId;
      n.entryId = entryId;
      n.buildingId = buildingId;
      n.demand = demand;
      n.depth = depth;
      n.entryWall = entryWall;
      return n;
    }
  }

  public static class Edge {
    public final String id, start, end;
    public final LineString geometry;
    public double flow;
    public int dn;
    public List<DepthPlanner.Section> sections = List.of();
    public List<SpatialRules.Passage> passages = List.of();

    public Edge(String id, String start, String end, LineString geometry) {
      this.id = id;
      this.start = start;
      this.end = end;
      this.geometry = geometry;
    }

    Edge copy() {
      Edge e = new Edge(id, start, end, geometry);
      e.flow = flow;
      e.dn = dn;
      return e;
    }
  }

  public final Map<String, Node> nodes = new LinkedHashMap<>();
  public final Map<String, Edge> edges = new LinkedHashMap<>();
  public final Set<String> connected = new LinkedHashSet<>();
  public final Map<String, String> reasons = new LinkedHashMap<>();
  private int sequence;

  public String next(String prefix) {
    // Restored snapshots can already contain generated IDs although sequence starts at zero.
    String id;
    do {
      id = prefix + "_" + (++sequence);
    } while (nodes.containsKey(id) || edges.containsKey(id));
    return id;
  }

  int sequence() {
    return sequence;
  }

  public Network copy() {
    Network n = new Network();
    n.sequence = sequence;
    for (Node v : nodes.values()) n.nodes.put(v.id, v.copy());
    for (Edge e : edges.values()) n.edges.put(e.id, e.copy());
    n.connected.addAll(connected);
    n.reasons.putAll(reasons);
    return n;
  }

  public List<Edge> children(String id) {
    List<Edge> out = new ArrayList<>();
    for (Edge e : edges.values()) if (e.start.equals(id)) out.add(e);
    return out;
  }

  public List<Edge> incident(String id) {
    List<Edge> out = new ArrayList<>();
    for (Edge e : edges.values()) if (e.start.equals(id) || e.end.equals(id)) out.add(e);
    return out;
  }

  public List<Node> roots() {
    List<Node> out = new ArrayList<>();
    for (Node n : nodes.values()) if (n.type.equals("tie")) out.add(n);
    return out;
  }

  public String split(String edgeId, Coordinate p) {
    Edge edge = edges.get(edgeId);
    double x = Geo.index(edge.geometry, p), len = edge.geometry.getLength();
    if (x < .05) return edge.start;
    if (x > len - .05) return edge.end;
    edges.remove(edgeId);
    Node node = new Node(next("ch"), Geo.at(edge.geometry, x), "chamber");
    nodes.put(node.id, node);
    Edge first = new Edge(next("edge"), edge.start, node.id, Geo.part(edge.geometry, 0, x)),
        second = new Edge(next("edge"), node.id, edge.end, Geo.part(edge.geometry, x, len));
    edges.put(first.id, first);
    edges.put(second.id, second);
    return node.id;
  }

  public void flows() {
    Map<String, List<Edge>> children = new HashMap<>();
    Map<String, Integer> incoming = new HashMap<>();
    for (Edge e : edges.values()) {
      if (!nodes.containsKey(e.start) || !nodes.containsKey(e.end))
        throw new IllegalArgumentException("Участок ссылается на отсутствующий узел");
      children.computeIfAbsent(e.start, k -> new ArrayList<>()).add(e);
      if (incoming.merge(e.end, 1, Integer::sum) > 1)
        throw new IllegalArgumentException("Несколько путей к одному узлу");
    }
    List<String> order = new ArrayList<>();
    Set<String> visited = new HashSet<>();
    Deque<String> stack = new ArrayDeque<>();
    for (Node root : roots()) {
      if (incoming.containsKey(root.id))
        throw new IllegalArgumentException("Врезка не может иметь входящий новый участок");
      stack.push(root.id);
    }
    while (!stack.isEmpty()) {
      String id = stack.pop();
      if (!visited.add(id)) throw new IllegalArgumentException("Цикл новой сети");
      order.add(id);
      for (Edge e : children.getOrDefault(id, List.of())) stack.push(e.end);
    }
    if (visited.size() != nodes.size())
      throw new IllegalArgumentException("Компонент без врезки или цикл новой сети");
    Map<String, Double> sums = new HashMap<>();
    for (int i = order.size() - 1; i >= 0; i--) {
      String id = order.get(i);
      double sum = nodes.get(id).demand;
      for (Edge e : children.getOrDefault(id, List.of())) {
        e.flow = sums.get(e.end);
        e.dn = Rules.diameter(e.flow);
        sum += e.flow;
      }
      sums.put(id, sum);
    }
  }

  public double rootFlow(Node root) {
    return children(root.id).stream().mapToDouble(e -> e.flow).sum();
  }

  public void lengths() {
    lengthChecks();
  }

  /** Size constant-flow chains and check the limit on each directed path, not across siblings. */
  public List<Map<String, Object>> lengthChecks() {
    Map<String, DiameterChain> belonging = new HashMap<>();
    List<DiameterChain> chains = new ArrayList<>();
    for (Node root : roots()) groupChains(root.id, null, belonging, chains);
    for (DiameterChain chain : chains) {
      int index = Rules.index(Rules.diameter(chain.flow));
      while (index < Rules.DN.length && chain.length > Rules.LENGTH[index] + .001) index++;
      if (index == Rules.DN.length)
        throw new IllegalArgumentException("Непрерывная часть длиннее предельной DN1400");
      chain.index = index;
    }
    for (int i = chains.size() - 1; i >= 0; i--)
      if (chains.get(i).parent != null)
        chains.get(i).parent.index = Math.max(chains.get(i).parent.index, chains.get(i).index);
    // A route can retain its diameter across a junction where the flow changes.
    // Increase a whole constant-flow chain to break an overlong same-DN path.
    for (int pass = 0; pass <= chains.size() * Rules.DN.length; pass++) {
      List<Map<String, Object>> checks = new ArrayList<>();
      List<DiameterChain> offending = null;
      for (Node root : roots()) {
        offending = checkPaths(root.id, 0, -1, new ArrayList<>(), belonging, checks);
        if (offending != null) break;
      }
      if (offending == null) {
        for (Edge e : edges.values()) e.dn = Rules.DN[belonging.get(e.id).index];
        return checks;
      }
      DiameterChain choice = null;
      double cheapest = Double.POSITIVE_INFINITY;
      for (DiameterChain candidate : new LinkedHashSet<>(offending)) {
        if (candidate.index == Rules.DN.length - 1) continue;
        int target = candidate.index + 1;
        double extra = 0;
        DiameterChain upstream = candidate;
        while (upstream != null && upstream.index < target) {
          if (upstream.length > Rules.LENGTH[target] + .001) {
            extra = Double.POSITIVE_INFINITY;
            break;
          }
          extra += upstream.length * (Rules.NEW[target] - Rules.NEW[upstream.index]);
          upstream = upstream.parent;
        }
        if (extra < cheapest) {
          cheapest = extra;
          choice = candidate;
        }
      }
      if (choice == null)
        throw new IllegalArgumentException("Непрерывная часть DN1400 длиннее предельной");
      int target = choice.index + 1;
      for (DiameterChain upstream = choice; upstream != null && upstream.index < target;
          upstream = upstream.parent) upstream.index = target;
    }
    throw new IllegalArgumentException("Не удалось подобрать диаметры при ограничении длины");
  }

  private static final class DiameterChain {
    final double flow;
    final DiameterChain parent;
    final List<Edge> edges = new ArrayList<>();
    double length;
    int index;

    DiameterChain(double flow, DiameterChain parent) {
      this.flow = flow;
      this.parent = parent;
    }
  }

  private void groupChains(String nodeId, DiameterChain parent,
      Map<String, DiameterChain> belonging, List<DiameterChain> chains) {
    for (Edge edge : children(nodeId)) {
      DiameterChain chain = parent != null && Math.abs(parent.flow - edge.flow) < 1e-8
          ? parent : new DiameterChain(edge.flow, parent);
      if (chain != parent) chains.add(chain);
      chain.edges.add(edge);
      chain.length += edge.geometry.getLength();
      belonging.put(edge.id, chain);
      groupChains(edge.end, chain, belonging, chains);
    }
  }

  private List<DiameterChain> checkPaths(String nodeId, double run, int previous,
      List<DiameterChain> path, Map<String, DiameterChain> belonging,
      List<Map<String, Object>> checks) {
    for (Edge edge : children(nodeId)) {
      DiameterChain chain = belonging.get(edge.id);
      double continued = previous == chain.index ? run : 0;
      List<DiameterChain> current = previous == chain.index
          ? new ArrayList<>(path) : new ArrayList<>();
      current.add(chain);
      double length = continued + edge.geometry.getLength();
      if (length > Rules.LENGTH[chain.index] + .001) return current;
      Map<String, Object> check = new LinkedHashMap<>();
      check.put("edgeId", edge.id);
      check.put("diameter", Rules.DN[chain.index]);
      check.put("continuousPathLengthM", length);
      check.put("limitM", Rules.LENGTH[chain.index]);
      check.put("flowTph", edge.flow);
      check.put("capacityTph", Rules.CAPACITY[chain.index]);
      check.put("raisedForLength", chain.index > Rules.index(Rules.diameter(chain.flow)));
      checks.add(check);
      List<DiameterChain> invalid = checkPaths(edge.end, length, chain.index, current, belonging, checks);
      if (invalid != null) return invalid;
    }
    return null;
  }

  public void noCrossings() {
    List<Edge> all = new ArrayList<>(edges.values());
    Map<String, Geometry> toleranceBuffers = new HashMap<>();
    for (int i = 0; i < all.size(); i++) {
      Edge a = all.get(i);
      if (!a.geometry.isSimple())
        throw new IllegalArgumentException("Самопересечение новой трассы");
      for (int j = i + 1; j < all.size(); j++) {
        Edge b = all.get(j);
        Envelope envelope = new Envelope(a.geometry.getEnvelopeInternal());
        envelope.expandBy(1e-5);
        if (!envelope.intersects(b.geometry.getEnvelopeInternal())) continue;
        Geometry intersection = a.geometry.intersection(b.geometry);
        // Almost collinear segments can miss exact intersection due to UTM floating-point
        // rounding, then overlap after GeoJSON projection. Ten micrometres is numerical
        // tolerance, not pipe clearance; contacts remain legal only at a common node.
        Geometry nearby =
            a.geometry.intersection(
                toleranceBuffers.computeIfAbsent(b.id, id -> b.geometry.buffer(1e-5, 1)));
        if (intersection.isEmpty() && nearby.isEmpty()) continue;
        Set<String> shared = new HashSet<>(List.of(a.start, a.end));
        shared.retainAll(List.of(b.start, b.end));
        if (shared.isEmpty()) {
          for (String an : List.of(a.start, a.end))
            for (String bn : List.of(b.start, b.end)) {
              Node na = nodes.get(an), nb = nodes.get(bn);
              if (na != null
                  && nb != null
                  && na.type.equals("tie")
                  && nb.type.equals("tie")
                  && na.existingId != null
                  && na.existingId.equals(nb.existingId)
                  && na.point.distance(nb.point) < .001) shared.add(an);
            }
        }
        if (shared.isEmpty() || !intersection.isEmpty() && intersection.getDimension() > 0)
          throw new IllegalArgumentException("Пересечение новых участков вне общего узла");
        for (Coordinate c : intersection.getCoordinates())
          if (shared.stream().noneMatch(id -> nodes.get(id).point.distance(c) < .01))
            throw new IllegalArgumentException("Пересечение новых участков вне общего узла");
        for (Coordinate c : nearby.getCoordinates())
          if (shared.stream().noneMatch(id -> nodes.get(id).point.distance(c) < .01))
            throw new IllegalArgumentException(
                "Наложение новых участков с учётом точности координат");
      }
    }
  }
}
