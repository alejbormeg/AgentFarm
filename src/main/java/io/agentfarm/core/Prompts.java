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
      Map.of("research", "research.v6", "implement", "implement.v3", "review", "review.v3");

  /**
   * Slots holding text written outside these rules (issues, PR reviews, alerts, corrections). They
   * are fenced as data with a code drawn per turn, so the text cannot close its own fence.
   */
  static final Set<String> EXTERNAL = Set.of("request", "feedback");

  private static final java.security.SecureRandom RANDOM = new java.security.SecureRandom();
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
      if (!values.containsKey(key))
        throw new IllegalArgumentException("Missing prompt value " + key);
      used.add(key);
      String value = values.get(key);
      if (EXTERNAL.contains(key)) value = fenced(value);
      m.appendReplacement(out, Matcher.quoteReplacement(value));
    }
    m.appendTail(out);
    if (!used.containsAll(values.keySet()))
      throw new IllegalArgumentException("Unused prompt values for " + id);
    return new Rendered(id, sha256(template), out.toString());
  }

  /** External text between markers whose code it cannot know; blank text is said so, unfenced. */
  static String fenced(String text) {
    if (text == null || text.isBlank()) return "(nada)";
    String code;
    do code = HexFormat.of().formatHex(bytes());
    while (text.contains(code));
    return "«datos " + code + "»\n" + text + "\n«fin datos " + code + "»";
  }

  private static byte[] bytes() {
    byte[] b = new byte[8];
    RANDOM.nextBytes(b);
    return b;
  }
}
