package io.agentfarm.core;

import static io.agentfarm.core.Json.*;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.*;

/**
 * The rules every candidate is held to, judged from the approved proposal, the executor's report
 * and what the panel itself read from GitHub. Unlike the wall in the novel, nobody repaints these:
 * they live in code, and an agent's change to the project's rules breaks the seventh.
 */
public final class Commandments {
  public static final List<String> TEXT =
      List.of(
          "Todo cambio de código pasa pruebas unitarias y de integración en un entorno limpio.",
          "Todo bug corregido trae una prueba que antes fallaba.",
          "Ningún agente revisa su propio trabajo.",
          "Ningún agente toca más de lo aprobado.",
          "Ninguna métrica de calidad empeora sin una excepción firmada.",
          "Ningún secreto ni vulnerabilidad conocida llega a la rama principal.",
          "Ningún agente cambia estos mandamientos.");
  /** The owner may sign an exception only where the rule itself allows for one. */
  public static final Set<String> EXCEPTABLE = Set.of("CM-2", "CM-5");

  public static final String PROFILE = "agentfarm.yaml";
  public static final String PLATFORM_WORKFLOW = ".github/workflows/agentfarm.yml";

  private Commandments() {}

  public static List<Map<String, Object>> list() {
    var out = new ArrayList<Map<String, Object>>();
    for (int i = 0; i < TEXT.size(); i++) out.add(obj("id", "CM-" + (i + 1), "text", TEXT.get(i)));
    return out;
  }

  /** Only the project's rules count here; the platform's own workflow is written by AgentFarm. */
  public static List<String> protectedChanges(
      Collection<String> changed, Collection<String> added, String recipe) {
    var out = new ArrayList<String>();
    for (String f : changed) {
      boolean adoption = f.equals(PROFILE) && "adopt-repo".equals(recipe) && added.contains(f);
      if ((f.equals(PROFILE) && !adoption)
          || (f.startsWith(".github/workflows/") && !f.equals(PLATFORM_WORKFLOW))) out.add(f);
    }
    return out;
  }

  public static List<Map<String, Object>> assess(
      JsonNode proposal, JsonNode reports, String ci, String ciReason, JsonNode ciDetail, String recipe) {
    boolean read = ci != null && !ci.equals("unavailable");
    var files = new ArrayList<String>();
    ciDetail.path("files").forEach(f -> files.add(f.asText()));
    var added = new ArrayList<String>();
    ciDetail.path("added").forEach(f -> added.add(f.asText()));
    var out = new ArrayList<Map<String, Object>>();
    out.add(entry(1, tested(reports, ci, ciReason)));
    out.add(entry(2, regression(reports)));
    out.add(entry(3, reviewed(reports)));
    out.add(entry(4, scoped(proposal, files, read)));
    out.add(entry(5, quality(reports)));
    out.add(entry(6, secrets(ciDetail, proposal, read)));
    out.add(entry(7, untouched(files, added, recipe, read)));
    return out;
  }

  private record Verdict(String status, String detail) {}

  private static Map<String, Object> entry(int number, Verdict v) {
    return obj(
        "id", "CM-" + number, "text", TEXT.get(number - 1), "status", v.status(), "detail", v.detail());
  }

  private static Verdict tested(JsonNode reports, String ci, String reason) {
    if (reports.path("untested_code").asBoolean())
      return new Verdict("failed", "Cambia código con pruebas marcadas N/A.");
    if ("failed".equals(ci)) return new Verdict("failed", reason);
    if ("success".equals(ci)) return new Verdict("ok", reason);
    return new Verdict("pending", reason == null ? "Falta leer la CI de este commit." : reason);
  }

  private static Verdict regression(JsonNode reports) {
    var r = reports.path("regression");
    return switch (r.path("status").asText()) {
      case "proved" -> new Verdict("ok", r.path("reason").asText("La prueba nueva falla sin el cambio."));
      case "not_proved" -> new Verdict("failed", r.path("reason").asText());
      case "not_required" -> new Verdict("not_applicable", "Esta tarea no corrige un bug.");
      default -> new Verdict("not_evaluated", r.path("reason").asText("Sin datos del ejecutor."));
    };
  }

