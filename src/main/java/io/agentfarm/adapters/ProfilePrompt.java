package io.agentfarm.adapters;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * How the owner shapes the agents of one repository: a system message per role and extra skills. It
 * is text for the prompt and for the files Claude, ChatGPT/Codex and Gemini read; it never grants a
 * permission, and the approved scope and the platform's rules come first. What reaches an executor
 * is cleaned here, so a malformed profile cannot break a turn.
 */
public final class ProfilePrompt {
  public static final List<String> ROLES = List.of("research", "implement", "review");
  public static final int MAX_SYSTEM = 8000, MAX_SKILL = 32768, MAX_SKILLS = 20;
  private static final ObjectMapper MAPPER = new ObjectMapper();
  private static final Set<String> SOURCES =
      Set.of("upload", "github", "anthropic", "openai", "gemini");

  /** Names, in the owner's words, and what each role does by default. */
  public static final Map<String, String> NAMES =
      Map.of(
          "research",
          "Napoleon (investiga)",
          "implement",
          "Boxer (implementa)",
          "review",
          "Benjamin (revisa)");

  public static final Map<String, String> DEFAULTS =
      Map.of(
          "research",
          "Eres Napoleon, el investigador de este repositorio. Lee el código antes de proponer. "
              + "Propón el cambio más pequeño que cumpla la petición, di qué cambia y qué no, y "
              + "define criterios de aceptación que se puedan comprobar. Si falta información, "
              + "dilo en lugar de suponerla.",
          "implement",
          "Eres Boxer, quien implementa en este repositorio. Haz solo lo que la propuesta "
              + "aprobada describe, con cambios pequeños y revisables. Escribe o ajusta las "
              + "pruebas que demuestran el cambio. No toques nada fuera del alcance aprobado.",
          "review",
          "Eres Benjamin, quien revisa en este repositorio. No cambias nada. Comprueba que el "
              + "cambio cumple la propuesta aprobada y sus criterios, busca errores, falta de "
              + "pruebas y riesgos, y explica cada hallazgo con evidencia del código.");

  private ProfilePrompt() {}

  private static String clean(String text, int max) {
    String t = text == null ? "" : text.replace("\r", "").replace("\u0000", "").strip();
    return t.length() > max ? t.substring(0, max) : t;
  }

