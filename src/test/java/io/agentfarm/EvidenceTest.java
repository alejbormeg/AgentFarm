package io.agentfarm;

import static io.agentfarm.core.Json.*;
import static org.junit.jupiter.api.Assertions.*;

import io.agentfarm.core.*;
import java.nio.file.*;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;

class EvidenceTest {
  @TempDir Path dir;
  Store store;
  WorkflowService work;
  EvidenceService evidence;
  Clock clock = Clock.systemUTC();
  String sha = "a".repeat(40);

  com.fasterxml.jackson.databind.JsonNode n(Object... v) {
    return MAPPER.valueToTree(obj(v));
  }

  com.fasterxml.jackson.databind.JsonNode dossier() {
    return MAPPER.valueToTree(evidence.get("evidence-task"));
  }

  @BeforeEach
  void setup() {
    store = new Store(dir.resolve("db").toString());
    work = new WorkflowService(store, clock);
    evidence = new EvidenceService(store, clock);
    work.create(
        n(
            "request_id",
            "evidence-task",
            "repository",
            "owner/repo",
            "title",
            "Fix",
            "request",
            "Fix bug"),
        "owner");
    String command = MAPPER.valueToTree(work.claim("worker")).path("command").path("id").asText();
    work.report(
        command,
        n(
            "state",
            "proposed",
            "proposal",
            obj(
                "summary",
                "Fix",
                "acceptance",
                "Result matches specification",
                "as_is",
                "A-->B",
                "to_be",
                "A-->C",
                "base_sha",
                "b".repeat(40),
                "unit_test",
                "unit",
                "integration_test",
                "integration")),
        "worker");
    work.action("evidence-task", "implement", n("version", 1), "owner");
    command = MAPPER.valueToTree(work.claim("worker")).path("command").path("id").asText();
    work.report(
        command,
        n("state", "review", "sha", sha, "pr", 1, "evidence", obj("sha", sha, "passed", true)),
        "worker");
  }

  com.fasterxml.jackson.databind.JsonNode decision(String kind) {
    return decision(kind, "AC-1");
  }

  com.fasterxml.jackson.databind.JsonNode decision(String kind, String criterion) {
    var d = dossier();
    return n(
        "request_id",
        UUID.randomUUID().toString(),
        "version",
        d.path("version").asInt(),
        "sha",
        d.path("sha").asText(),
        "basis",
        d.path("basis").asText(),
        "criterion",
        criterion,
        "kind",
        kind,
        "reason",
        "Checked the actual behavior against the stated acceptance criterion",
        "environment",
        "Manual browser run in test environment");
  }

  @Test
  void agentClaimIsNotVerificationAndMergeRequiresCoverage() {
    assertFalse(dossier().path("ready").asBoolean());
    assertEquals("declared_by_executor", dossier().path("agent_reports").path("trust").asText());
    assertThrows(
        IllegalArgumentException.class,
        () -> work.action("evidence-task", "merge", n("sha", sha), "owner"));
    assertThrows(
        IllegalArgumentException.class,
        () -> evidence.decide("evidence-task", decision("manual"), "node:worker"));
    evidence.decide("evidence-task", decision("manual"), "owner");
    assertTrue(dossier().path("ready").asBoolean());
    assertEquals("manually_validated", dossier().path("acceptance_state").asText());
    assertDoesNotThrow(() -> work.action("evidence-task", "merge", n("sha", sha), "owner"));
  }

  @Test
  void candidateOrReportChangesInvalidateDecisions() {
    var stale = decision("manual");
    evidence.decide("evidence-task", stale, "owner");
    store.tx(c -> {
      Store.exec(
          c,
          "UPDATE work_items SET evidence=? WHERE id='evidence-task'",
          encode(obj("sha", sha, "passed", true, "summary", "changed")));
      return null;
    });
    assertFalse(dossier().path("ready").asBoolean());
    var another = stale.deepCopy();
    ((com.fasterxml.jackson.databind.node.ObjectNode) another)
        .put("request_id", UUID.randomUUID().toString());
    assertThrows(
        IllegalArgumentException.class, () -> evidence.decide("evidence-task", another, "owner"));
    evidence.decide("evidence-task", decision("manual"), "owner");
    assertTrue(dossier().path("ready").asBoolean());
    store.tx(c -> {
      Store.exec(c, "UPDATE work_items SET sha=? WHERE id='evidence-task'", "c".repeat(40));
      return null;
    });
    assertFalse(dossier().path("ready").asBoolean());
    assertEquals(2, dossier().path("history").size());
  }

  @Test
  void exceptionsAndRevocationRemainVisibleAndSurviveRestart() {
    var request = decision("exception");
    evidence.decide("evidence-task", request, "owner");
    evidence.decide("evidence-task", request, "owner");
    assertEquals(1, dossier().path("history").size());
    assertEquals(1, dossier().path("exceptions").asInt());
    assertEquals("accepted_with_exceptions", dossier().path("acceptance_state").asText());
    evidence = new EvidenceService(new Store(dir.resolve("db").toString()), clock);
    assertTrue(dossier().path("ready").asBoolean());
    evidence.decide("evidence-task", decision("reject"), "owner");
    assertFalse(dossier().path("ready").asBoolean());
    assertEquals(2, dossier().path("history").size());
  }

