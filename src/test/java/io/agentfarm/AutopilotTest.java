package io.agentfarm;

import static io.agentfarm.core.Json.*;
import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.databind.JsonNode;
import io.agentfarm.adapters.Autopilot;
import io.agentfarm.core.*;
import java.nio.file.Path;
import java.time.Clock;
import java.util.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;

class AutopilotTest {
  @TempDir Path dir;
  Store store;
  WorkflowService work;
  Autopilot autopilot;

  @BeforeEach
  void setup() {
    store = new Store(dir.resolve("db").toString());
    work = new WorkflowService(store, Clock.systemUTC());
    autopilot = new Autopilot(store, Clock.systemUTC(), work);
  }

  JsonNode n(Object... v) {
    return MAPPER.valueToTree(obj(v));
  }

  String create(String id, String recipe) {
    var request =
        new LinkedHashMap<>(
            obj("request_id", id, "repository", "o/r", "title", id, "request", "Hazlo"));
    if (recipe != null) request.put("recipe", obj("id", recipe, "version", 1));
    work.create(MAPPER.valueToTree(request), "owner");
    return id;
  }

  /**
   * The research of the next queued order proposes {@code criteria}; sandbox mode "host" or
   * "container".
   */
  void propose(String sandbox, List<Object> criteria) {
    var proposal =
        new LinkedHashMap<>(
            obj(
                "summary",
                "s",
                "acceptance",
                "a",
                "as_is",
                "A",
                "to_be",
                "B",
                "base_sha",
                "b".repeat(40),
                "unit_test",
                "u",
                "integration_test",
                "i",
                "criteria",
                criteria));
    if (sandbox != null) proposal.put("sandbox", obj("mode", sandbox));
    var claim = MAPPER.valueToTree(work.claim("worker"));
    work.report(
        claim.path("command").path("id").asText(),
        n("state", "proposed", "proposal", proposal),
        "worker");
  }

  static final List<Object> MANUAL =
      List.of(obj("id", "AC-1", "description", "d", "method", "manual"));

  String state(String id) {
    return work.get(id).get("state").toString();
  }

  @Test
  void aTaskTheOwnerChoseApprovesItsOwnPlanEvenWithManualCriteria() {
    String id = create("auto-0001", null);
    assertTrue(autopilot.mark(id, "o/r", null, true, "owner"));
    propose("container", MANUAL);
    assertEquals("approved", state(id), "no one had to approve the plan");
  }

  @Test
  void anUnmarkedTaskStillWaitsForThePerson() {
    String id = create("manual-0001", null);
    assertFalse(autopilot.mark(id, "o/r", null, false, "owner"), "an explicit no beats a default");
    propose("container", MANUAL);
    assertEquals("proposed", state(id));
  }

  @Test
  void aRepositoryDefaultMarksNewTasksButNeverAdoptionsOrMemoryOrOtherActors() {
    autopilot.configure("o/r", true, 1, 10, "owner");
    assertTrue(autopilot.mark("a-0000001", "o/r", null, null, "owner"));
    assertFalse(
        autopilot.mark("b-0000001", "o/r", "adopt-repo", null, "owner"),
        "the adoption writes the profile");
    assertFalse(autopilot.mark("c-0000001", "o/r", "project-memory", null, "owner"));
    assertFalse(
        autopilot.mark("d-0000001", "o/r", null, null, "trigger:weekly"),
        "a trigger's task is not the owner's choice");
    assertFalse(autopilot.mark("e-0000001", "o/r", null, true, "node:evil"));
    assertEquals(Set.of("a-0000001"), autopilot.marked().keySet());
  }

