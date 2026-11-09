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

class EvidenceVerifierTest {
  @TempDir Path dir;
  Store store;
  WorkflowService work;
  EvidenceService evidence;
  EvidenceVerifier verifier;
  Clock clock = Clock.systemUTC();
  String sha = "a".repeat(40);
  String conclusion = "success", status = "completed";
  JsonNode pull = MAPPER.createObjectNode();
  List<String> files = new ArrayList<>(List.of("src/App.java", "src/AppTest.java"));

  JsonNode n(Object... v) {
    return MAPPER.valueToTree(obj(v));
  }

  JsonNode github(String path) {
    if (path.contains("/check-runs"))
      return n(
          "total_count",
          1,
          "check_runs",
          List.of(obj("name", "AgentFarm unit", "status", status, "conclusion", conclusion)));
    if (path.contains("/status")) return n("total_count", 0, "state", "pending");
    if (path.startsWith("/pulls/")) return pull;
    if (path.contains("/compare/")) {
      var list = new ArrayList<Object>();
      for (String file : files) list.add(obj("filename", file));
      return n("files", list);
    }
    throw new IllegalArgumentException(path);
  }

  Map<String, Object> report(String kind, String command, boolean container) {
    var r = obj("kind", kind, "status", "passed", "command", command);
    if (container) r.put("sandbox", obj("mode", "container"));
    return r;
  }

  void candidate(Map<String, Object> reportUnit) {
    work.create(
        n(
            "request_id",
            "verify-task",
            "repository",
            "owner/repo",
            "title",
            "Fix",
            "request",
            "Fix"),
        "owner");
    work.report(
        id(work.claim("worker")),
        n(
            "state",
            "proposed",
            "proposal",
            obj(
                "summary",
                "Fix",
                "acceptance",
                "Works",
                "as_is",
                "A-->B",
                "to_be",
                "A-->C",
                "base_sha",
                "b".repeat(40),
                "unit_test",
                "mvn test",
                "integration_test",
                "mvn verify",
                "criteria",
                List.of(
                    obj(
                        "id",
                        "AC-1",
                        "description",
                        "Works",
                        "method",
                        "test",
                        "checks",
                        List.of("unit"))))),
        "worker");
    work.action("verify-task", "implement", n("version", 1), "owner");
    work.report(
        id(work.claim("worker")),
        n(
            "state",
            "review",
            "sha",
            sha,
            "pr",
            1,
            "evidence",
            obj(
                "sha",
                sha,
                "passed",
                true,
                "ci_checks",
                List.of("AgentFarm unit"),
                "unit",
                reportUnit,
                "integration",
                report("integration", "mvn verify", true))),
        "worker");
  }

  String id(Object claim) {
    return MAPPER.valueToTree(claim).path("command").path("id").asText();
  }

  JsonNode dossier() {
    return MAPPER.valueToTree(evidence.get("verify-task"));
  }

  @BeforeEach
  void setup() {
    store = new Store(dir.resolve("db").toString());
    work = new WorkflowService(store, clock);
    evidence = new EvidenceService(store, clock);
    verifier = new EvidenceVerifier(store, clock, (repo, path) -> github(path), () -> true);
  }

  @Test
  void anExecutorReportAloneNeverClosesATestCriterion() {
    candidate(report("unit", "mvn test", true));
    assertEquals("missing", dossier().path("criteria").get(0).path("status").asText());
    assertFalse(dossier().path("ready").asBoolean());
    assertEquals("missing", dossier().path("verification").path("state").asText());
  }

  @Test
  void passingCiClosesTheCriterionWithoutAHumanException() {
    candidate(report("unit", "mvn test", true));
    verifier.verify("verify-task");
    var d = dossier();
    assertEquals("verified", d.path("criteria").get(0).path("status").asText());
    assertTrue(d.path("ready").asBoolean(), d.toString());
    assertEquals("verified", d.path("acceptance_state").asText());
    assertEquals(0, d.path("exceptions").asInt());
    assertDoesNotThrow(() -> work.action("verify-task", "merge", n("sha", sha), "owner"));
  }

