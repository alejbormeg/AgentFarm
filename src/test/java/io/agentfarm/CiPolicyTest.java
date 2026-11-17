package io.agentfarm;

import static io.agentfarm.core.Json.*;
import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.databind.JsonNode;
import io.agentfarm.adapters.CiPolicy;
import io.agentfarm.core.EvidenceVerifier;
import io.agentfarm.core.IntegrationPublisher;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** Which checks count, and which are required, with and without local CI (#241, #157). */
class CiPolicyTest {
  static final String SHA = "a".repeat(40), OTHER = "b".repeat(40), PANEL = "4242";

  static Map<String, Object> run(String name, String conclusion, String sha, Object app) {
    return obj(
        "name", name, "status", "completed", "conclusion", conclusion, "head_sha", sha, "app", app);
  }

  static Map<String, Object> panel() {
    return obj("id", 4242, "slug", "agentfarm-panel");
  }

  static Map<String, Object> actions() {
    return obj("id", 15368, "slug", "github-actions");
  }

  static Map<String, Object> other() {
    return obj("id", 777, "slug", "someone-else");
  }

  static JsonNode checks(Object... runs) {
    return MAPPER.valueToTree(obj("total_count", runs.length, "check_runs", List.of(runs)));
  }

  static JsonNode evidence(Object... required) {
    return MAPPER.valueToTree(obj("passed", true, "sha", SHA, "ci_checks", List.of(required)));
  }

  static String assess(JsonNode evidence, JsonNode checks, boolean local) {
    var result =
        EvidenceVerifier.assess(
            CiPolicy.evidence(evidence, local),
            CiPolicy.checks(checks, SHA, PANEL, local),
            MAPPER.valueToTree(obj("total_count", 0, "state", "pending")),
            MAPPER.valueToTree(obj("files", List.of())));
    return result.state() + ": " + result.reason();
  }

  static final Object[] LOCAL_GREEN = {
    run("AgentFarm local · unit", "success", SHA, panel()),
    run("AgentFarm local · integration", "success", SHA, panel())
  };

  @Test
  void localCiPassesOnlyWithThePanelsOwnChecksForThisCommit() {
    assertTrue(assess(evidence("*"), checks(LOCAL_GREEN), true).startsWith("success"));
  }

  @Test
  void theExecutorCannotChooseWhatLocalCiRequires() {
    var lint = checks(run("lint", "success", SHA, other()));
    String verdict = assess(evidence("lint"), lint, true);
    assertTrue(
        verdict.contains("AgentFarm local · unit"), "a passing lint unlocks nothing: " + verdict);
  }

  @Test
  void aBorrowedNameFromAnotherIssuerDoesNotCount() {
    var forged =
        checks(
            run("AgentFarm local · unit", "success", SHA, other()),
            run("AgentFarm local · integration", "success", SHA, actions()));
    assertFalse(assess(evidence("*"), forged, true).startsWith("success"));
    var template =
        checks(
            run("AgentFarm unit", "success", SHA, other()),
            run("AgentFarm integration", "success", SHA, other()));
    assertFalse(
        assess(evidence("AgentFarm unit", "AgentFarm integration"), template, false)
            .startsWith("success"),
        "the template's checks come from GitHub Actions only");
    var real =
        checks(
            run("AgentFarm unit", "success", SHA, actions()),
            run("AgentFarm integration", "success", SHA, actions()));
    assertTrue(
        assess(evidence("AgentFarm unit", "AgentFarm integration"), real, false)
            .startsWith("success"));
  }

  @Test
  void aResultForAnotherCommitDoesNotCount() {
    var stale =
        checks(
            run("AgentFarm local · unit", "success", OTHER, panel()),
            run("AgentFarm local · integration", "success", OTHER, panel()));
    assertFalse(assess(evidence("*"), stale, true).startsWith("success"));
  }

  @Test
  void thePanelsChecksNeverStandInForTheProjectsOwnCi() {
    assertFalse(
        assess(evidence("*"), checks(LOCAL_GREEN), false).startsWith("success"),
        "outside local mode the panel's checks count for nothing");
    var own = checks(run("build", "success", SHA, actions()));
    assertTrue(assess(evidence("*"), own, false).startsWith("success"));
  }

  @Test
  void integrationAppliesTheSameRules() {
    var remote = MAPPER.valueToTree(obj("head", obj("sha", SHA), "draft", false, "state", "open"));
    var statuses = MAPPER.valueToTree(obj("total_count", 0, "state", "pending"));
    var reviews = MAPPER.createArrayNode();
    IntegrationPublisher.validate(
        remote,
        CiPolicy.evidence(evidence("lint"), true),
        CiPolicy.checks(checks(LOCAL_GREEN), SHA, PANEL, true),
        statuses,
        reviews,
        SHA);
    var lintOnly = checks(run("lint", "success", SHA, other()));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            IntegrationPublisher.validate(
                remote,
                CiPolicy.evidence(evidence("lint"), true),
                CiPolicy.checks(lintOnly, SHA, PANEL, true),
                statuses,
                reviews,
                SHA));
  }

  @Test
  void withoutAnAppNothingIsThePanels() {
    assertFalse(CiPolicy.ours(MAPPER.valueToTree(obj("app", obj("id", 0))), ""));
    assertFalse(CiPolicy.ours(MAPPER.valueToTree(obj("app", panel())), ""));
    assertTrue(CiPolicy.ours(MAPPER.valueToTree(obj("app", panel())), PANEL));
  }
}
