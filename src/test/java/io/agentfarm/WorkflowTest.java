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

  Map<String, Object> claim() {
    return (Map<String, Object>) service.claim("agent");
  }

  String command(Map<String, Object> claim) {
    return ((Map<String, Object>) claim.get("command")).get("id").toString();
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
        new String[][] {{"request-a1", "owner/a"}, {"request-a2", "owner/a"}, {"request-b1", "owner/b"}, {"request-c1", "owner/c"}})
      parallel.create(n("request_id", r[0], "repository", r[1], "title", "T", "request", "R"), "owner");
    var first = (Map<?, ?>) parallel.claim("node-s1");
    assertEquals("request-a1", ((Map<?, ?>) first.get("item")).get("id"));
    parallel.report(((Map<?, ?>) first.get("command")).get("id").toString(), n("state", "heartbeat"), "node-s1");
    assertTrue(((Map<?, ?>) parallel.claim("node-s1")).isEmpty(), "one command per executor");
    var second = (Map<?, ?>) parallel.claim("node-s2");
    assertEquals(
        "request-b1", ((Map<?, ?>) second.get("item")).get("id"), "same repository is skipped");
    assertTrue(((Map<?, ?>) parallel.claim("node-s3")).isEmpty(), "global limit reached");
    assertThrows(IllegalArgumentException.class, () -> new WorkflowService(store, Clock.systemUTC(), 2, 3));
    assertThrows(IllegalArgumentException.class, () -> ControlService.nodeIdentifier("napoleon-s1"));
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
    var broker = (Map<String, Object>) service.claimIntegration("control-plane");
    assertEquals("merge", ((Map<?, ?>) broker.get("command")).get("kind"));
  }

  @Test
  void pausePreventsClaim() {
    create();
    store.tx(
        c -> {
          Store.exec(c, "UPDATE governance SET paused=1");
          return null;
        });
    assertTrue(claim().isEmpty());
  }
}
