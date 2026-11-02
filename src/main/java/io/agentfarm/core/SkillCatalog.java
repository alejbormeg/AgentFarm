package io.agentfarm.core;

import static io.agentfarm.core.Json.*;

import com.fasterxml.jackson.databind.JsonNode;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.stream.Stream;

/**
 * Specialised guidance for agents, chosen deterministically by role and stack and written into the
 * prompt, so using a skill never depends on a model deciding to. Evidence records each id, version
 * and hash. A repository can only name skills: it can't bring its own, since its files are written
 * by the same agents these rules bind. Keep released versions, as with recipes.
 */
public final class SkillCatalog {
  private static final int MAX_EXTRA_BYTES = 32768;
  private static final JsonNode CATALOG = load();

  public record Skill(String id, int version, String text) {
    public Map<String, Object> reference() {
      return obj("id", id, "version", version, "hash", sha256(text));
    }
  }

  public record Selection(String text, List<Map<String, Object>> references) {}

  private SkillCatalog() {}

  private static JsonNode load() {
    try (var in = SkillCatalog.class.getResourceAsStream("/skills/catalog.json")) {
      if (in == null) throw new IllegalStateException("Skill catalog missing");
      return MAPPER.readTree(in);
    } catch (Exception e) {
      throw new IllegalStateException("Cannot load skill catalog", e);
    }
  }

  private static Skill builtin(JsonNode entry) {
    String id = entry.path("id").asText();
    int version = entry.path("version").asInt();
    try (var in = SkillCatalog.class.getResourceAsStream("/skills/" + id + ".v" + version + ".md")) {
      if (in == null) throw new IllegalStateException("Skill file missing: " + id);
      return new Skill(id, version, new String(in.readAllBytes(), StandardCharsets.UTF_8));
    } catch (java.io.IOException e) {
      throw new IllegalStateException("Cannot read skill " + id, e);
    }
  }

  public static List<Map<String, Object>> list() {
    var out = new ArrayList<Map<String, Object>>();
    for (var entry : CATALOG) {
      var row = new LinkedHashMap<>(builtin(entry).reference());
      row.put("title", entry.path("title").asText());
      row.put("roles", entry.path("roles"));
      row.put("stacks", entry.path("stacks"));
      out.add(row);
    }
    return out;
  }

  /** What the checkout is made of, from well-known files. Never runs anything from it. */
  public static Set<String> detectStacks(Path root) throws java.io.IOException {
    var stacks = new TreeSet<String>();
    if (exists(root, "pom.xml", "build.gradle", "build.gradle.kts")) stacks.add("java");
    if (exists(root, "pyproject.toml", "requirements.txt", "setup.py")) stacks.add("python");
    if (exists(root, "package.json", "tsconfig.json")) stacks.add("node");
    if (exists(root, "go.mod")) stacks.add("go");
    try (Stream<Path> files = Files.walk(root, 6)) {
      if (files
          .filter(
              p -> {
                String path = root.relativize(p).toString();
                return !path.startsWith(".git") && !path.contains("node_modules");
              })
          .map(p -> p.getFileName().toString().toLowerCase(Locale.ROOT))
          .anyMatch(
              n ->
                  n.endsWith(".html")
                      || n.endsWith(".tsx")
                      || n.endsWith(".jsx")
                      || n.endsWith(".vue")
                      || n.endsWith(".svelte")
                      || n.endsWith(".css"))) stacks.add("ui");
    }
    return stacks;
  }

  private static boolean exists(Path root, String... names) {
    return Arrays.stream(names).anyMatch(n -> Files.exists(root.resolve(n)));
  }

  /**
   * Skills for a role: every built-in matching the stack, plus those the project names. A named
   * skill is a built-in or one the operator installed in {@code operatorDir/<id>/SKILL.md}; an
   * unknown name is an error, never skipped.
   */
  public static Selection select(
      String role, Set<String> stacks, List<String> named, Path operatorDir) throws java.io.IOException {
    var chosen = new LinkedHashMap<String, Skill>();
    for (var entry : CATALOG) {
      boolean inRole = contains(entry.path("roles"), role),
          inStack =
              contains(entry.path("stacks"), "any")
                  || stacks.stream().anyMatch(s -> contains(entry.path("stacks"), s));
      if (inRole && inStack) chosen.put(entry.path("id").asText(), builtin(entry));
    }
    for (String name : named) {
      if (chosen.containsKey(name)) continue;
      JsonNode known = null;
      for (var entry : CATALOG) if (entry.path("id").asText().equals(name)) known = entry;
      if (known != null) chosen.put(name, builtin(known));
      else chosen.put(name, operatorSkill(name, operatorDir));
    }
    if (chosen.isEmpty()) return new Selection("", List.of());
    var text =
        new StringBuilder(
            "\n\nSkills de AgentFarm para este turno. Síguelas; las instrucciones anteriores"
                + " y la propuesta aprobada prevalecen si hay conflicto.\n");
    var references = new ArrayList<Map<String, Object>>();
    for (var skill : chosen.values()) {
      text.append("\n<skill id=\"").append(skill.id()).append("\">\n");
      text.append(skill.text().strip()).append("\n</skill>\n");
      references.add(skill.reference());
    }
    return new Selection(text.toString(), List.copyOf(references));
  }

  private static Skill operatorSkill(String name, Path dir) throws java.io.IOException {
    if (!name.matches("[a-z0-9][a-z0-9-]{0,39}") || dir == null)
      throw new IllegalArgumentException("Skill '" + name + "' is not available on this executor");
    Path file = dir.resolve(name).resolve("SKILL.md");
    if (!Files.isRegularFile(file))
      throw new IllegalArgumentException("Skill '" + name + "' is not available on this executor");
    if (Files.size(file) > MAX_EXTRA_BYTES)
      throw new IllegalArgumentException("Skill '" + name + "' exceeds 32 KiB");
    return new Skill(name, 0, Files.readString(file));
  }

  private static boolean contains(JsonNode list, String value) {
    for (var item : list) if (item.asText().equals(value)) return true;
    return false;
  }
}