  @Test
  void aMergeMadeOutsideThePanelClosesTheTaskAndSaysSo() {
    candidate(report("unit", "mvn test", true));
    verifier.reconcile("verify-task");
    assertEquals("review", work.items().get(0).get("state"), "an open PR stays in review");
    pull = n("merged", true);
    verifier.reconcile("verify-task");
    var item = work.items().get(0);
    assertEquals("integrated", item.get("state"));
    assertTrue(item.get("error").toString().contains("fuera del panel"));
  }

  @Test
  void aConflictBlocksIntegrationOnlyWhenGitHubConfirmsIt() {
    candidate(report("unit", "mvn test", true));
    verifier.verify("verify-task");
    pull = n("merged", false, "mergeable", null);
    verifier.reconcile("verify-task");
    assertTrue(dossier().path("ready").asBoolean(), "GitHub still computing");
    pull = n("merged", false, "mergeable", false);
    verifier.reconcile("verify-task");
    assertTrue(dossier().path("blockers").toString().contains("conflictos"), dossier().toString());
    assertFalse(dossier().path("ready").asBoolean());
    pull = n("merged", false, "mergeable", true);
    verifier.reconcile("verify-task");
    assertTrue(dossier().path("ready").asBoolean());
  }

  @Test
  void anotherTaskChangingTheSameFilesIsReported() throws Exception {
    candidate(report("unit", "mvn test", true));
    verifier.verify("verify-task");
    assertEquals(0, dossier().path("overlaps").size());
    store.tx(c -> {
      Store.exec(
          c,
          "INSERT INTO work_items(id,repo,title,request,state,version,proposal,evidence,pr,sha,created)"
              + " SELECT 'other',repo,'Otra','x','review',1,'{}','{}',9,?,1 FROM work_items WHERE id='verify-task'",
          sha);
      Store.exec(
          c,
          "INSERT INTO evidence_verifications(item,basis,sha,state,reason,detail,at)"
              + " VALUES('other','b',?,'success','ok',?,1)",
          sha,
          "{\"files\":[\"src/App.java\",\"docs/x.md\"]}");
      return null;
    });
    var overlap = dossier().path("overlaps").get(0);
    assertEquals("other", overlap.path("item").asText());
    assertEquals("[\"src/App.java\"]", overlap.path("files").toString());
  }

  @Test
  void ciStillRunningOrFailingKeepsItOpen() {
    candidate(report("unit", "mvn test", true));
    status = "in_progress";
    conclusion = "";
    verifier.verify("verify-task");
    assertEquals("pending", dossier().path("verification").path("state").asText());
    assertFalse(dossier().path("ready").asBoolean());
    status = "completed";
    conclusion = "failure";
    verifier.verify("verify-task");
    assertEquals("failed", dossier().path("verification").path("state").asText());
    assertTrue(dossier().path("verification").path("reason").asText().contains("AgentFarm unit"));
    assertFalse(dossier().path("ready").asBoolean());
    conclusion = "success";
    verifier.verify("verify-task");
    assertTrue(dossier().path("ready").asBoolean(), "a rerun can still pass");
  }

  @Test
  void onlyTheApprovedCommandInTheContainerCounts() {
    candidate(report("unit", "true", true));
    verifier.verify("verify-task");
    assertEquals("missing", dossier().path("criteria").get(0).path("status").asText());
  }

  @Test
  void testsOnTheHostDoNotCount() {
    candidate(report("unit", "mvn test", false));
    verifier.verify("verify-task");
    assertEquals("missing", dossier().path("criteria").get(0).path("status").asText());
  }

  @Test
  void aHumanRejectionOutranksPassingCi() {
    candidate(report("unit", "mvn test", true));
    verifier.verify("verify-task");
    var d = dossier();
    evidence.decide(
        "verify-task",
        n(
            "request_id",
            UUID.randomUUID().toString(),
            "version",
            d.path("version").asInt(),
            "sha",
            sha,
            "basis",
            d.path("basis").asText(),
            "criterion",
            "AC-1",
            "kind",
            "reject",
            "reason",
            "The test passes but it does not cover the reported case",
            "environment",
            "Reading the diff"),
        "owner");
    assertEquals("reject", dossier().path("criteria").get(0).path("status").asText());
    assertFalse(dossier().path("ready").asBoolean());
  }

