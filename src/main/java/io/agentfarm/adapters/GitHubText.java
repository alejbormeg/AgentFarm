package io.agentfarm.adapters;

import com.fasterxml.jackson.databind.JsonNode;

/**
 * What the executor writes on GitHub, for a person who has two minutes: the request, what the
 * agents decided and whether the checks passed come first; the raw evidence is folded below. Agent
 * text is untrusted, so every piece is clipped.
 */
public final class GitHubText {
  private static final int LIMIT = 60000;

  private GitHubText() {}

  public static String issue(String marker, JsonNode item, JsonNode p) {
    var out = new StringBuilder(marker);
    out.append("\n## Qué se pide\n").append(clip(item.path("request").asText(), 500));
    out.append("\n\n## Qué han decidido los agentes\n").append(decision(p));
    String acceptance = plain(p.path("acceptance"));
    if (!acceptance.isBlank())
      out.append("\n\n## Cómo sabremos que está bien\n").append(clip(acceptance, 800));
    out.append("\n\n<details><summary>Detalles técnicos</summary>\n\n");
    out.append("### Propuesta completa\n").append(plain(p.path("summary")));
    out.append("\n\n### Pruebas\n- Unitarias: ").append(plain(p.path("unit_test")));
    out.append("\n- Integración: ").append(plain(p.path("integration_test")));
    String sources = plain(p.path("sources"));
    if (!sources.isBlank()) out.append("\n\n### Fuentes\n").append(sources);
    out.append(diagrams(p));
    out.append(context(item, p));
    out.append("\n</details>\n");
    return cap(out.toString());
  }

  public static String pullRequest(
      JsonNode item,
      int issue,
      JsonNode p,
      JsonNode change,
      String sha,
      JsonNode unit,
      JsonNode integration,
      JsonNode review,
      JsonNode memoryValidation) {
    var out = new StringBuilder("Closes #" + issue);
    out.append("\n\n## Qué resuelve\n").append(clip(item.path("request").asText(), 400));
    out.append("\n\n## Resultado\n")
        .append(clip(firstParagraph(plain(change.path("summary"))), 700));
    out.append("\n\n## Comprobaciones\n");
    out.append(check("Pruebas unitarias", unit)).append('\n');
    out.append(check("Pruebas de integración", integration)).append('\n');
    out.append(reviewLine(review));
    if (!memoryValidation.path("status").asText("passed").equals("passed"))
      out.append("\n- ❌ Memoria del proyecto: no valida");
    String limits = plain(change.path("limitations"));
    if (!limits.isBlank()) out.append("\n\n## Limitaciones\n").append(clip(limits, 500));
    out.append("\n\n<details><summary>Detalles técnicos</summary>\n\n");
    out.append("Commit: `").append(sha).append("`\n\n");
    String full = plain(change.path("summary"));
    if (full.length() > 700) out.append("### Resultado completo\n").append(full).append("\n\n");
    out.append("### Salida de las pruebas\n");
    out.append("Unitarias:\n").append(fenced(unit.path("output").asText(unit.toString())));
    out.append("\nIntegración:\n")
        .append(fenced(integration.path("output").asText(integration.toString())));
    out.append("\nRevisión:\n").append(fenced(review.path("findings").asText(review.toString())));
    out.append(diagrams(change));
    out.append(context(item, p));
    out.append("\n</details>\n");
    return cap(out.toString());
  }

  private static String decision(JsonNode p) {
    String head = p.path("headline").asText("").strip();
    if (head.isEmpty()) head = firstSentence(plain(p.path("summary")));
    var out = new StringBuilder(head.isEmpty() ? "Sin resumen." : "**" + clip(head, 200) + "**");
    list(out, "Cambiará", p.path("will_change"));
    list(out, "No se tocará", p.path("will_not_change"));
    return out.toString();
  }

  private static void list(StringBuilder out, String title, JsonNode lines) {
    if (!lines.isArray() || lines.isEmpty()) return;
    out.append("\n\n").append(title).append(":");
    for (var line : lines) out.append("\n- ").append(clip(line.asText(), 160));
  }