  public static String hash(String text) {
    try {
      return HexFormat.of()
          .formatHex(
              MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8)));
    } catch (Exception e) {
      throw new IllegalStateException(e);
    }
  }

  /** Cleans what the owner sent; invalid parts are rejected with a reason, never half-saved. */
  public static ObjectNode sanitize(JsonNode input) {
    var out = MAPPER.createObjectNode();
    var roles = out.putObject("roles");
    for (String role : ROLES) {
      String system = clean(input.path("roles").path(role).path("system").asText(""), MAX_SYSTEM);
      if (input.path("roles").path(role).path("system").asText("").length() > MAX_SYSTEM)
        throw new IllegalArgumentException(
            "El system message de " + role + " pasa de " + MAX_SYSTEM + " caracteres");
      if (!system.isEmpty() && !system.equals(DEFAULTS.get(role)))
        roles.putObject(role).put("system", system);
    }
    var skills = out.putArray("skills");
    var ids = new java.util.HashSet<String>();
    for (var s : input.path("skills")) {
      if (skills.size() >= MAX_SKILLS)
        throw new IllegalArgumentException("Máximo " + MAX_SKILLS + " skills por repositorio");
      String id = s.path("id").asText("");
      if (!id.matches("[a-z0-9][a-z0-9-]{0,39}"))
        throw new IllegalArgumentException(
            "Identificador de skill no válido: usa minúsculas, números y guiones");
      if (!ids.add(id)) throw new IllegalArgumentException("Skill repetida: " + id);
      String text = s.path("text").asText("");
      if (text.length() > MAX_SKILL)
        throw new IllegalArgumentException("La skill " + id + " pasa de 32 KiB");
      if (text.isBlank()) throw new IllegalArgumentException("La skill " + id + " está vacía");
      var skill = skills.addObject();
      skill.put("id", id);
      skill.put("title", clean(s.path("title").asText(id), 80));
      var roleList = skill.putArray("roles");
      for (String role : ROLES)
        for (var r : s.path("roles")) if (r.asText().equals(role)) roleList.add(role);
      if (roleList.isEmpty()) ROLES.forEach(roleList::add);
      skill.put("enabled", s.path("enabled").asBoolean(true));
      String source = s.path("source").asText("upload");
      skill.put("source", SOURCES.contains(source) ? source : "upload");
      skill.put("origin", clean(s.path("origin").asText(""), 300));
      skill.put("text", clean(text, MAX_SKILL));
      skill.put("hash", hash(clean(text, MAX_SKILL)));
    }
    return out;
  }

  public static String system(JsonNode profile, String role) {
    String custom = profile.path("roles").path(role).path("system").asText("").strip();
    return custom.isEmpty() ? DEFAULTS.get(role) : custom;
  }

  /** The profile with defaults filled in, as the panel edits it. */
  public static ObjectNode forEditing(JsonNode profile) {
    var out = MAPPER.createObjectNode();
    var roles = out.putObject("roles");
    for (String role : ROLES) {
      var r = roles.putObject(role);
      r.put("system", system(profile, role));
      r.put("default", DEFAULTS.get(role));
      r.put("custom", !profile.path("roles").path(role).path("system").asText("").isBlank());
      r.put("name", NAMES.get(role));
    }
    out.set(
        "skills",
        profile.path("skills").isArray() ? profile.path("skills") : MAPPER.createArrayNode());
    return out;
  }

  /** What an executor appends to a turn's prompt for {@code role}, and the skills it lists. */
  public record Section(String text, List<Map<String, Object>> references) {}

  public static Section section(JsonNode profile, String role) {
    if (profile == null || !profile.isObject() || profile.isEmpty())
      return new Section("", List.of());
    var text = new StringBuilder();
    var references = new ArrayList<Map<String, Object>>();
    String custom = profile.path("roles").path(role).path("system").asText("").strip();
    if (!custom.isEmpty()) {
      text.append(
              "\n\nInstrucciones del propietario para este repositorio. La petición, el alcance aprobado y las reglas de AgentFarm prevalecen si hay conflicto.\n<owner-instructions>\n")
          .append(clean(custom, MAX_SYSTEM))
          .append("\n</owner-instructions>\n");
      references.add(
          Map.of(
              "id", "owner-system-" + role, "version", 0, "hash", hash(custom), "source", "panel"));
    }
    for (var s : profile.path("skills")) {
      boolean inRole = false;
      for (var r : s.path("roles")) inRole |= r.asText().equals(role);
      if (!s.path("enabled").asBoolean(true) || !inRole) continue;
      String body = clean(s.path("text").asText(), MAX_SKILL), id = s.path("id").asText();
      if (!id.matches("[a-z0-9][a-z0-9-]{0,39}") || body.isEmpty()) continue;
      text.append("\n<skill id=\"")
          .append(id)
          .append("\" origin=\"owner\">\n")
          .append(body)
          .append("\n</skill>\n");
      references.add(Map.of("id", id, "version", 0, "hash", hash(body), "source", "panel"));
    }
    return new Section(text.toString(), List.copyOf(references));
  }

  /**
   * The same profile as the file each tool reads: CLAUDE.md, AGENTS.md (Codex/ChatGPT) or
   * GEMINI.md.
   */
  public static String export(JsonNode profile, String format) {
    String file =
        switch (format) {
          case "claude" -> "CLAUDE.md";
          case "codex" -> "AGENTS.md";
          case "gemini" -> "GEMINI.md";
          default -> throw new IllegalArgumentException("Formato desconocido: " + format);
        };
    var out = new StringBuilder("# " + file + " — instrucciones para los agentes\n\n");
    out.append(
        "Generado por AgentFarm. Estas instrucciones no amplían permisos: manda siempre el alcance aprobado.\n");
    for (String role : ROLES)
      out.append("\n## ")
          .append(NAMES.get(role))
          .append("\n\n")
          .append(system(profile, role))
          .append("\n");
    ArrayNode skills = profile.path("skills") instanceof ArrayNode a ? a : MAPPER.createArrayNode();
    for (var s : skills) {
      if (!s.path("enabled").asBoolean(true)) continue;
      out.append("\n## Skill: ")
          .append(s.path("title").asText(s.path("id").asText()))
          .append("\n\n");
      out.append("Se usa en: ");
      var names = new ArrayList<String>();
      for (var r : s.path("roles")) names.add(NAMES.getOrDefault(r.asText(), r.asText()));
      out.append(String.join(", ", names))
          .append("\n\n")
          .append(s.path("text").asText().strip())
          .append("\n");
    }
    return out.toString();
  }
}
