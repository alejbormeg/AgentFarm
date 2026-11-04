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
          "summary",
          "acceptance",
          "unit_test",
          "integration_test",
          "setup",
          "allowed_paths",
          "criteria",
          "risks");

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

  /**
   * Evidence that the commands of a "test" criterion cannot show, in lower case and without
   * accents.
   */
  private static final List<String> OUT_OF_REACH =
      List.of(
          "identidad del binario",
          "hash del binario",
          "firma del binario",
          "binary identity",
          "usuario del proceso",
          "uid del proceso",
          "process user",
          "como root",
          "estado de la red",
          "sin acceso a red",
          "sin acceso a la red",
          "network state",
          "en produccion",
          "in production",
          "captura de pantalla",
          "screenshot",
          "a mano",
          "manualmente",
          "by hand",
          "manually",
          "revision humana",
          "human review");

  private static final Set<String> PROVES_NOTHING = Set.of("true", ":", "exit 0", "/bin/true");

  /**
   * Criteria that may never be accredited, so the owner sees them before approving. Advice only: a
   * phrase can be a false alarm, and rejecting the proposal would waste a paid research turn. What
   * is certainly impossible (a test criterion on a command marked N/A) is rejected by
   * EvidenceService.
   */
  public static ArrayNode doubtfulCriteria(JsonNode proposal) {
    var result = new ArrayNode(JsonNodeFactory.instance);
    for (var criterion : proposal.path("criteria")) {
      if (!criterion.path("method").asText().equals("test")) continue;
      String reason = null;
      String description = plain(criterion.path("description").asText());
      for (String phrase : OUT_OF_REACH)
        if (description.contains(phrase)) {
          reason = "Pide «" + phrase + "», que las pruebas de la tarea quizá no puedan demostrar.";
          break;
        }
      for (var check : criterion.path("checks")) {
        String command = proposal.path(check.asText() + "_test").asText().trim();
        if (reason == null && PROVES_NOTHING.contains(command))
          reason = "Se cierra con «" + command + "», que siempre pasa y no demuestra nada.";
      }
      if (reason == null) continue;
      var entry = result.addObject();
      entry.put("id", criterion.path("id").asText());
      entry.put("reason", reason);
    }
    return result;
  }

  private static String plain(String text) {
    return java.text.Normalizer.normalize(text, java.text.Normalizer.Form.NFD)
        .replaceAll("\\p{M}", "")
        .toLowerCase(Locale.ROOT);
  }

  public static void normalize(JsonNode proposal) {
    for (var criterion : proposal.path("criteria")) {
      if (!(criterion instanceof ObjectNode c) || !c.path("method").asText().equals("test"))
        continue;
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