  @Test
  void aNewCandidateInvalidatesTheVerification() {
    candidate(report("unit", "mvn test", true));
    verifier.verify("verify-task");
    store.tx(c -> {
      Store.exec(c, "UPDATE work_items SET sha=? WHERE id='verify-task'", "c".repeat(40));
      return null;
    });
    assertEquals("missing", dossier().path("verification").path("state").asText());
    assertFalse(dossier().path("ready").asBoolean());
  }

  @Test
  void anUnreachableGitHubIsReportedAndClosesNothing() {
    candidate(report("unit", "mvn test", true));
    new EvidenceVerifier(
            store,
            clock,
            (repo, path) -> {
              throw new IllegalStateException("GitHub HTTP 401 for " + path);
            },
            () -> true)
        .verify("verify-task");
    assertEquals("unavailable", dossier().path("verification").path("state").asText());
    assertFalse(dossier().path("ready").asBoolean());
  }

  @Test
  void withoutCredentialsItStaysIdle() {
    candidate(report("unit", "mvn test", true));
    new EvidenceVerifier(
            store,
            clock,
            (repo, path) -> {
              throw new AssertionError("must not call GitHub");
            },
            () -> false)
        .tick();
    assertEquals("missing", dossier().path("verification").path("state").asText());
  }

  @Test
  void aBrokenCommandmentBlocksUntilItsExceptionIsSigned() {
    candidate(report("unit", "mvn test", true));
    store.tx(c -> {
      var evidence =
          (com.fasterxml.jackson.databind.node.ObjectNode)
              parse(
                  Store.one(c, "SELECT evidence FROM work_items WHERE id='verify-task'")
                      .get("evidence")
                      .toString());
      evidence.set("regression", n("status", "not_proved", "reason", "Ya pasaban"));
      Store.exec(c, "UPDATE work_items SET evidence=? WHERE id='verify-task'", encode(evidence));
      return null;
    });
    verifier.verify("verify-task");
    var d = dossier();
    assertFalse(d.path("ready").asBoolean());
    assertTrue(d.path("blockers").toString().contains("CM-2"), d.path("blockers").toString());
    var request =
        n(
            "request_id",
            UUID.randomUUID().toString(),
            "version",
            d.path("version").asInt(),
            "sha",
            sha,
            "basis",
            d.path("basis").asText(),
            "criterion",
            "CM-2",
            "kind",
            "exception",
            "reason",
            "Este fallo depende de una carrera que no se reproduce en una prueba",
            "environment",
            "Revisión del diff");
    evidence.decide("verify-task", request, "owner");
    d = dossier();
    assertTrue(d.path("ready").asBoolean(), d.path("blockers").toString());
    assertEquals("accepted_with_exceptions", d.path("acceptance_state").asText());
    assertEquals("exception", d.path("commandments").get(1).path("status").asText());
    var reject = ((com.fasterxml.jackson.databind.node.ObjectNode) request.deepCopy());
    reject.put("request_id", UUID.randomUUID().toString()).put("kind", "reject");
    assertThrows(
        IllegalArgumentException.class, () -> evidence.decide("verify-task", reject, "owner"));
    var other = ((com.fasterxml.jackson.databind.node.ObjectNode) request.deepCopy());
    other.put("request_id", UUID.randomUUID().toString()).put("criterion", "CM-6");
    assertThrows(
        IllegalArgumentException.class,
        () -> evidence.decide("verify-task", other, "owner"),
        "no exception can excuse a secret");
  }

  @Test
  void aSecretInTheDiffFailsTheVerification() {
    var compare =
        n("files", List.of(obj("filename", "app.env", "patch", "+KEY=ghp_" + "a".repeat(36))));
    var result = EvidenceVerifier.assess(reportWith("passed"), runs(), github("/status"), compare);
    assertEquals("failed", result.state());
    assertFalse(Json.encode(result.detail()).contains("a".repeat(36)));
  }

  JsonNode reportWith(String unitStatus) {
    return n(
        "ci_checks",
        List.of("*"),
        "unit",
        obj("status", unitStatus),
        "integration",
        obj("status", "passed"));
  }

  JsonNode runs() {
    return github("/check-runs");
  }

