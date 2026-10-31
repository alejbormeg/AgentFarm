package io.agentfarm;

import static io.agentfarm.core.Json.*;
import static org.junit.jupiter.api.Assertions.*;

import io.agentfarm.core.*;
import java.nio.file.*;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;

class InitiativeTest {
  @TempDir Path dir;
  Store db;
  WorkflowService work;
  InitiativeService initiatives;
  Clock clock = Clock.fixed(Instant.ofEpochSecond(1000000), ZoneOffset.UTC);

  com.fasterxml.jackson.databind.JsonNode n(Object... v) {
    return MAPPER.valueToTree(obj(v));
  }

  @BeforeEach
  void setup() {
    db = new Store(dir.resolve("db").toString());
    work = new WorkflowService(db, clock);
    initiatives = new InitiativeService(db, clock);
    initiatives.create(
        n(
            "request_id",
            "initiative-1",
            "title",
            "Reliable release",
            "goal",
            "Deliver dependent changes with bounded review",
            "review_limit",
            1,
            "milestones",
            List.of(obj("id", "M1", "title", "First delivery"))),
        "owner");
  }

  com.fasterxml.jackson.databind.JsonNode snapshot() {
    return MAPPER.valueToTree(initiatives.get("initiative-1"));
  }

  void task(String id) {
    work.create(
        n(
            "request_id",
            id,
            "repository",
            "owner/repo",
            "title",
            id,
            "request",
            "Investigate " + id),
        "owner");
  }

  com.fasterxml.jackson.databind.JsonNode linkRequest(String id, String... deps) {
    return n(
        "version",
        snapshot().path("version").asInt(),
        "item",
        id,
        "milestone",
        "M1",
        "depends_on",
        List.of(deps));
  }

  void link(String id, String... deps) {
    initiatives.link("initiative-1", linkRequest(id, deps), "owner");
  }

  com.fasterxml.jackson.databind.JsonNode claim() {
    return MAPPER.valueToTree(work.claim("worker", false));
  }

  String command(com.fasterxml.jackson.databind.JsonNode claim) {
    return claim.path("command").path("id").asText();
  }

