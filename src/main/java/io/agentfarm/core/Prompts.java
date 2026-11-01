package io.agentfarm.core;

import static io.agentfarm.core.Json.*;

import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.regex.*;

/**
 * Versioned agent prompts shipped as resources. Evidence records the template id and its hash so a
 * result can be traced to the exact instructions the agent received.
 */
public final class Prompts {
  public static final Map<String, String> CURRENT =
      Map.of("research", "research.v2", "implement", "implement.v2", "review", "review.v2");
  private static final Pattern SLOT = Pattern.compile("\\{\\{([a-z_]+)\\}\\}");

  public record Rendered(String id, String hash, String text) {
    public Map<String, Object> reference() {
      return obj("id", id, "hash", hash);
    }
  }

  private Prompts() {}

  public static String template(String id) {
    if (!id.matches("[a-z]+\\.v[0-9]+")) throw new IllegalArgumentException("Invalid prompt id");
    try (var in = Prompts.class.getResourceAsStream("/prompts/" + id + ".md")) {
      if (in == null) throw new IllegalArgumentException("Unknown prompt " + id);
      return new String(in.readAllBytes(), StandardCharsets.UTF_8);
    } catch (java.io.IOException e) {
      throw new IllegalStateException("Cannot read prompt " + id, e);
    }
  }

  /** Fills every {{slot}}; a missing or unused value is a programming error. */
  public static Rendered render(String role, Map<String, String> values) {
    String id = CURRENT.get(role);
    if (id == null) throw new IllegalArgumentException("Unknown prompt role " + role);
    String template = template(id);
    var used = new HashSet<String>();
    var out = new StringBuilder();
    Matcher m = SLOT.matcher(template);
    while (m.find()) {
      String key = m.group(1);
      if (!values.containsKey(key)) throw new IllegalArgumentException("Missing prompt value " + key);
      used.add(key);
      m.appendReplacement(out, Matcher.quoteReplacement(values.get(key)));
    }
    m.appendTail(out);
    if (!used.containsAll(values.keySet()))
      throw new IllegalArgumentException("Unused prompt values for " + id);
    return new Rendered(id, sha256(template), out.toString());
  }
}
