package io.agentfarm;

import static io.agentfarm.core.Json.*;
import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.databind.JsonNode;
import io.agentfarm.adapters.LocalVerifications;
import io.agentfarm.core.*;
import java.nio.file.Path;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Local CI (#241): ordered once per commit, run on the approved commands, published by the panel.
 */
class LocalVerificationsTest {
  @TempDir Path dir;
  static final String SHA = "c".repeat(40);
  Instant now = Instant.ofEpochSecond(1_000_000);
  final Clock clock =
      new Clock() {
        public ZoneId getZone() {
          return ZoneOffset.UTC;
        }

        public Clock withZone(ZoneId zone) {
          return this;
        }

        public Instant instant() {
          return now;
        }
      };
  final List<Map<String, Object>> published = new ArrayList<>();
  boolean githubDown;
  Store store;
  WorkflowService work;

  LocalVerifications setUp(boolean local) {
    store = new Store(dir.resolve("db").toString());
    new ControlService(store, clock, new BudgetService(store, clock));
    work = new WorkflowService(store, clock);
    new SchemaMigrations(
        store, work, new TelemetryService(store, clock), new NodeCredentials(store, clock));
    store.tx(c -> {
      Store.exec(
          c,
          "INSERT INTO work_items(id,repo,title,request,state,pr,sha,branch,version,created)"
              + " VALUES('task-1','o/r','t','r','review',7,?,'agentfarm/1-x',2,0)",
          SHA);
      Store.exec(
          c,
          "INSERT INTO acceptance_scopes(item,version,hash,criteria,base_sha,proposal)"
              + " VALUES('task-1',2,'h','[]',?,?)",
          "b".repeat(40),
          encode(
              obj(
                  "unit_test", "pytest -q",
                  "integration_test", "N/A: no hay servicios",
                  "setup", "pip install -r req.txt",
                  "sandbox", obj("image", "python:3.12", "network", "setup"))));
      if (local) Store.exec(c, "INSERT INTO repo_ci_modes(repo,actor,at) VALUES('o/r','owner',0)");
      return null;
    });
    return new LocalVerifications(
        store,
        clock,
        () -> true,
        (repo, run) -> {
          if (githubDown) throw new IllegalStateException("HTTP 502");
          published.add(new LinkedHashMap<>(Map.of("repo", repo, "run", run)));
        });
  }

  JsonNode claim(String agent) {
    return MAPPER.valueToTree(work.claim(agent));
  }

  JsonNode results(String unit) {
    return MAPPER.valueToTree(
        obj(
            "agent",
            "napoleon",
            "sha",
            SHA,
            "results",
            List.of(
                obj("kind", "unit", "status", unit, "command", "pytest -q", "output", "1 passed"),
                obj(
                    "kind",
                    "integration",
                    "status",
                    "not_applicable",
                    "reason",
                    "N/A: no hay servicios"))));
  }

  @Test
  void oneOrderPerCommitWithTheApprovedCommands() {
    var v = setUp(true);
    assertEquals(1, v.enqueue());
    assertEquals(0, v.enqueue(), "the same commit is ordered once");
    var claimed = claim("napoleon");
    assertEquals("verify", claimed.path("command").path("kind").asText());
    var order = parse(claimed.path("command").path("payload").asText());
    assertEquals(SHA, order.path("sha").asText());
    assertEquals("pytest -q", order.path("unit_test").asText(), "from the approved proposal");
    assertEquals("python:3.12", order.path("sandbox").path("image").asText());
    assertEquals("agentfarm/1-x", order.path("branch").asText());
  }

  @Test
  void nothingIsOrderedWhereTheOwnerDidNotChooseLocalCi() {
    assertEquals(0, setUp(false).enqueue());
  }

  @Test
  void thePanelPublishesWhatRanForThatCommitAndSaysItIsNotIndependent() {
    var v = setUp(true);
    v.enqueue();
    String command = claim("napoleon").path("command").path("id").asText();
    assertEquals(Map.of("state", "ran"), v.finish(command, "napoleon", results("passed")));
    assertEquals(2, published.size());
    var unit = MAPPER.valueToTree(published.get(0).get("run"));
    assertEquals("AgentFarm local · unit", unit.path("name").asText());
    assertEquals(SHA, unit.path("head_sha").asText());
    assertEquals("success", unit.path("conclusion").asText());
    assertTrue(unit.path("output").path("summary").asText().contains("no es una CI independiente"));
    var integration = MAPPER.valueToTree(published.get(1).get("run"));
    assertEquals("success", integration.path("conclusion").asText(), "an approved N/A passes");
    assertEquals("published", v.of("task-1").get(0).get("state"));
    assertEquals(0, v.enqueue(), "a published commit is not ordered again");
  }

  @Test
  void aFailingTestIsAFailedCheck() {
    var v = setUp(true);
    v.enqueue();
    String command = claim("napoleon").path("command").path("id").asText();
    v.finish(command, "napoleon", results("failed"));
    assertEquals(
        "failure", MAPPER.valueToTree(published.get(0).get("run")).path("conclusion").asText());
  }

  @Test
  void onlyTheExecutorHoldingTheOrderAnswersAndOnlyForThatCommit() {
    var v = setUp(true);
    v.enqueue();
    String command = claim("napoleon").path("command").path("id").asText();
    assertThrows(
        IllegalArgumentException.class, () -> v.finish(command, "intruder", results("passed")));
    var other = (com.fasterxml.jackson.databind.node.ObjectNode) results("passed");
    other.put("sha", "d".repeat(40));
    assertThrows(IllegalArgumentException.class, () -> v.finish(command, "napoleon", other));
    var partial = MAPPER.valueToTree(obj("agent", "napoleon", "sha", SHA, "results", List.of()));
    assertThrows(
        IllegalArgumentException.class,
        () -> v.finish(command, "napoleon", partial),
        "every approved command must have its result");
    assertTrue(published.isEmpty());
  }

  @Test
  void anOrderThatCouldNotRunIsOrderedAgainLaterWithoutBlockingTheTask() {
    var v = setUp(true);
    v.enqueue();
    String command = claim("napoleon").path("command").path("id").asText();
    var error = MAPPER.valueToTree(obj("agent", "napoleon", "sha", SHA, "error", "clone failed"));
    assertEquals(Map.of("state", "error"), v.finish(command, "napoleon", error));
    assertTrue(published.isEmpty());
    assertEquals(
        "review",
        store.tx(c -> Store.one(c, "SELECT state FROM work_items WHERE id='task-1'")).get("state"));
    assertEquals(0, v.enqueue(), "not straight away");
    now = now.plusSeconds(601);
    assertEquals(1, v.enqueue());
  }

  @Test
  void aCheckGitHubRefusedIsPublishedOnALaterTick() {
    var v = setUp(true);
    v.enqueue();
    String command = claim("napoleon").path("command").path("id").asText();
    githubDown = true;
    v.finish(command, "napoleon", results("passed"));
    assertEquals("unpublished", v.of("task-1").get(0).get("state"));
    githubDown = false;
    v.publishPending();
    assertEquals("published", v.of("task-1").get(0).get("state"));
    assertEquals(2, published.size());
  }
}
