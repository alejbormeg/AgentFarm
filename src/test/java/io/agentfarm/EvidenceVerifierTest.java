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
        n("request_id", "verify-task", "repository", "owner/repo", "title", "Fix", "request", "Fix"),
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
    store.tx(
        c -> {
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
  void assessRejectsChangesToTheProjectsOwnCi() {
    var result =
        EvidenceVerifier.assess(
            reportWith("passed"),
            runs(),
            github("/status"),
            compared(".github/workflows/ci.yml", "src/App.java"));
    assertEquals("failed", result.state());
    assertTrue(result.reason().contains("ci.yml"));
    assertEquals(
        "success",
        EvidenceVerifier.assess(
                reportWith("passed"),
                runs(),
                github("/status"),
                compared(".github/workflows/agentfarm.yml", "src/App.java"))
            .state());
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
        "failed",
        EvidenceVerifier.assess(n(), runs(), github("/status"), compared()).state());
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
  void testPolicyOnlyExcusesDocumentation() {
    assertTrue(TestPolicy.untestedCode(List.of("src/App.java"), "not_applicable", "passed"));
    assertTrue(TestPolicy.untestedCode(List.of("README.md", "app.py"), "passed", "not_applicable"));
    assertFalse(TestPolicy.untestedCode(List.of("README.md", "docs/x.png"), "not_applicable", "not_applicable"));
    assertFalse(TestPolicy.untestedCode(List.of("src/App.java"), "passed", "passed"));
    assertFalse(TestPolicy.untestedCode(List.of("agentfarm-memory.json"), "not_applicable", "passed"));
  }

  @Test
  void aTestCriterionCannotRelyOnAnNaCommand() {
    work.create(
        n("request_id", "na-task-0001", "repository", "owner/repo", "title", "Fix", "request", "Fix"),
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
