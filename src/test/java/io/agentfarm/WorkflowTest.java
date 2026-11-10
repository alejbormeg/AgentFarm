package io.agentfarm;

import static io.agentfarm.core.Json.*;
import static org.junit.jupiter.api.Assertions.*;

import io.agentfarm.core.*;
import java.nio.file.*;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;

class WorkflowTest {
  @TempDir Path dir;
  Store store;
  WorkflowService service;

  @BeforeEach
  void setup() {
    store = new Store(dir.resolve("db").toString());
    service = new WorkflowService(store, Clock.fixed(Instant.ofEpochSecond(1000), ZoneOffset.UTC));
  }

  com.fasterxml.jackson.databind.JsonNode n(Object... values) {
    return MAPPER.valueToTree(obj(values));
  }

  void create() {
    service.create(
        n(
            "request_id",
            "request-123",
            "repository",
            "owner/repo",
            "title",
            "Task",
            "request",
            "Implement tests"),
        "owner");
  }

  @SuppressWarnings("unchecked")
  Map<String, Object> claim() {
    return (Map<String, Object>) service.claim("agent");
  }

  String command(Map<String, Object> claim) {
    return ((Map<?, ?>) claim.get("command")).get("id").toString();
  }

  void proposal(String command) {
    service.report(
        command,
        n(
            "state",
            "proposed",
            "proposal",
            obj(
                "base_sha",
                "b".repeat(40),
                "summary",
                "Change",
                "acceptance",
                "Tests",
                "as_is",
                "flowchart LR\nA-->B",
                "to_be",
                "flowchart LR\nA-->C",
                "unit_test",
                "./unit",
                "integration_test",
                "./integration")),
        "agent");
  }

  @Test
  void credentialsAnAgentReportsAreNeitherStoredNorListed() throws Exception {
    String token = "github" + "_pat_" + "Zz9_".repeat(15),
        node = "af" + "n_" + "q".repeat(43),
        key = "s" + "k-ant-" + "Yy8-".repeat(10);
    create();
    String first = command(claim());
    service.report(
        first,
        n(
            "state",
            "proposed",
            "proposal",
            obj(
                "base_sha",
                "b".repeat(40),
                "summary",
                "Uses " + token,
                "acceptance",
                "Tests",
                "as_is",
                "flowchart LR\nA-->B",
                "to_be",
                "flowchart LR\nA-->C",
                "unit_test",
                "./unit",
                "integration_test",
                "./integration"),
            "evidence",
            obj("checks", List.of(obj("output", "Authorization: Bearer " + node + " " + key)))),
        "agent");
    String raw =
        store.tx(c ->
            Store.one(c, "SELECT proposal || evidence AS t FROM work_items").get("t").toString());
    String listed = Json.encode(service.items());
    for (String secret : List.of(token, node, key)) {
      assertFalse(raw.contains(secret), "stored");
      assertFalse(listed.contains(secret), "listed");
    }
    assertTrue(raw.contains("[credencial oculta]"));
    // A row written before redaction existed is still redacted on the way out.
    store.tx(c -> Store.exec(c, "UPDATE work_items SET error=?", "fallo con " + token));
    assertFalse(Json.encode(service.items()).contains(token));
  }

  @Test
  void idempotentRequestAndConflict() {
    create();
    create();
    assertEquals(1, service.items().size());
    assertThrows(
        IllegalArgumentException.class,
        () ->
            service.create(
                n(
                    "request_id",
                    "request-123",
                    "repository",
                    "owner/repo",
                    "title",
                    "Different",
                    "request",
                    "Implement tests"),
                "owner"));
  }

