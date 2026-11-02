package io.agentfarm.core;

import java.util.Collection;
import java.util.Locale;
import java.util.Set;

/** What counts as tested. A test kind marked N/A only excuses changes no test could exercise. */
public final class TestPolicy {
  private static final Set<String> DOCUMENTS =
      Set.of("license", "notice", "agentfarm-memory.json", ".github/workflows/agentfarm.yml");

  private TestPolicy() {}

  public static boolean isDocumentation(String path) {
    String p = path.toLowerCase(Locale.ROOT);
    return p.startsWith("docs/")
        || p.endsWith(".md")
        || p.endsWith(".mdx")
        || p.endsWith(".rst")
        || p.endsWith(".adoc")
        || p.endsWith(".txt")
        || DOCUMENTS.contains(p);
  }

  /** True when code changed and a test kind did not run: N/A is never a pass for code. */
  public static boolean untestedCode(Collection<String> changed, String unit, String integration) {
    boolean ran = "passed".equals(unit) && "passed".equals(integration);
    return !ran
        && ("not_applicable".equals(unit) || "not_applicable".equals(integration))
        && !changed.stream().allMatch(TestPolicy::isDocumentation);
  }
}