  JsonNode compared(String... names) {
    var list = new ArrayList<Object>();
    for (String f : names) list.add(obj("filename", f));
    return n("files", list);
  }

  @Test
  void aChangeGitHubDoesNotShowIsNotCalledClean() {
    var compare =
        n(
            "files",
            List.of(
                obj("filename", "big.sql", "status", "added", "changes", 90000),
                obj("filename", "empty.py", "status", "added", "changes", 0),
                obj("filename", "gone.txt", "status", "removed", "changes", 12)));
    var result = EvidenceVerifier.assess(reportWith("passed"), runs(), github("/status"), compare);
    assertEquals("failed", result.state());
    assertTrue(result.reason().contains("big.sql"), result.reason());
    assertFalse(result.reason().contains("empty.py"), "an empty file has nothing to hide");
    assertFalse(result.reason().contains("gone.txt"), "a deletion adds nothing");
  }

  @Test
  void aRenameReportsTheFileItTakesAway() {
    var compare =
        n(
            "files",
            List.of(
                obj(
                    "filename",
                    "docs/guard.txt",
                    "status",
                    "renamed",
                    "previous_filename",
                    "private/guard.txt")));
    var result = EvidenceVerifier.assess(reportWith("passed"), runs(), github("/status"), compare);
    assertEquals(
        List.of("private/guard.txt", "docs/guard.txt"),
        result.detail().get("files"),
        "the scope verdict sees the deletion outside docs/");
  }

  @Test
  void assessRejectsChangesToTheProjectsOwnCi() {
    var result =
        EvidenceVerifier.assess(
            reportWith("passed"),
            runs(),
            github("/status"),
            compared(".github/workflows/ci.yml", "src/App.java"));
    assertEquals("failed", result.state());
    assertTrue(result.reason().contains("ci.yml"));
    var templateOnly =
        n(
            "ci_checks",
            List.of("AgentFarm unit"),
            "unit",
            obj("status", "passed"),
            "integration",
            obj("status", "passed"));
    assertEquals(
        "success",
        EvidenceVerifier.assess(
                templateOnly,
                runs(),
                github("/status"),
                compared(".github/workflows/agentfarm.yml", "src/App.java"))
            .state(),
        "without CI of its own, the platform writes its template");
    var ownCi =
        EvidenceVerifier.assess(
            reportWith("passed"),
            runs(),
            github("/status"),
            compared(".github/workflows/agentfarm.yml", "src/App.java"));
    assertEquals("failed", ownCi.state(), "with CI of its own, the template is not the platform's");
    assertTrue(ownCi.reason().contains("agentfarm.yml"), ownCi.reason());
  }

  @Test
  void assessRejectsCodeWithAnNaTestButNotDocumentation() {
    assertEquals(
        "failed",
        EvidenceVerifier.assess(
                reportWith("not_applicable"), runs(), github("/status"), compared("src/App.java"))
            .state());
    assertEquals(
        "success",
        EvidenceVerifier.assess(
                reportWith("not_applicable"), runs(), github("/status"), compared("docs/guide.md"))
            .state());
  }

  @Test
  void assessNeedsLinkedChecksAndEveryExpectedOne() {
    assertEquals(
        "failed", EvidenceVerifier.assess(n(), runs(), github("/status"), compared()).state());
    assertEquals(
        "failed",
        EvidenceVerifier.assess(
                n("ci_checks", List.of("AgentFarm integration")),
                runs(),
                github("/status"),
                compared())
            .state());
    assertEquals(
        "pending",
        EvidenceVerifier.assess(
                n("ci_checks", List.of("*")), n("total_count", 0), github("/status"), compared())
            .state());
  }

  @Test
  void aCiThatNeverStartsExplainsWhyButLetsNothingThrough() {
    var linked = n("ci_checks", List.of("*"));
    var none = n("total_count", 0);
    var waiting = EvidenceVerifier.assess(linked, none, github("/status"), compared());
    var quiet = EvidenceVerifier.explainDelay(waiting, 0);
    assertEquals("La CI aún no ha empezado.", quiet.reason());
    assertNull(quiet.detail().get("stalled"));
    var late = EvidenceVerifier.explainDelay(waiting, 720);
    assertEquals("pending", late.state(), "the wait is explained, never excused");
    assertEquals(true, late.detail().get("stalled"));
    assertTrue(late.reason().contains("12 min"), late.reason());
    assertTrue(late.reason().contains("Actions esté desactivado"), late.reason());
  }

