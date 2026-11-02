package io.agentfarm;

import static io.agentfarm.core.Json.*;
import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.databind.JsonNode;
import io.agentfarm.core.*;
import java.nio.file.Path;
import java.time.Clock;
import java.util.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;

class DigestTest {
  @TempDir Path dir;
  Store store;
  WorkflowService work;
  EvidenceService evidence;
  DigestService digest;
  Clock clock = Clock.systemUTC();

  JsonNode n(Object... v) {
    return MAPPER.valueToTree(obj(v));
  }

  String id(Object claim) {
    return MAPPER.valueToTree(claim).path("command").path("id").asText();
  }

  String candidate(String request, String recipe) {
    String sha = (request.charAt(request.length() - 1) + "").repeat(40);
    var create =
        new LinkedHashMap<>(
            obj("request_id", request, "repository", "owner/repo", "title", request, "request", "Hazlo"));
    if (recipe != null) create.put("recipe", obj("id", recipe, "version", 1));
    work.create(MAPPER.valueToTree(create), "owner");
    work.report(
        id(work.claim("worker")),
        n(
            "state",
            "proposed",
            "proposal",
            obj(
                "summary", "s", "acceptance", "a", "as_is", "A", "to_be", "B", "base_sha", "b".repeat(40),
                "unit_test", "u", "integration_test", "i",
                "criteria", List.of(obj("id", "AC-1", "description", "d", "method", "manual")))),
        "worker");
    work.action(request, "implement", n("version", 1), "owner");
    work.report(
        id(work.claim("worker")),
        n("state", "review", "sha", sha, "pr", 1, "evidence", obj("sha", sha, "passed", true)),
        "worker");
    return sha;
  }

  void accept(String item) {
    EvidenceFixtures.accept(store, clock, item);
  }

  JsonNode ready() {
    return MAPPER.valueToTree(digest.digest()).path("ready");
  }

  @BeforeEach
  void setup() {
    store = new Store(dir.resolve("db").toString());
    work = new WorkflowService(store, clock);
    evidence = new EvidenceService(store, clock);
    digest = new DigestService(store, clock, work);
    new SchemaMigrations(store, work, new TelemetryService(store, clock), new NodeCredentials(store, clock));
  }

  @Test
  void onlyLowRiskRecipesWithEveryGatePassedCanBeBatched() {
    candidate("batch-low-1", "test-improvement");
    candidate("batch-free-2", null);
    candidate("batch-bug-3", "bugfix");
    assertEquals(0, ready().size(), "nothing is ready before its criteria are covered");
    accept("batch-low-1");
    accept("batch-free-2");
    accept("batch-bug-3");
    assertEquals(1, ready().size());
    assertEquals("batch-low-1", ready().get(0).path("id").asText());
  }

  @Test
  void aBatchMergesTheExactCommitAndRefusesTheRest() {
    String sha = candidate("batch-low-1", "dependency-update");
    candidate("batch-bug-2", "bugfix");
    accept("batch-low-1");
    accept("batch-bug-2");
    var results =
        MAPPER.valueToTree(
                digest.approve(
                    MAPPER.valueToTree(
                        List.of(
                            obj("id", "batch-low-1", "sha", sha),
                            obj("id", "batch-bug-2", "sha", "2".repeat(40)))),
                    "owner"))
            .path("results");
    assertEquals("integrating", results.get(0).path("state").asText());
    assertEquals("refused", results.get(1).path("state").asText());
    assertTrue(results.get(1).path("error").asText().contains("bajo riesgo"));
    var states = new HashMap<String, String>();
    work.items().forEach(w -> states.put(w.get("id").toString(), w.get("state").toString()));
    assertEquals("integrating", states.get("batch-low-1"));
    assertEquals("review", states.get("batch-bug-2"));
  }

  @Test
  void aStaleCommitIsNotApproved() {
    candidate("batch-low-1", "test-improvement");
    accept("batch-low-1");
    var results =
        MAPPER.valueToTree(
                digest.approve(
                    MAPPER.valueToTree(List.of(obj("id", "batch-low-1", "sha", "f".repeat(40)))), "owner"))
            .path("results");
    assertEquals("refused", results.get(0).path("state").asText());
  }

  @Test
  void batchSizeIsBounded() {
    assertThrows(IllegalArgumentException.class, () -> digest.approve(MAPPER.createArrayNode(), "owner"));
  }

  @Test
  void theWeekCountsWhatHappened() {
    candidate("batch-low-1", "test-improvement");
    var d = MAPPER.valueToTree(digest.digest());
    assertEquals(1, d.path("waiting").asInt());
    assertEquals(0, d.path("integrated").size());
    assertEquals(0, d.path("blocked").size());
  }

  @Test
  void theWeeklyMessageIsSentOnceAndNotForAQuietWeek() {
    digest.weekly();
    assertEquals(0, digests(), "nothing happened");
    candidate("batch-low-1", "test-improvement");
    digest.weekly();
    digest.weekly();
    assertEquals(1, digests());
  }

  int digests() {
    return store.tx(c -> Store.rows(c, "SELECT 1 FROM notifications WHERE state='digest'").size());
  }

  @Test
  void adoptingIsAnOrdinaryRecipe() {
    var recipe = MAPPER.valueToTree(RecipeCatalog.snapshot("adopt-repo", 1));
    assertTrue(recipe.path("research_steps").toString().contains("prueba"));
    assertTrue(recipe.path("restrictions").toString().contains("agentfarm.yaml"));
  }
}