  @Test
  void rejectChangedScopeAndUnknownCriteria() {
    var wrong = decision("manual");
    ((com.fasterxml.jackson.databind.node.ObjectNode) wrong).put("criterion", "AC-9");
    assertThrows(
        IllegalArgumentException.class, () -> evidence.decide("evidence-task", wrong, "owner"));
    evidence.decide("evidence-task", decision("manual"), "owner");
    store.tx(c -> {
      Store.exec(
          c,
          "UPDATE work_items SET proposal=? WHERE id='evidence-task'",
          encode(obj("acceptance", "Changed")));
      return null;
    });
    assertFalse(dossier().path("ready").asBoolean());
  }

  @Test
  void legacyScopeNeedsExplicitImport() {
    store.tx(c -> {
      Store.exec(c, "DELETE FROM acceptance_scopes");
      return null;
    });
    assertTrue(dossier().path("scope_missing").asBoolean());
    assertFalse(dossier().path("ready").asBoolean());
    assertThrows(
        IllegalArgumentException.class,
        () -> evidence.decide("evidence-task", decision("manual"), "owner"));
    evidence.importLegacy("evidence-task", n("sha", sha, "version", 1), "owner");
    assertFalse(dossier().path("scope_missing").asBoolean());
    assertFalse(dossier().path("ready").asBoolean());
  }

  private void revise(com.fasterxml.jackson.databind.JsonNode proposal) {
    work.action(
        "evidence-task", "correct", n("feedback", "Reconsider the acceptance scope"), "owner");
    String command = MAPPER.valueToTree(work.claim("worker")).path("command").path("id").asText();
    work.report(command, n("state", "proposed", "proposal", proposal), "worker");
    work.action("evidence-task", "implement", n("version", 2), "owner");
    command = MAPPER.valueToTree(work.claim("worker")).path("command").path("id").asText();
    work.report(
        command,
        n("state", "review", "sha", sha, "pr", 1, "evidence", obj("sha", sha, "passed", true)),
        "worker");
  }

  @Test
  void proposalVersionInvalidatesEvenAnIdenticalCommitAndReport() {
    evidence.decide("evidence-task", decision("manual"), "owner");
    var original = Json.parse(work.get("evidence-task").get("proposal").toString());
    revise(original);
    assertFalse(dossier().path("ready").asBoolean());
    assertEquals(1, dossier().path("history").size());
    assertEquals(2, dossier().path("version").asInt());
  }

  @Test
  void testCriteriaCannotBeSilentlyDowngradedToManual() {
    var proposal =
        (com.fasterxml.jackson.databind.node.ObjectNode)
            Json.parse(work.get("evidence-task").get("proposal").toString());
    proposal.set(
        "criteria",
        MAPPER.valueToTree(
            List.of(
                obj(
                    "id",
                    "AC-2",
                    "description",
                    "Bug reproduced and corrected",
                    "method",
                    "test",
                    "checks",
                    List.of("unit", "integration")))));
    revise(proposal);
    assertThrows(
        IllegalArgumentException.class,
        () -> evidence.decide("evidence-task", decision("manual", "AC-2"), "owner"));
    evidence.decide("evidence-task", decision("manual", "AC-1"), "owner");
    evidence.decide("evidence-task", decision("exception", "AC-2"), "owner");
    assertEquals("accepted_with_exceptions", dossier().path("acceptance_state").asText());
  }

  @Test
  void dossierRedactsCredentialFieldsAndKnownTokenFormats() {
    String secret = "afn_" + "a".repeat(43);
    store.tx(c -> {
      Store.exec(
          c,
          "UPDATE work_items SET evidence=? WHERE id='evidence-task'",
          encode(obj("sha", sha, "password", "private-value", "output", secret)));
      return null;
    });
    String exported = Json.encode(evidence.get("evidence-task"));
    assertFalse(exported.contains("private-value"));
    assertFalse(exported.contains(secret));
    assertTrue(exported.contains("[oculto]"));
  }

  @Test
  void structuredCriteriaRequireValidUniqueIdsAndMethods() {
    assertThrows(
        IllegalArgumentException.class,
        () ->
            EvidenceService.criteria(
                n(
                    "criteria",
                    List.of(
                        obj(
                            "id",
                            "AC-1",
                            "description",
                            "X",
                            "method",
                            "test",
                            "checks",
                            List.of("unit", "unit"))))));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            EvidenceService.criteria(
                n(
                    "criteria",
                    List.of(
                        obj("id", "AC-1", "description", "X", "method", "manual"),
                        obj("id", "AC-1", "description", "Y", "method", "manual")))));
    assertDoesNotThrow(() ->
        EvidenceService.criteria(
            n(
                "criteria",
                List.of(
                    obj(
                        "id",
                        "AC-1",
                        "description",
                        "X",
                        "method",
                        "test",
                        "checks",
                        List.of("unit"))))));
  }
}