  @Test
  void slotsRunInParallelWithinGlobalAndRepositoryLimits() {
    var parallel =
        new WorkflowService(store, Clock.fixed(Instant.ofEpochSecond(1000), ZoneOffset.UTC), 2, 1);
    for (String[] r :
        new String[][] {
          {"request-a1", "owner/a"},
          {"request-a2", "owner/a"},
          {"request-b1", "owner/b"},
          {"request-c1", "owner/c"}
        })
      parallel.create(
          n("request_id", r[0], "repository", r[1], "title", "T", "request", "R"), "owner");
    var first = (Map<?, ?>) parallel.claim("node-s1");
    assertEquals("request-a1", ((Map<?, ?>) first.get("item")).get("id"));
    parallel.report(
        ((Map<?, ?>) first.get("command")).get("id").toString(),
        n("state", "heartbeat"),
        "node-s1");
    assertTrue(((Map<?, ?>) parallel.claim("node-s1")).isEmpty(), "one command per executor");
    var second = (Map<?, ?>) parallel.claim("node-s2");
    assertEquals(
        "request-b1", ((Map<?, ?>) second.get("item")).get("id"), "same repository is skipped");
    assertTrue(((Map<?, ?>) parallel.claim("node-s3")).isEmpty(), "global limit reached");
    assertThrows(
        IllegalArgumentException.class, () -> new WorkflowService(store, Clock.systemUTC(), 2, 3));
    assertThrows(
        IllegalArgumentException.class, () -> ControlService.nodeIdentifier("napoleon-s1"));
    assertDoesNotThrow(() -> ControlService.nodeIdentifier("napoleon-sur"));
  }

  @Test
  void lostClaimResponseIsRedeliveredUntilTheExecutorReports() {
    create();
    String first = command(claim());
    assertEquals(first, command(claim()), "response lost: same command again");
    service.report(first, n("state", "heartbeat"), "agent");
    assertTrue(claim().isEmpty(), "after a report the executor owns it; nothing new");
    assertTrue(service.claim("other-agent") instanceof Map<?, ?> m && m.isEmpty());
  }

  @Test
  void claimReturnsTheClaimedCommand() {
    create();
    var command = (Map<?, ?>) claim().get("command");
    assertEquals("claimed", command.get("state"));
    assertEquals("agent", command.get("claimed_by"));
  }

  @Test
  void onlyOneClaimAndApprovalVersion() {
    create();
    String c = command(claim());
    service.report(c, n("state", "heartbeat"), "agent");
    assertTrue(claim().isEmpty());
    proposal(c);
    assertThrows(
        IllegalArgumentException.class,
        () -> service.action("request-123", "implement", n("version", 0), "owner"));
    service.action("request-123", "implement", n("version", 1), "owner");
    assertThrows(
        IllegalArgumentException.class,
        () -> service.action("request-123", "implement", n("version", 1), "owner"));
    assertEquals("implement", ((Map<?, ?>) claim().get("command")).get("kind"));
  }

  @Test
  void rejectsUnownedOrWrongStage() {
    create();
    String c = command(claim());
    assertThrows(
        IllegalArgumentException.class,
        () -> service.report(c, n("state", "heartbeat"), "intruder"));
    assertThrows(
        IllegalArgumentException.class,
        () -> service.report(c, n("state", "integrated", "merged", true), "agent"));
  }

  /** v1 approved, then a correction whose agent answers with these criteria. */
  com.fasterxml.jackson.databind.JsonNode corrected(
      String feedback, List<String> retire, Object... criteria) {
    approved();
    var body = obj("feedback", feedback);
    if (!retire.isEmpty()) body.put("retire", retire);
    service.action("request-123", "correct", MAPPER.valueToTree(body), "owner");
    var item = parse(one("SELECT proposal FROM work_items WHERE id='request-123'")).deepCopy();
    ((com.fasterxml.jackson.databind.node.ObjectNode) item)
        .set("criteria", MAPPER.valueToTree(List.of(criteria)));
    service.report(command(claim()), n("state", "proposed", "proposal", item), "agent");
    return parse(one("SELECT proposal FROM work_items WHERE id='request-123'"));
  }

  void approved() {
    create();
    proposal(command(claim()), test("AC-1", "Works"));
    service.action("request-123", "implement", n("version", 1), "owner");
    service.report(command(claim()), n("state", "blocked", "error", "Fallo del agente"), "agent");
  }