  void propose(String command) {
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
                "Behavior correct",
                "base_sha",
                "b".repeat(40),
                "as_is",
                "A-->B",
                "to_be",
                "A-->C",
                "unit_test",
                "unit",
                "integration_test",
                "integration")),
        "worker");
  }

  void review(String command) {
    work.report(
        command,
        n(
            "state",
            "review",
            "pr",
            1,
            "sha",
            "a".repeat(40),
            "evidence",
            obj("sha", "a".repeat(40), "passed", true)),
        "worker");
  }

  void integrate(String id) {
    EvidenceFixtures.accept(db, clock, id);
    work.action(id, "merge", n("sha", "a".repeat(40)), "owner");
    var claim = MAPPER.valueToTree(work.claimIntegration("control-plane"));
    work.report(command(claim), n("state", "integrated", "merged", true), "control-plane");
  }

  @Test
  void dependenciesAndReviewLimitGateDispatchWithoutStarvingEligibleWork() {
    task("root-task");
    task("dependent-task");
    link("root-task");
    link("dependent-task", "root-task");
    var root = claim();
    assertEquals("root-task", root.path("item").path("id").asText());
    propose(command(root));
    work.action("root-task", "implement", n("version", 1), "owner");
    var implement = claim();
    assertEquals("root-task", implement.path("item").path("id").asText());
    assertEquals(1, snapshot().path("review_slots").asInt());
    assertTrue(
        java.util.stream.StreamSupport.stream(snapshot().path("tasks").spliterator(), false)
            .filter(t -> t.path("id").asText().equals("root-task"))
            .findFirst()
            .orElseThrow()
            .path("blockers")
            .isEmpty());
    review(command(implement));
    task("parallel-task");
    link("parallel-task");
    var research = claim();
    assertEquals("parallel-task", research.path("item").path("id").asText());
    propose(command(research));
    work.action("parallel-task", "implement", n("version", 1), "owner");
    assertTrue(claim().isEmpty());
    assertEquals("review", work.get("root-task").get("state"));
    assertEquals(0, snapshot().path("integrated").asInt());
    integrate("root-task");
    assertEquals(1, snapshot().path("integrated").asInt());
    assertEquals(0, snapshot().path("review_slots").asInt());
    assertFalse(snapshot().path("complete").asBoolean());
    assertEquals("dependent-task", claim().path("item").path("id").asText());
  }

  @Test
  void cyclesRollbackTheWholeUpdate() {
    task("root-task");
    task("dependent-task");
    link("root-task");
    link("dependent-task", "root-task");
    var before = snapshot();
    var cycle = linkRequest("root-task", "dependent-task");
    assertThrows(
        IllegalArgumentException.class, () -> initiatives.link("initiative-1", cycle, "owner"));
    assertEquals(before, snapshot());
    assertThrows(IllegalArgumentException.class, () -> link("root-task", "root-task"));
    assertEquals("root-task", claim().path("item").path("id").asText());
  }

  @Test
  void createAndLinkAreDurableAndIdempotent() {
    var first = snapshot();
    initiatives.create(
        n(
            "request_id",
            "initiative-1",
            "title",
            "Reliable release",
            "goal",
            "Deliver dependent changes with bounded review",
            "review_limit",
            1,
            "milestones",
            List.of(obj("id", "M1", "title", "First delivery"))),
        "owner");
    assertEquals(first, snapshot());
    task("root-task");
    var request = linkRequest("root-task");
    initiatives.link("initiative-1", request, "owner");
    var linked = snapshot();
    initiatives.link("initiative-1", request, "owner");
    assertEquals(linked, snapshot());
    initiatives = new InitiativeService(new Store(dir.resolve("db").toString()), clock);
    assertEquals(linked, snapshot());
    assertThrows(
        IllegalArgumentException.class,
        () ->
            initiatives.create(
                n(
                    "request_id",
                    "initiative-1",
                    "title",
                    "Other",
                    "goal",
                    "Changed",
                    "review_limit",
                    1,
                    "milestones",
                    List.of(obj("id", "M1", "title", "First delivery"))),
                "owner"));
  }

  @Test
  void pausedInitiativeDoesNotBlockUnrelatedWorkOrOverrideRunningOrders() {
    task("root-task");
    link("root-task");
    long version = snapshot().path("version").asLong();
    initiatives.pause("initiative-1", n("version", version, "paused", true), "owner");
    task("unlinked-task");
    var other = claim();
    assertEquals("unlinked-task", other.path("item").path("id").asText());
    propose(command(other));
    assertTrue(claim().isEmpty());
    assertThrows(
        IllegalArgumentException.class,
        () -> initiatives.pause("initiative-1", n("version", version, "paused", false), "owner"));
    initiatives.pause(
        "initiative-1",
        n("version", snapshot().path("version").asLong(), "paused", false),
        "owner");
    var root = claim();
    assertEquals("root-task", root.path("item").path("id").asText());
    initiatives.pause(
        "initiative-1", n("version", snapshot().path("version").asLong(), "paused", true), "owner");
    assertDoesNotThrow(() -> propose(command(root)));
  }

  @Test
  void claimedAndReviewedTasksCannotBeRewired() {
    task("root-task");
    task("other-task");
    link("root-task");
    link("other-task");
    var root = claim();
    assertThrows(IllegalArgumentException.class, () -> link("root-task", "other-task"));
    propose(command(root));
    work.action("root-task", "implement", n("version", 1), "owner");
    var research = claim();
    propose(command(research));
    var implementation = claim();
    review(command(implementation));
    assertThrows(IllegalArgumentException.class, () -> link("root-task", "other-task"));
  }

  @Test
  void milestonesOnlyCompleteAfterIntegrationAndScopeStillNeedsApproval() {
    assertFalse(snapshot().path("milestones").get(0).path("complete").asBoolean());
    task("root-task");
    link("root-task");
    var research = claim();
    propose(command(research));
    assertTrue(claim().isEmpty());
    work.action("root-task", "implement", n("version", 1), "owner");
    review(command(claim()));
    assertFalse(snapshot().path("complete").asBoolean());
    integrate("root-task");
    assertTrue(snapshot().path("complete").asBoolean());
    assertTrue(snapshot().path("milestones").get(0).path("complete").asBoolean());
  }
}
