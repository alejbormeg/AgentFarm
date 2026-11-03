package io.agentfarm.core;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.*;
import java.util.*;

/**
 * Normalizes harmless shape variations in an agent's proposal before the panel validates it, so a
 * paid research turn is not lost over formatting. It never invents criteria or checks: anything
 * that cannot be mapped to "unit"/"integration" is left for the panel to reject.
 */
public final class ProposalShape {
  private ProposalShape() {}

  private static final List<String> COMPARED =
      List.of(
          "summary", "acceptance", "unit_test", "integration_test", "setup", "allowed_paths",
          "criteria", "risks");

  /** What a new version changes in the fields an operator approves, for review at a glance. */
  public static ArrayNode changes(JsonNode before, JsonNode after) {
    var result = new ArrayNode(JsonNodeFactory.instance);
    if (before == null || before.isEmpty()) return result;
    for (String field : COMPARED) {
      if (before.path(field).equals(after.path(field))) continue;
      var change = result.addObject();
      change.put("field", field);
      change.set("before", before.path(field));
      change.set("after", after.path(field));
    }
    return result;
  }

  public static void normalize(JsonNode proposal) {
    for (var criterion : proposal.path("criteria")) {
      if (!(criterion instanceof ObjectNode c) || !c.path("method").asText().equals("test")) continue;
      var checks = c.get("checks");
      var names = new LinkedHashSet<String>();
      if (checks == null || checks.isNull()) continue;
      if (checks.isObject()) checks.fieldNames().forEachRemaining(names::add);
      else if (checks.isTextual()) names.add(checks.asText());
      else if (checks.isArray()) checks.forEach(x -> names.add(x.asText()));
      var mapped = new ArrayNode(JsonNodeFactory.instance);
      for (String name : names) {
        String n = name.trim().toLowerCase(Locale.ROOT);
        if (n.equals("unit") || n.equals("integration")) {
          if (!mapped.toString().contains("\"" + n + "\"")) mapped.add(n);
        } else return; // unknown entry: keep the original so the panel explains the rejection
      }
      if (!mapped.isEmpty()) c.set("checks", mapped);
    }
  }
}