  void proposal(String command, Object... criteria) {
    service.report(
        command,
        n(
            "state",
            "proposed",
            "proposal",
            obj(
                "base_sha", "b".repeat(40),
                "summary", "Change",
                "acceptance", "Tests",
                "as_is", "flowchart LR\nA-->B",
                "to_be", "flowchart LR\nA-->C",
                "unit_test", "./unit",
                "integration_test", "./integration",
                "criteria", List.of(criteria))),
        "agent");
  }

  String one(String sql) {
    return store.tx(c -> Store.one(c, sql).values().iterator().next().toString());
  }

  Object test(String id, String description) {
    return obj("id", id, "description", description, "method", "test", "checks", List.of("unit"));
  }

  @Test
  void aCorrectionKeepsTheApprovedCriteriaAndAddsItsOwn() {
    var p =
        corrected(
            "Corrige solo el último fallo", List.of(), test("AC-1", "Solo cubre la corrección"));
    var criteria = p.path("criteria");
    assertEquals(2, criteria.size());
    assertEquals("AC-1", criteria.get(0).path("id").asText());
    assertEquals(
        "Works", criteria.get(0).path("description").asText(), "the approved text is kept");
    assertEquals(
        "AC-2", criteria.get(1).path("id").asText(), "reusing an id does not replace a criterion");
    assertEquals("Solo cubre la corrección", criteria.get(1).path("description").asText());
    assertEquals("AC-1", p.path("kept_criteria").get(0).asText());
  }

  @Test
  void aSameCriterionIsNotRepeated() {
    var p = corrected("Corrige el fallo", List.of(), test("AC-7", "  Works "));
    assertEquals(1, p.path("criteria").size());
  }

  @Test
  void anAgentCannotRetireACriterionByItself() {
    var p = corrected("Corrige el fallo", List.of(), test("AC-1", "Otro"));
    assertTrue(p.path("retired_criteria").isEmpty());
    assertEquals("Works", p.path("criteria").get(0).path("description").asText());
  }

  @Test
  void onlyAPersonRetiresACriterionWithAReasonAndItShows() {
    var p =
        corrected(
            "Ese criterio no se puede acreditar con la plataforma",
            List.of("AC-1"),
            test("AC-1", "Otro"));
    assertEquals(1, p.path("criteria").size());
    assertEquals("Otro", p.path("criteria").get(0).path("description").asText());
    var retired = p.path("retired_criteria").get(0);
    assertEquals("AC-1", retired.path("id").asText());
    assertEquals("Works", retired.path("description").asText());
    assertEquals("owner", retired.path("actor").asText());
    assertTrue(retired.path("reason").asText().startsWith("Ese criterio"));
    assertEquals("1", one("SELECT COUNT(*) FROM audit WHERE action='work.retire-criterion'"));
  }