  private static Verdict reviewed(JsonNode reports) {
    var review = reports.path("review");
    if (review.isMissingNode()) return new Verdict("not_evaluated", "Sin revisión registrada.");
    if (!review.path("passed").asBoolean())
      return new Verdict("failed", "El revisor no aprobó: " + review.path("findings").asText());
    String implement = reports.path("providers").path("implement").asText(),
        reviewer = reports.path("providers").path("review").asText();
    return new Verdict(
        "ok",
        !implement.isEmpty() && implement.equals(reviewer)
            ? "Turno aparte de solo lectura, con el mismo motor (" + reviewer + ")."
            : "Turno aparte de solo lectura con otro motor.");
  }

  private static Verdict scoped(JsonNode proposal, List<String> files, boolean read) {
    var allowed = proposal.path("allowed_paths");
    if (!allowed.isArray() || allowed.isEmpty())
      return new Verdict("not_evaluated", "Sin agentfarm.yaml no hay rutas aprobadas.");
    if (!read || files.isEmpty()) return new Verdict("pending", "Falta leer los archivos cambiados.");
    var paths = new ArrayList<String>();
    allowed.forEach(p -> paths.add(p.asText()));
    var outside =
        files.stream()
            .filter(f -> !f.equals(PLATFORM_WORKFLOW) && !ProjectProfile.permitted(paths, f))
            .toList();
    return outside.isEmpty()
        ? new Verdict("ok", "Todos los archivos están dentro de las rutas aprobadas.")
        : new Verdict("failed", "Fuera de lo aprobado: " + String.join(", ", outside));
  }

  private static Verdict quality(JsonNode reports) {
    var q = reports.path("quality");
    if (q.path("checks").isEmpty() && q.path("metrics").isEmpty())
      return new Verdict("not_evaluated", "El proyecto no declara comprobaciones en agentfarm.yaml.");
    var failed = new ArrayList<String>();
    for (var c : q.path("checks"))
      if (!c.path("status").asText().equals("passed")) failed.add(c.path("kind").asText());
    var summary = new ArrayList<String>();
    for (var m : q.path("metrics")) {
      if (!m.path("status").asText().equals("passed")) failed.add(m.path("name").asText());
      if (m.has("base"))
        summary.add(
            m.path("name").asText()
                + " "
                + m.path("base").asDouble()
                + " → "
                + m.path("candidate").asDouble());
    }
    if (!failed.isEmpty())
      return new Verdict("failed", "Empeora o falla: " + String.join(", ", failed) + ".");
    return new Verdict(
        "ok", summary.isEmpty() ? "Comprobaciones superadas." : String.join(" · ", summary));
  }

  private static Verdict secrets(JsonNode detail, JsonNode proposal, boolean read) {
    if (!read) return new Verdict("pending", "Falta leer el diff del commit.");
    if (!detail.path("secrets").isEmpty()) {
      var found = new ArrayList<String>();
      detail.path("secrets").forEach(s -> found.add(s.asText()));
      return new Verdict("failed", "Posible secreto: " + String.join(", ", found));
    }
    if (!detail.has("files")) return new Verdict("pending", "Falta leer el diff del commit.");
    return new Verdict(
        "ok",
        proposal.path("quality").path("checks").has("audit")
            ? "Sin secretos en el diff; la auditoría de dependencias es una comprobación del proyecto."
            : "Sin secretos en el diff. Vulnerabilidades sin comprobar: declara una comprobación audit.");
  }

  private static Verdict untouched(
      List<String> files, List<String> added, String recipe, boolean read) {
    if (!read || files.isEmpty()) return new Verdict("pending", "Falta leer los archivos cambiados.");
    var changed = protectedChanges(files, added, recipe);
    return changed.isEmpty()
        ? new Verdict("ok", "No toca agentfarm.yaml ni la CI del proyecto.")
        : new Verdict("failed", "Cambia las reglas del proyecto: " + String.join(", ", changed));
  }
}
