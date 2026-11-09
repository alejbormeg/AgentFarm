package io.agentfarm;

import static io.agentfarm.core.Json.*;
import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.databind.JsonNode;
import io.agentfarm.core.*;
import java.nio.file.Path;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;

class AutomationTest {
  @TempDir Path dir;
  Store store;
  WorkflowService work;
  TriggerService triggers;
  Map<String, String> github = new HashMap<>();

  @BeforeEach
  void setup() {
    store = new Store(dir.resolve("db").toString());
    var clock = Clock.fixed(Instant.parse("2026-11-02T10:00:00Z"), ZoneOffset.UTC);
    work = new WorkflowService(store, clock, 4, 2);
    triggers = new TriggerService(store, clock, work, new GitHubReader(""));
  }

  JsonNode gh(String repo, String path) {
    return parse(github.getOrDefault(path, "[]"));
  }

  JsonNode n(Object... v) {
    return MAPPER.valueToTree(obj(v));
  }

  @Test
  void signalsBecomeProposalsOnceWithinLimitsAndNeverWhilePaused() {
    triggers.configure(
        n(
            "repository",
            "o/r",
            "kinds",
            List.of("labeled_issue", "failing_ci", "dependabot", "weekly"),
            "max_new",
            3),
        "owner");
    github.put(
        "/issues?labels=agentfarm&state=open&per_page=20",
        "[{\"number\":7,\"title\":\"Login lento\",\"body\":\"Tarda 5s\",\"html_url\":\"u\"},{\"number\":8,\"pull_request\":{}}]");
    github.put("/", "{\"default_branch\":\"main\"}");
    github.put(
        "/actions/runs?branch=main&status=completed&per_page=1",
        "{\"workflow_runs\":[{\"id\":99,\"name\":\"ci\",\"conclusion\":\"failure\",\"html_url\":\"r\",\"head_sha\":\"abc\"}]}");
    github.put(
        "/dependabot/alerts?state=open&per_page=20",
        "[{\"number\":3,\"html_url\":\"d\",\"dependency\":{\"package\":{\"name\":\"lodash\"}},\"security_advisory\":{\"severity\":\"high\",\"summary\":\"proto\"}}]");
    var first = triggers.tick(this::gh);
    assertEquals(3, first.size(), "max_new per pass");
    var second = triggers.tick(this::gh);
    assertEquals(1, second.size(), "remaining signal next pass");
    assertTrue(triggers.tick(this::gh).isEmpty(), "each signal only once");
    var items = work.items();
    assertEquals(4, items.size());
    assertTrue(items.stream().allMatch(i -> i.get("state").equals("queued")), "proposals only");
    assertTrue(
        items.stream()
            .anyMatch(
                i -> MAPPER.valueToTree(i).path("recipe").path("id").asText().equals("bugfix")));
    store.tx(c -> Store.exec(c, "UPDATE governance SET paused=1"));
    github.put(
        "/issues?labels=agentfarm&state=open&per_page=20",
        "[{\"number\":9,\"title\":\"x\",\"body\":\"\",\"html_url\":\"u\"}]");
    assertTrue(triggers.tick(this::gh).isEmpty());
    assertThrows(
        IllegalArgumentException.class,
        () -> triggers.configure(n("repository", "o/r", "kinds", List.of("deploy")), "owner"));
  }

  /**
   * The adoption form: a retry keeps one task, and watching starts once the adoption integrates.
   */
  @Test
  void anAdoptionIsCreatedOnceAndItsTriggersWaitUntilItIsIntegrated() {
    var request =
        n(
            "request_id",
            "adopt-o-r-1",
            "repository",
            "o/r",
            "title",
            "Adoptar o/r",
            "request",
            "Adopta este repositorio",
            "recipe",
            obj("id", "adopt-repo", "version", 2));
    String id = MAPPER.valueToTree(work.create(request, "owner")).path("id").asText();
    // The trigger call failed, the owner clicks again: same request id, same task.
    assertEquals(id, MAPPER.valueToTree(work.create(request, "owner")).path("id").asText());
    assertEquals(1, work.items().size());

    var configured =
        triggers.configure(
            n("repository", "o/r", "kinds", List.of("labeled_issue"), "enabled", true, "after", id),
            "owner");
    assertEquals(id, configured.get("waiting_for"));
    github.put(
        "/issues?labels=agentfarm&state=open&per_page=20",
        "[{\"number\":7,\"title\":\"Login lento\",\"body\":\"\",\"html_url\":\"u\"}]");
    assertTrue(triggers.tick(this::gh).isEmpty(), "nothing is watched before the adoption lands");
    assertEquals(id, triggers.policies().get(0).get("waiting_for"));

    store.tx(c -> Store.exec(c, "UPDATE work_items SET state='integrated' WHERE id=?", id));
    assertNull(triggers.policies().get(0).get("waiting_for"));
    assertEquals(1, triggers.tick(this::gh).size(), "integrated: the watch starts");

    assertThrows(
        IllegalArgumentException.class,
        () ->
            triggers.configure(
                n("repository", "o/other", "kinds", List.of("weekly"), "after", id), "owner"),
        "an adoption of another repository can't gate this one");
  }