  @Test
  void retiringNeedsAReasonAndAnApprovedCriterion() {
    approved();
    assertThrows(
        IllegalArgumentException.class,
        () ->
            service.action(
                "request-123",
                "correct",
                n("feedback", "Corto", "retire", List.of("AC-1")),
                "owner"));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            service.action(
                "request-123",
                "correct",
                n("feedback", "Un motivo con la longitud suficiente", "retire", List.of("AC-9")),
                "owner"));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            service.action(
                "request-123",
                "correct",
                n("feedback", "Un motivo con la longitud suficiente", "retire", List.of("AC-1")),
                "node:napoleon"));
  }

  @Test
  void beforeAnyApprovalAgentsMayStillRewriteTheirProposal() {
    create();
    proposal(command(claim()), test("AC-1", "Works"));
    service.action("request-123", "correct", n("feedback", "Replantea los criterios"), "owner");
    var item = parse(one("SELECT proposal FROM work_items WHERE id='request-123'")).deepCopy();
    ((com.fasterxml.jackson.databind.node.ObjectNode) item)
        .set("criteria", MAPPER.valueToTree(List.of(test("AC-1", "Otro"))));
    service.report(command(claim()), n("state", "proposed", "proposal", item), "agent");
    var p = parse(one("SELECT proposal FROM work_items WHERE id='request-123'"));
    assertEquals(1, p.path("criteria").size());
    assertEquals("Otro", p.path("criteria").get(0).path("description").asText());
  }

  @Test
  void onlyTheExecutorHoldingAnOrderLearnsItsRepository() {
    create();
    String command = command(claim());
    assertEquals("owner/repo", service.heldRepository(command, "agent").get("repo"));
    assertEquals("research", service.heldRepository(command, "agent").get("kind"));
    assertThrows(IllegalArgumentException.class, () -> service.heldRepository(command, "other"));
    proposal(command);
    assertThrows(
        IllegalArgumentException.class,
        () -> service.heldRepository(command, "agent"),
        "already finished");
  }

  @Test
  void cancelRevokesCommand() {
    create();
    String c = command(claim());
    service.action("request-123", "cancel", n(), "owner");
    assertThrows(IllegalArgumentException.class, () -> proposal(c));
    assertEquals("cancelled", service.get("request-123").get("state"));
  }

  @Test
  void staleClaimsBlockWithoutReplay() {
    create();
    claim();
    var later =
        new WorkflowService(store, Clock.fixed(Instant.ofEpochSecond(1121), ZoneOffset.UTC));
    assertEquals(Map.of(), later.claim("other"));
    assertEquals("blocked", later.get("request-123").get("state"));
  }

  @Test
  void candidateAndMergeNeedExactShaAndConfirmation() {
    create();
    proposal(command(claim()));
    service.action("request-123", "implement", n("version", 1), "owner");
    String c = command(claim());
    assertThrows(
        IllegalArgumentException.class, () -> service.report(c, n("state", "review"), "agent"));
    String sha = "a".repeat(40);
    service.report(c, n("state", "review", "pr", 12, "sha", sha), "agent");
    assertThrows(
        IllegalArgumentException.class,
        () -> service.action("request-123", "merge", n("sha", "b".repeat(40)), "owner"));
    EvidenceFixtures.accept(store, Clock.systemUTC(), "request-123");
    service.action("request-123", "merge", n("sha", sha), "owner");
    String m = command(claim());
    assertThrows(
        IllegalArgumentException.class,
        () -> service.report(m, n("state", "integrated", "merged", false), "agent"));
    service.report(m, n("state", "integrated", "merged", true), "agent");
    assertEquals("integrated", service.get("request-123").get("state"));
  }

  /** Up to a PR waiting for integration: the state where cancel and merge race. */
  String inReview() {
    create();
    proposal(command(claim()));
    service.action("request-123", "implement", n("version", 1), "owner");
    String c = command(claim()), sha = "a".repeat(40);
    service.report(c, n("state", "review", "pr", 12, "sha", sha), "agent");
    EvidenceFixtures.accept(store, Clock.systemUTC(), "request-123");
    return sha;
  }

  String cancelRefusal() {
    return assertThrows(
            IllegalArgumentException.class,
            () -> service.action("request-123", "cancel", n(), "owner"))
        .getMessage();
  }

  @Test
  void anIntegratedTaskCannotBeCancelled() {
    String sha = inReview();
    service.action("request-123", "merge", n("sha", sha), "owner");
    String m = command(claim());
    assertTrue(cancelRefusal().contains("espera a su resultado"), "integration in flight");
    service.report(m, n("state", "integrated", "merged", true), "agent");
    assertTrue(cancelRefusal().contains("pide su reversión"));
    assertEquals("integrated", service.get("request-123").get("state"), "history stays true");
  }

  @Test
  void undoingIntegratedWorkIsItsOwnTracedTask() {
    String sha = inReview();
    assertThrows(
        IllegalArgumentException.class,
        () -> service.action("request-123", "revert", n(), "owner"),
        "nothing to revert before integration");
    service.action("request-123", "merge", n("sha", sha), "owner");
    service.report(command(claim()), n("state", "integrated", "merged", true), "agent");

    var revert = service.action("request-123", "revert", n(), "owner");
    String revertId = revert.get("id").toString();
    assertEquals("revert-request-123", revertId);
    assertEquals("queued", revert.get("state"), "a revert starts with research, like any task");
    assertTrue(revert.get("request").toString().contains("PR #12"));
    assertTrue(revert.get("request").toString().contains(sha));
    assertEquals("request-123", service.get(revertId).get("reverts"));
    assertEquals(revertId, service.get("request-123").get("reverted_by"));
    assertEquals(
        "integrated", service.get("request-123").get("state"), "the merge stays on record");
    assertEquals(
        revertId,
        service.action("request-123", "revert", n(), "owner").get("id"),
        "asking again returns the same revert");
    assertEquals(2, service.items().size());
    assertTrue(
        store
            .tx(c -> Store.rows(c, "SELECT subject FROM audit WHERE action='work.revert'"))
            .toString()
            .contains("request-123 -> " + revertId));
  }

  @Test
  void everyActionIsCheckedAgainstOneTableOfStates() {
    var all =
        List.of(
            "queued",
            "proposed",
            "approved",
            "review",
            "blocked",
            "integrating",
            "integrated",
            "cancelled");
    for (var e : WorkStates.ACTIONS.entrySet())
      for (String state : all) {
        boolean allowed = e.getValue().from().contains(state);
        if (allowed) assertDoesNotThrow(() -> WorkStates.require(e.getKey(), state));
        else
          assertThrows(
              IllegalArgumentException.class,
              () -> WorkStates.require(e.getKey(), state),
              e.getKey() + " from " + state);
      }
    assertThrows(IllegalArgumentException.class, () -> WorkStates.require("delete", "queued"));
    for (String terminal : List.of("integrated", "cancelled"))
      assertTrue(
          WorkStates.ACTIONS.entrySet().stream()
              .filter(e -> e.getValue().from().contains(terminal))
              .allMatch(e -> e.getKey().equals("revert")),
          terminal + " only admits a revert");
  }

  @Test
  void cancelFirstThenMergeIsRefusedConsistently() {
    String sha = inReview();
    service.action("request-123", "cancel", n(), "owner");
    assertThrows(
        IllegalArgumentException.class,
        () -> service.action("request-123", "merge", n("sha", sha), "owner"));
    assertEquals("cancelled", service.get("request-123").get("state"));
    assertTrue(cancelRefusal().contains("ya está cancelada"));
  }

  @Test
  void cancelChecksTheVersionTheReviewerSaw() {
    create();
    proposal(command(claim()));
    assertThrows(
        IllegalArgumentException.class,
        () -> service.action("request-123", "cancel", n("version", 7), "owner"));
    assertEquals("proposed", service.get("request-123").get("state"));
    service.action("request-123", "cancel", n("version", 1), "owner");
    assertEquals("cancelled", service.get("request-123").get("state"));
  }

  @Test
  void executorCannotClaimIntegration() {
    create();
    proposal(command(claim()));
    service.action("request-123", "implement", n("version", 1), "owner");
    String c = command(claim()), sha = "a".repeat(40);
    service.report(c, n("state", "review", "pr", 12, "sha", sha), "agent");
    EvidenceFixtures.accept(store, Clock.systemUTC(), "request-123");
    service.action("request-123", "merge", n("sha", sha), "owner");
    assertEquals(Map.of(), service.claim("executor", false));
    var broker = (Map<?, ?>) service.claimIntegration("control-plane");
    assertEquals("merge", ((Map<?, ?>) broker.get("command")).get("kind"));
  }

  @Test
  void pausePreventsClaim() {
    create();
    store.tx(c -> {
      Store.exec(c, "UPDATE governance SET paused=1");
      return null;
    });
    assertTrue(claim().isEmpty());
  }
}
