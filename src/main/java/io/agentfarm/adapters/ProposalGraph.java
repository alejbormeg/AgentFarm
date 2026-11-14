package io.agentfarm.adapters;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.*;
import java.util.*;
import java.util.regex.Pattern;

/**
 * The structured diagrams and the at-a-glance summary of a proposal. All of it is written by an
 * agent and only shown to a person, so it is cleaned instead of rejected: a bad diagram must not
 * cost a paid research turn. A graph that cannot be used is dropped and the proposal keeps its
 * Mermaid text; a usable one becomes the single source of that text, so the two never disagree.
 */
public final class ProposalGraph {
  private ProposalGraph() {}

  /** What a node is. The panel draws one shape and icon per kind; anything unknown is "other". */
  static final Set<String> TYPES =
      Set.of("service", "database", "file", "config", "agent", "person", "external", "ci", "other");

  /** What the proposal does to a node, so the to-be diagram shows the difference by itself. */
  static final Set<String> CHANGES = Set.of("new", "modified", "removed", "unchanged");

  static final int MAX_NODES = 24, MAX_EDGES = 40, MAX_LABEL = 40, MAX_EDGE_LABEL = 24;
  static final int MAX_LINES = 5, MAX_LINE = 160;
  private static final Pattern ID = Pattern.compile("[A-Za-z0-9_-]{1,32}");
  private static final List<String> LISTS = List.of("will_change", "will_not_change");

  /** Cleans {@code as_is_graph}, {@code to_be_graph} and the summary fields of the proposal. */
  public static void apply(JsonNode proposal) {
    if (!(proposal instanceof ObjectNode p)) return;
    graph(p, "as_is_graph", "as_is");
    graph(p, "to_be_graph", "to_be");
    line(p, "headline");
    for (String field : LISTS) lines(p, field);
  }

  private static void graph(ObjectNode p, String field, String mermaid) {
    var clean = clean(p.get(field));
    if (clean == null) {
      p.remove(field);
      // Without a usable graph or text the panel would refuse the proposal and the turn be lost.
      if (p.path(mermaid).asText("").isBlank())
        p.put(mermaid, "flowchart LR\n  a[\"Sin diagrama\"]\n");
      return;
    }
    p.set(field, clean);
    p.put(mermaid, mermaid(clean));
  }

  /** The graph with known types, bounded size and no dangling edges, or null if nothing is left. */
  static ObjectNode clean(JsonNode raw) {
    if (raw == null || !raw.isObject()) return null;
    var nodes = new ArrayNode(JsonNodeFactory.instance);
    var ids = new HashSet<String>();
    for (var n : raw.path("nodes")) {
      if (nodes.size() >= MAX_NODES) break;
      String id = n.path("id").asText("");
      String label = text(n.path("label").asText(""), MAX_LABEL);
      if (!ID.matcher(id).matches() || label.isEmpty() || !ids.add(id)) continue;
      var node = nodes.addObject();
      node.put("id", id);
      node.put("label", label);
      String type = n.path("type").asText("other").toLowerCase(Locale.ROOT);
      node.put("type", TYPES.contains(type) ? type : "other");
      String change = n.path("change").asText("unchanged").toLowerCase(Locale.ROOT);
      node.put("change", CHANGES.contains(change) ? change : "unchanged");
    }
    if (nodes.isEmpty()) return null;
    var edges = new ArrayNode(JsonNodeFactory.instance);
    var seen = new HashSet<String>();
    for (var e : raw.path("edges")) {
      if (edges.size() >= MAX_EDGES) break;
      String from = e.path("from").asText(""), to = e.path("to").asText("");
      if (!ids.contains(from) || !ids.contains(to) || !seen.add(from + ">" + to)) continue;
      var edge = edges.addObject();
      edge.put("from", from);
      edge.put("to", to);
      String label = text(e.path("label").asText(""), MAX_EDGE_LABEL);
      if (!label.isEmpty()) edge.put("label", label);
    }
    var out = new ObjectNode(JsonNodeFactory.instance);
    out.set("nodes", nodes);
    out.set("edges", edges);
    return out;
  }

  /** Mermaid text of a cleaned graph, for the issue and PR bodies and for old clients. */
  static String mermaid(JsonNode graph) {
    var out = new StringBuilder("flowchart LR\n");
    var byChange = new LinkedHashMap<String, List<String>>();
    for (var n : graph.path("nodes")) {
      String id = n.path("id").asText(), label = n.path("label").asText();
      out.append("  ").append(id).append(shape(n.path("type").asText(), label)).append('\n');
      String change = n.path("change").asText();
      if (!change.equals("unchanged"))
        byChange.computeIfAbsent(change, k -> new ArrayList<>()).add(id);
    }
    for (var e : graph.path("edges")) {
      out.append("  ").append(e.path("from").asText());
      String label = e.path("label").asText("");
      out.append(label.isEmpty() ? " --> " : " -->|\"" + quote(label) + "\"| ");
      out.append(e.path("to").asText()).append('\n');
    }
    byChange.forEach((change, ids) ->
        out.append("  class ")
            .append(String.join(",", ids))
            .append(' ')
            .append(change)
            .append('\n'));
    out.append("  classDef new fill:#d9f2dd,stroke:#2e7d32\n");
    out.append("  classDef modified fill:#fff0cc,stroke:#b26a00\n");
    out.append("  classDef removed fill:#f7d6d6,stroke:#b3261e,stroke-dasharray:4 3\n");
    return out.toString();
  }

  private static String shape(String type, String label) {
    String q = "\"" + quote(label) + "\"";
    return switch (type) {
      case "database" -> "[(" + q + ")]";
      case "file" -> ">" + q + "]";
      case "config" -> "{{" + q + "}}";
      case "agent" -> "([" + q + "])";
      case "person" -> "((" + q + "))";
      case "external" -> "[/" + q + "/]";
      case "ci" -> "[[" + q + "]]";
      default -> "[" + q + "]";
    };
  }

  private static String quote(String s) {
    return s.replace('"', '\'');
  }

  /** One line of plain text: no markup characters, collapsed spaces, bounded length. */
  static String text(String raw, int max) {
    String s = raw.replaceAll("[\\p{Cntrl}<>`]", " ").replaceAll("\\s+", " ").trim();
    return s.length() <= max ? s : s.substring(0, max - 1).trim() + "…";
  }

  private static void line(ObjectNode p, String field) {
    var v = p.get(field);
    String s = v != null && v.isTextual() ? text(v.asText(), MAX_LINE) : "";
    if (s.isEmpty()) p.remove(field);
    else p.put(field, s);
  }

  private static void lines(ObjectNode p, String field) {
    var v = p.get(field);
    var out = new ArrayNode(JsonNodeFactory.instance);
    if (v != null && v.isArray())
      for (var x : v) {
        if (out.size() >= MAX_LINES) break;
        String s = x.isTextual() ? text(x.asText(), MAX_LINE) : "";
        if (!s.isEmpty()) out.add(s);
      }
    if (out.isEmpty()) p.remove(field);
    else p.set(field, out);
  }
}