  @Test
  void newSentryErrorsBecomeBugfixProposalsOnce() {
    assertThrows(
        IllegalArgumentException.class,
        () -> triggers.configure(n("repository", "o/s", "kinds", List.of("sentry")), "owner"),
        "organization and project are required");
    triggers.configure(
        n(
            "repository",
            "o/s",
            "kinds",
            List.of("sentry"),
            "sentry",
            obj("organization", "acme", "project", "shop")),
        "owner");
    var paths = new ArrayList<String>();
    SentryReader.Source sentry =
        path -> {
          paths.add(path);
          return parse(
              "[{\"id\":\"42\",\"title\":\"TypeError: total is undefined\",\"culprit\":\"cart.js\","
                  + "\"permalink\":\"https://sentry.io/i/42\",\"level\":\"error\",\"count\":\"17\"}]");
        };
    assertEquals(1, triggers.tick(this::gh, sentry).size());
    assertTrue(triggers.tick(this::gh, sentry).isEmpty(), "once per Sentry issue");
    assertTrue(paths.get(0).startsWith("/api/0/projects/acme/shop/issues/"));
    var item = MAPPER.valueToTree(work.items().get(0));
    assertEquals("bugfix", item.path("recipe").path("id").asText());
    assertTrue(item.path("request").asText().contains("https://sentry.io/i/42"));
  }

  String researched(String id, String recipe, List<Object> criteria) {
    work.create(
        n(
            "request_id",
            id,
            "repository",
            "o/" + id,
            "title",
            "T",
            "request",
            "R",
            "recipe",
            obj("id", recipe, "version", 1)),
        "owner");
    var claim = (Map<?, ?>) work.claim("agent-" + id);
    String command = ((Map<?, ?>) claim.get("command")).get("id").toString();
    work.report(
        command,
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
                "x",
                "to_be",
                "y",
                "unit_test",
                "u",
                "integration_test",
                "i",
                "base_sha",
                "b".repeat(40),
                "criteria",
                criteria)),
        "agent-" + id);
    return work.get(id).get("state").toString();
  }

  @Test
  void autonomyApprovesOnlyLowRiskTestVerifiedProposals() {
    var tested =
        List.<Object>of(
            obj("id", "AC-1", "description", "d", "method", "test", "checks", List.of("unit")));
    var manual = List.<Object>of(obj("id", "AC-1", "description", "d", "method", "manual"));
    assertEquals("proposed", researched("auto-0001", "bugfix", tested), "manual by default");
    work.setAutonomy("bugfix", "auto_implement", "owner");
    assertEquals("approved", researched("auto-0002", "bugfix", tested));
    work.action("auto-0002", "cancel", n(), "owner"); // free the queue for the next cases
    assertEquals("proposed", researched("auto-0003", "bugfix", manual), "manual criteria wait");
    assertEquals("proposed", researched("auto-0004", "migration", tested), "other recipes wait");
    assertThrows(
        IllegalArgumentException.class,
        () -> work.setAutonomy("migration", "auto_implement", "owner"));
    assertThrows(
        IllegalArgumentException.class, () -> work.setAutonomy("bugfix", "auto_merge", "owner"));
  }

  @Test
  void correctionsProposeAMemoryLessonWhenEnabled() {
    var tested =
        List.<Object>of(
            obj("id", "AC-1", "description", "d", "method", "test", "checks", List.of("unit")));
    researched("lesson-001", "bugfix", tested);
    work.action("lesson-001", "correct", n("feedback", "No uses H2 en tests"), "owner");
    assertTrue(
        work.items().stream().noneMatch(i -> i.get("id").toString().startsWith("mem-")),
        "off by default");
    work.action("lesson-001", "cancel", n(), "owner");
    work.setAutonomy("project-memory", "propose_on_feedback", "owner");
    researched("lesson-002", "bugfix", tested);
    work.action("lesson-002", "correct", n("feedback", "No uses H2 en tests"), "owner");
    var lessons =
        work.items().stream().filter(i -> i.get("id").toString().startsWith("mem-")).toList();
    assertEquals(1, lessons.size());
    assertTrue(lessons.get(0).get("request").toString().contains("No uses H2"));
  }
}
