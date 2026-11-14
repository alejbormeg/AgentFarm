package io.agentfarm.adapters;

import java.util.Map;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.SafeConstructor;

/**
 * Name and description of a SKILL.md, from its YAML frontmatter or, without one, from its first
 * heading and paragraph. The file is hostile text: the YAML is read with safe types and small
 * limits, anything odd falls back to the plain text, and control characters never get through.
 */
public final class SkillMeta {
  public record Meta(String name, String description) {}

  private SkillMeta() {}

  static String clean(Object value, int max) {
    if (value == null) return "";
    String t =
        String.valueOf(value)
            .replaceAll("[\\p{Cntrl}&&[^\\n\\t]]", "")
            .replaceAll("\\s+", " ")
            .strip();
    return t.length() > max ? t.substring(0, max).strip() : t;
  }

  public static Meta parse(String text, String fallbackName) {
    String name = "", description = "";
    String body = text == null ? "" : text.replace("\r\n", "\n");
    if (body.startsWith("---\n")) {
      int end = body.indexOf("\n---", 4);
      if (end > 0 && end < 8192) {
        try {
          var options = new LoaderOptions();
          options.setMaxAliasesForCollections(0);
          options.setCodePointLimit(8192);
          options.setAllowDuplicateKeys(false);
          Object parsed = new Yaml(new SafeConstructor(options)).load(body.substring(4, end));
          if (parsed instanceof Map<?, ?> map) {
            Object n = map.get("name"), d = map.get("description");
            if (n instanceof String || n instanceof Number) name = clean(n, 80);
            if (d instanceof String || d instanceof Number) description = clean(d, 500);
          }
        } catch (Exception | StackOverflowError e) {
          // Broken frontmatter is not an error: the text below still describes the skill.
        }
        body = body.substring(Math.min(body.length(), end + 4));
      }
    }
    if (name.isEmpty() || description.isEmpty()) {
      String heading = "", paragraph = "";
      for (String line : body.split("\n")) {
        String t = line.strip();
        if (t.isEmpty()) {
          if (!paragraph.isEmpty()) break;
          continue;
        }
        if (t.startsWith("#")) {
          if (heading.isEmpty()) heading = t.replaceAll("^#+\\s*", "");
          continue;
        }
        paragraph += (paragraph.isEmpty() ? "" : " ") + t;
        if (paragraph.length() > 600) break;
      }
      if (name.isEmpty()) name = clean(heading.isEmpty() ? fallbackName : heading, 80);
      if (description.isEmpty()) description = clean(paragraph, 500);
    }
    return new Meta(name.isEmpty() ? clean(fallbackName, 80) : name, description);
  }
}
