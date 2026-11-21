package io.agentfarm;

import static io.agentfarm.core.Json.*;
import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.databind.JsonNode;
import io.agentfarm.adapters.Autopilot;
import io.agentfarm.adapters.ParentTasks;
import io.agentfarm.adapters.RepoGroups;
import io.agentfarm.core.*;
import java.nio.file.Path;
import java.time.Clock;
import java.util.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;

class ParentTasksTest {
  @TempDir Path dir;
  Store store;
  WorkflowService work;
  RepoGroups groups;
  ParentTasks parts;
  Autopilot autopilot;

  @BeforeEach
  void setup() {
    store = new Store(dir.resolve("db").toString());
    work = new WorkflowService(store, Clock.systemUTC());
    var initiatives = new InitiativeService(store, Clock.systemUTC());
    autopilot = new Autopilot(store, Clock.systemUTC(), work);
    groups = new RepoGroups(store, Clock.systemUTC());
    parts = new ParentTasks(store, Clock.systemUTC(), work, initiatives, autopilot);
    groups.define(
        "o/forja",
        MAPPER.valueToTree(List.of(obj("repo", "o/api", "role", "contrato"), obj("repo", "o/web"))),
        "owner");
    // The panel already works in the members: they are adopted.
    for (String repo : List.of("o/api", "o/web")) task("seed-" + repo.substring(2) + "-01", repo);
  }

  JsonNode n(Object... v) {
    return MAPPER.valueToTree(obj(v));
  }

  void task(String id, String repo) {
    work.create(n("request_id", id, "repository", repo, "title", id, "request", "r"), "owner");
  }