  @Test
  void aQueuedCiBlamesTheRunnerAndARunningOneKeepsWaiting() {
    var linked = n("ci_checks", List.of("*"));
    status = "queued";
    conclusion = null;
    var queued =
        EvidenceVerifier.explainDelay(
            EvidenceVerifier.assess(linked, github("/check-runs"), github("/status"), compared()),
            900);
    assertEquals("pending", queued.state());
    assertEquals(true, queued.detail().get("stalled"));
    assertTrue(queued.reason().contains("15 min en cola"), queued.reason());
    assertTrue(queued.reason().contains("runner"), queued.reason());
    status = "in_progress";
    var running =
        EvidenceVerifier.explainDelay(
            EvidenceVerifier.assess(linked, github("/check-runs"), github("/status"), compared()),
            900);
    assertEquals("La CI sigue en marcha.", running.reason(), "a long test run is not stuck");
    assertNull(running.detail().get("stalled"));
  }

  @Test
  void verifyCountsTheWaitFromTheMomentTheCandidateArrived() throws Exception {
    // state_since comes from a migration, so this test needs them on its database.
    new SchemaMigrations(
        store, work, new TelemetryService(store, clock), new NodeCredentials(store, clock));
    candidate(report("unit", "mvn test", true));
    var always =
        new EvidenceVerifier(
            store,
            clock,
            (repo, path) -> {
              if (path.contains("/check-runs")) return n("total_count", 0);
              return github(path);
            },
            () -> true,
            600);
    always.verify("verify-task");
    assertEquals(
        "La CI aún no ha empezado.", dossier().path("verification").path("reason").asText());
    var later = Clock.offset(clock, java.time.Duration.ofMinutes(11));
    new EvidenceVerifier(
            store,
            later,
            (repo, path) -> {
              if (path.contains("/check-runs")) return n("total_count", 0);
              return github(path);
            },
            () -> true,
            600)
        .verify("verify-task");
    var verification = dossier().path("verification");
    assertEquals("pending", verification.path("state").asText());
    assertTrue(verification.path("detail").path("stalled").asBoolean());
    assertFalse(dossier().path("ready").asBoolean(), "explaining the wait doesn't open the gate");
  }

  @Test
  void testPolicyOnlyExcusesDocumentation() {
    assertTrue(TestPolicy.untestedCode(List.of("src/App.java"), "not_applicable", "passed"));
    assertTrue(TestPolicy.untestedCode(List.of("README.md", "app.py"), "passed", "not_applicable"));
    assertFalse(
        TestPolicy.untestedCode(
            List.of("README.md", "docs/x.png"), "not_applicable", "not_applicable"));
    assertFalse(TestPolicy.untestedCode(List.of("src/App.java"), "passed", "passed"));
    assertFalse(
        TestPolicy.untestedCode(List.of("agentfarm-memory.json"), "not_applicable", "passed"));
  }

  @Test
  void aTestCriterionCannotRelyOnAnNaCommand() {
    work.create(
        n(
            "request_id",
            "na-task-0001",
            "repository",
            "owner/repo",
            "title",
            "Fix",
            "request",
            "Fix"),
        "owner");
    assertThrows(
        IllegalArgumentException.class,
        () ->
            work.report(
                id(work.claim("worker")),
                n(
                    "state",
                    "proposed",
                    "proposal",
                    obj(
                        "summary",
                        "Fix",
                        "acceptance",
                        "Works",
                        "as_is",
                        "A",
                        "to_be",
                        "B",
                        "base_sha",
                        "b".repeat(40),
                        "unit_test",
                        "N/A: no hay nada que probar",
                        "integration_test",
                        "mvn verify",
                        "criteria",
                        List.of(
                            obj(
                                "id",
                                "AC-1",
                                "description",
                                "Works",
                                "method",
                                "test",
                                "checks",
                                List.of("unit"))))),
                "worker"));
  }
}
