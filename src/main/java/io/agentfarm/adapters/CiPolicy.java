package io.agentfarm.adapters;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.List;
import java.util.Set;

/**
 * Which check-runs may count for a commit, and which ones are required (#241, part of #157).
 *
 * <p>A check counts only for the commit it ran on and only from the issuer that owns its family:
 * the CI template's checks from GitHub Actions, the local checks from this panel's own App, and the
 * project's own CI from anyone but AgentFarm. A check with a borrowed name is left out, so it can
 * neither pass nor fail a candidate.
 *
 * <p>In local mode the panel, not the executor, decides what is required: one local check per
 * approved test command. A lint that passes cannot stand in for them.
 */
public final class CiPolicy {
  /** Names of the checks the panel publishes for a verification it ordered. */
  public static final String LOCAL = "AgentFarm local · ";

  public static final List<String> KINDS = List.of("unit", "integration");

  /** The CI template's job names, from {@code ExecutorWork.verificationWorkflow}. */
  private static final Set<String> TEMPLATE = Set.of("AgentFarm unit", "AgentFarm integration");

  private static final String ACTIONS = "github-actions";
  private static final ObjectMapper MAPPER = new ObjectMapper();

  private CiPolicy() {}

  public static List<String> localChecks() {
    return KINDS.stream().map(k -> LOCAL + k).toList();
  }

  /** The evidence as the gates must read it: in local mode, with the panel's own requirement. */
  public static JsonNode evidence(JsonNode evidence, boolean local) {
    if (!local || !evidence.isObject()) return evidence;
    var copy = (ObjectNode) evidence.deepCopy();
    ArrayNode required = copy.putArray("ci_checks");
    localChecks().forEach(required::add);
    return copy;
  }

  /**
   * Only the check-runs that may count for {@code sha}; the total is recounted. Outside local mode
   * the panel's own checks count for nothing, so they can never stand in for the project's CI.
   */
  public static JsonNode checks(JsonNode checks, String sha, String panelApp, boolean local) {
    var kept = MAPPER.createArrayNode();
    for (var run : checks.path("check_runs"))
      if (counts(run, sha, panelApp) && (local || !ours(run, panelApp))) kept.add(run);
    var out = MAPPER.createObjectNode();
    out.put("total_count", kept.size());
    out.set("check_runs", kept);
    return out;
  }

  static boolean counts(JsonNode run, String sha, String panelApp) {
    if (!run.path("head_sha").asText().equals(sha)) return false;
    String name = run.path("name").asText();
    if (name.startsWith(LOCAL)) return ours(run, panelApp);
    if (TEMPLATE.contains(name)) return run.path("app").path("slug").asText().equals(ACTIONS);
    return !ours(run, panelApp);
  }

  /** Published by this panel's App. Without an App the panel publishes nothing. */
  public static boolean ours(JsonNode run, String panelApp) {
    return panelApp != null
        && !panelApp.isBlank()
        && run.path("app").path("id").asText().equals(panelApp);
  }
}