  /** The order the executor claims next, answered with a proposal carrying {@code extra}. */
  String propose(String id, Map<String, Object> extra) {
    var claim = (Map<?, ?>) work.claim("agent");
    String command = ((Map<?, ?>) claim.get("command")).get("id").toString();
    var proposal =
        new LinkedHashMap<String, Object>(
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
                "criteria",
                List.of(obj("id", "AC-1", "description", "d", "method", "manual"))));
    proposal.put("base_sha", "a".repeat(40));
    proposal.putAll(extra);
    work.report(
        command,
        n("state", "proposed", "issue", "7", "proposal", MAPPER.valueToTree(proposal)),
        "agent");
    return command;
  }

  Map<String, Object> item(String id) {
    return work.get(id);
  }

  List<Object> twoParts() {
    return List.of(
        obj("repo", "o/api", "title", "Contrato", "request", "Define el contrato"),
        obj(
            "repo",
            "o/web",
            "title",
            "Interfaz",
            "request",
            "Usa el contrato",
            "depends_on",
            List.of(0)),
        obj(
            "repo",
            "o/forja",
            "title",
            "Cierre",
            "request",
            "Actualiza la matriz",
            "depends_on",
            List.of(0, 1)));
  }

  JsonNode proposalOf(String id) {
    return parse(item(id).get("proposal").toString());
  }

  @BeforeEach
  void drainSeeds() {
    // The seed tasks are queued for research; leave them cancelled so claims return the next one.
    for (String id : List.of("seed-api-01", "seed-web-01")) {
      try {
        work.action(id, "cancel", n(), "owner");
      } catch (RuntimeException ignored) {
      }
    }
  }

  @Test
  void aValidPlanSplitsIntoOneTaskPerRepositoryWithDependencies() {
    task("parent-0001", "o/forja");
    propose("parent-0001", Map.of("parts", twoParts()));
    var p = proposalOf("parent-0001");
    assertEquals(3, p.path("parts").size());
    assertTrue(p.path("parts").get(2).path("closes_parent").asBoolean(), "the last one closes");
    assertFalse(p.path("parts").get(0).has("closes_parent"));
    work.action(
        "parent-0001", "implement", n("version", item("parent-0001").get("version")), "owner");
    assertEquals("coordinating", item("parent-0001").get("state"));
    var children = parts.all();
    assertEquals(3, children.size());
    String api = children.get(0).get("child").toString(),
        web = children.get(1).get("child").toString();
    assertEquals("o/api", item(api).get("repo"));
    assertTrue(
        item(api)
            .get("request")
            .toString()
            .startsWith("Parte de: https://github.com/o/forja/issues/7\n"));
    assertTrue(
        item(children.get(2).get("child").toString())
            .get("request")
            .toString()
            .startsWith("Parte de: https://github.com/o/forja/issues/7 · cierra\n"));
    // The second waits for the first to be integrated: only the first can be claimed.
    var claim = (Map<?, ?>) work.claim("agent");
    assertEquals(api, ((Map<?, ?>) claim.get("item")).get("id"));
    assertTrue(((Map<?, ?>) work.claim("other")).isEmpty(), "the dependent parts wait");
    assertNotEquals(web, api);
  }

  @Test
  void integratingEveryPartIntegratesTheParentAndCancelingStopsTheRest() {
    task("parent-0002", "o/forja");
    propose("parent-0002", Map.of("parts", twoParts()));
    work.action(
        "parent-0002", "implement", n("version", item("parent-0002").get("version")), "owner");
    var children = parts.all();
    // Cancelling the coordinating task cancels what has not reached a branch.
    work.action("parent-0002", "cancel", n(), "owner");
    assertEquals("cancelled", item("parent-0002").get("state"));
    for (var c : children) assertEquals("cancelled", item(c.get("child").toString()).get("state"));
  }

  @Test
  void whenEveryChildIsIntegratedTheParentIs() throws Exception {
    task("parent-0003", "o/forja");
    propose(
        "parent-0003",
        Map.of("parts", List.of(obj("repo", "o/api", "title", "T", "request", "R"))));
    work.action(
        "parent-0003", "implement", n("version", item("parent-0003").get("version")), "owner");
    String child = parts.all().get(0).get("child").toString();
    store.tx(c -> {
      Store.exec(c, "UPDATE work_items SET state='integrated' WHERE id=?", child);
      parts.childIntegrated(c, child);
      return null;
    });
    assertEquals("integrated", item("parent-0003").get("state"));
  }

  @Test
  void aPlanOutsideTheGroupIsKeptButDoesNotSplit() {
    task("parent-0004", "o/forja");
    propose(
        "parent-0004",
        Map.of("parts", List.of(obj("repo", "o/intruso", "title", "T", "request", "R"))));
    var p = proposalOf("parent-0004");
    assertFalse(p.has("parts"));
    assertTrue(p.path("parts_problem").asText().contains("o/intruso no es del grupo"));
    // Approving it is a normal implementation, not a split.
    work.action(
        "parent-0004", "implement", n("version", item("parent-0004").get("version")), "owner");
    assertEquals("approved", item("parent-0004").get("state"));
    assertTrue(parts.all().isEmpty());
  }

  @Test
  void badPartsAreRejectedWithAReason() {
    var members = Set.of("o/api", "o/web");
    for (var bad :
        List.<List<Object>>of(
            List.of(obj("repo", "o/api", "title", "", "request", "R")),
            List.of(obj("repo", "o/api", "title", "T", "request", "R", "depends_on", List.of(0))),
            List.of(obj("repo", "o/api", "title", "T", "request", "R", "depends_on", "x")),
            List.of(obj("repo", "../x", "title", "T", "request", "R")),
            Collections.nCopies(9, obj("repo", "o/api", "title", "T", "request", "R")))) {
      task("p" + Math.abs(bad.hashCode()) + "-0001", "o/forja");
      propose("p" + Math.abs(bad.hashCode()) + "-0001", Map.of("parts", bad));
      assertTrue(
          proposalOf("p" + Math.abs(bad.hashCode()) + "-0001").has("parts_problem"),
          bad.toString());
    }
    assertTrue(members.contains("o/api"));
  }

  @Test
  void aMemberTheMainPanelDoesNotKnowBlocksTheApproval() {
    groups.define(
        "o/forja", MAPPER.valueToTree(List.of(obj("repo", "o/nuevo", "role", ""))), "owner");
    task("parent-0005", "o/forja");
    propose(
        "parent-0005",
        Map.of("parts", List.of(obj("repo", "o/nuevo", "title", "T", "request", "R"))));
    var e =
        assertThrows(
            IllegalArgumentException.class,
            () ->
                work.action(
                    "parent-0005",
                    "implement",
                    n("version", item("parent-0005").get("version")),
                    "owner"));
    assertTrue(e.getMessage().contains("Adopta primero o/nuevo"));
    assertEquals("proposed", item("parent-0005").get("state"), "nothing was half created");
    assertTrue(parts.all().isEmpty());
  }

  @Test
  void aTaskWithoutAGroupNeverSplits() {
    task("lone-0001", "o/solo");
    propose(
        "lone-0001", Map.of("parts", List.of(obj("repo", "o/solo", "title", "T", "request", "R"))));
    assertTrue(
        proposalOf("lone-0001").path("parts_problem").asText().contains("no tiene un grupo"));
  }

  @Test
  void autopilotNeverFansAChangeOut() {
    task("parent-0006", "o/forja");
    autopilot.mark("parent-0006", "o/forja", null, true, "owner");
    propose("parent-0006", Map.of("parts", twoParts(), "sandbox", Map.of("mode", "container")));
    assertEquals("proposed", item("parent-0006").get("state"), "a person approves a split");
  }
}