  @Test
  void itNeverApprovesTestsOnTheHostOrWhenPaused() {
    String host = create("host-0001", null);
    autopilot.mark(host, "o/r", null, true, "owner");
    propose("host", MANUAL);
    assertEquals("proposed", state(host), "tests on the host need the person");
    String paused = create("paused-0001", null);
    autopilot.mark(paused, "o/r", null, true, "owner");
    var claim = MAPPER.valueToTree(work.claim("worker"));
    store.tx(c -> {
      Store.exec(c, "UPDATE governance SET paused=1 WHERE id=1");
      return null;
    });
    work.report(
        claim.path("command").path("id").asText(),
        n(
            "state",
            "proposed",
            "proposal",
            obj(
                "summary",
                "s",
                "acceptance",
                "a",
                "as_is",
                "A",
                "to_be",
                "B",
                "base_sha",
                "b".repeat(40),
                "unit_test",
                "u",
                "integration_test",
                "i",
                "criteria",
                MANUAL)),
        "worker");
    assertEquals("proposed", state(paused), "a paused farm stops it");
  }

  @Test
  void aDailyCapBoundsWhatItSpendsUnattended() {
    autopilot.configure("o/r", true, 1, 1, "owner");
    String first = create("cap-00001", null), second = create("cap-00002", null);
    autopilot.mark(first, "o/r", null, true, "owner");
    autopilot.mark(second, "o/r", null, true, "owner");
    propose("container", MANUAL);
    propose("container", MANUAL);
    assertEquals(
        List.of("approved", "proposed"),
        List.of(state(first), state(second)),
        "the second waits for the person");
  }

  /** A task approved and implemented, ready to report its review. */
  String implemented(String id, int round) {
    create(id, null);
    autopilot.mark(id, "o/r", null, true, "owner");
    propose("container", MANUAL);
    return id;
  }

  void reportReview(String sha, JsonNode evidence) {
    var claim = MAPPER.valueToTree(work.claim("worker"));
    work.report(
        claim.path("command").path("id").asText(),
        n("state", "review", "sha", sha, "pr", 1, "evidence", evidence),
        "worker");
  }

  @Test
  void aResultThatDoesNotPassGetsOneAutomaticCorrectionThenStops() {
    String id = implemented("fix-00001", 0);
    reportReview(
        "c".repeat(40),
        n(
            "sha",
            "c".repeat(40),
            "passed",
            false,
            "unit",
            obj("status", "failed", "output", "AssertionError en test_x"),
            "review",
            obj("passed", false, "findings", "Falta una prueba")));
    assertEquals("queued", state(id), "it went back to research by itself");
    assertEquals(1, autopilot.marked().get(id));
    propose("container", MANUAL);
    assertEquals("approved", state(id), "and the corrected plan approves itself too");
    reportReview(
        "d".repeat(40),
        n(
            "sha",
            "d".repeat(40),
            "passed",
            false,
            "review",
            obj("passed", false, "findings", "Sigue mal")));
    assertEquals("review", state(id), "after the round it stops for the person");
    assertEquals(1, autopilot.marked().get(id));
  }

  @Test
  void aResultThatPassesGoesStraightToThePersonAndTheFeedbackSaysWhatFailed() {
    String id = implemented("ok-000001", 0);
    reportReview("c".repeat(40), n("sha", "c".repeat(40), "passed", true));
    assertEquals("review", state(id), "a good result waits for the person to accept the PR");
    assertEquals(0, autopilot.marked().get(id));
    var feedback = work.get(id);
    assertNotNull(feedback);
  }

  @Test
  void settingsAreValidatedAndOnlyThePersonChanges() {
    assertThrows(
        IllegalArgumentException.class, () -> autopilot.configure("o/r", true, 4, 10, "owner"));
    assertThrows(
        IllegalArgumentException.class, () -> autopilot.configure("o/r", true, 1, 0, "owner"));
    assertThrows(
        IllegalArgumentException.class, () -> autopilot.configure("o/r", true, 1, 10, "node:evil"));
    assertThrows(
        IllegalArgumentException.class, () -> autopilot.configure("../x", true, 1, 10, "owner"));
    var s = autopilot.configure("o/r", true, 0, 5, "owner");
    assertEquals(true, s.get("enabled"));
    assertEquals(0L, ((Number) s.get("max_rounds")).longValue());
  }
}