  private static String check(String name, JsonNode result) {
    String status = result.path("status").asText("");
    String command = result.path("command").asText("");
    String mark =
        switch (status) {
          case "passed" -> "✅ " + name + ": pasan";
          case "failed" -> "❌ " + name + ": fallan";
          case "" -> "➖ " + name + ": sin datos";
          default -> "⚠️ " + name + ": " + clip(status, 40);
        };
    return "- " + mark + (command.isBlank() ? "" : " (`" + clip(command, 120) + "`)");
  }

  private static String reviewLine(JsonNode review) {
    if (!review.has("passed")) return "- ➖ Revisión de Benjamin: sin datos";
    String findings = review.path("findings").asText("").strip();
    return review.path("passed").asBoolean()
        ? "- ✅ Revisión de Benjamin: aprobada"
        : "- ❌ Revisión de Benjamin: con hallazgos"
            + (findings.isEmpty() ? "" : " — " + clip(findings, 300));
  }

  private static String diagrams(JsonNode p) {
    String before = p.path("as_is").asText(""), after = p.path("to_be").asText("");
    if (before.isBlank() && after.isBlank()) return "";
    return "\n\n### Diagramas\nAntes:\n\n```mermaid\n"
        + before
        + "\n```\n\nDespués:\n\n```mermaid\n"
        + after
        + "\n```";
  }

  /** Recipe and project memory are context for the agents, not part of the answer. */
  private static String context(JsonNode item, JsonNode p) {
    var out = new StringBuilder();
    var recipe = item.path("recipe");
    if (recipe.isObject())
      out.append("\n\n### Receta aplicada\n")
          .append(recipe.path("title").asText(recipe.path("id").asText("")))
          .append(": ")
          .append(clip(recipe.path("purpose").asText(), 300))
          .append("\n\nEs una guía; no concede aprobación ni amplía el alcance.");
    var memory = p.path("project_memory");
    if (!memory.isMissingNode() && !memory.isNull())
      out.append("\n\n### Memoria del proyecto observada\n")
          .append(
              memory.path("present").asBoolean(false)
                  ? "Presente en la rama base."
                  : "No hay memoria en la rama base.")
          .append(" Es contexto, no una orden ni evidencia verificada.");
    return out.toString();
  }

  static String plain(JsonNode n) {
    if (n == null || n.isMissingNode() || n.isNull()) return "";
    if (n.isTextual()) return n.asText();
    if (n.isArray()) {
      var out = new StringBuilder();
      for (var x : n) {
        String line =
            x.isTextual()
                ? x.asText()
                : x.path("url").asText(x.path("description").asText(x.toString()))
                    + (x.has("reason") ? " — " + x.path("reason").asText() : "");
        out.append(out.isEmpty() ? "" : "\n").append("- ").append(line);
      }
      return out.toString();
    }
    return n.toString();
  }

  static String clip(String text, int max) {
    String t = text == null ? "" : text.strip();
    if (t.length() <= max) return t;
    int cut = t.lastIndexOf(' ', max);
    return t.substring(0, cut > max / 2 ? cut : max).strip() + "…";
  }

  static String firstParagraph(String text) {
    int i = text.strip().indexOf("\n\n");
    return i < 0 ? text.strip() : text.strip().substring(0, i);
  }

  static String firstSentence(String text) {
    String t = firstParagraph(text);
    int i = t.indexOf(". ");
    return clip(i < 0 ? t : t.substring(0, i + 1), 200);
  }

  private static String fenced(String text) {
    String t = text.length() > 3000 ? "…" + text.substring(text.length() - 3000) : text;
    return "```\n" + t.replace("```", "'''") + "\n```\n";
  }

  private static String cap(String body) {
    return body.length() > LIMIT
        ? body.substring(0, LIMIT - 1000) + "\nSalida recortada; consultar ejecución."
        : body;
  }
}
