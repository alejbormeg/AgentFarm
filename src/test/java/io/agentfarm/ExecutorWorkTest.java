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
    String cleaned = ExecutorWork.clean("Authorization: Bearer abc.def ghp_abcdefgh sk-abcdefghijklmnop fin");
    assertFalse(cleaned.contains("abc.def") || cleaned.contains("ghp_") || cleaned.contains("sk-abc"), cleaned);
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
    assertEquals("agentfarm/7-a-adir-saludo", ExecutorWork.branchName(Json.parse("{\"title\":\"Añadir saludo!\"}"), 7));
    String longest = ExecutorWork.branchName(Json.parse("{\"title\":\"" + "x".repeat(300) + "\"}"), 7);
    assertEquals(100, longest.length());
    assertEquals(
        "agentfarm/existente",
        ExecutorWork.branchName(Json.parse("{\"title\":\"Otro\",\"branch\":\"agentfarm/existente\"}"), 7),
        "a retry finds its own work");
    assertEquals(
        "agentfarm/7-otro", ExecutorWork.branchName(Json.parse("{\"title\":\"Otro\",\"branch\":\"  \"}"), 7));
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
    var quality = Map.<String, Object>of("quality", Map.of("checks", List.of(Map.of("status", "passed"))));
    var plain = Json.parse("{\"summary\":\"s\"}");
    assertTrue(ExecutorWork.withCandidateGates(plain, quality).has("quality"));
    assertFalse(plain.has("quality"), "the approved proposal is not modified");
    var approved = Json.parse("{\"profile_hash\":\"h\"}");
    assertSame(approved, ExecutorWork.withCandidateGates(approved, quality), "an approved profile governs");
    assertSame(plain, ExecutorWork.withCandidateGates(plain, Map.of()));
  }

  @Test
  void aFailedGateIsFoundAtAnyDepth() {
    assertTrue(ExecutorWork.anyFailed(Map.of("checks", List.of(Map.of("status", "passed"), Map.of("status", "failed")))));
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
    var process = new ProcessBuilder("bash", "-c", "printf '%s' " + ExecutorWork.shellQuote(tricky)).start();
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
        List.of("Pide esto", "no concede aprobación", "Resumen", "Criterio", "Unitarias: u", "Integración: i", "flowchart LR; B", "no una orden"))
      assertTrue(body.contains(part), part);
    assertEquals("", ExecutorWork.memoryContext(Json.parse("{}").path("project_memory")));
    assertEquals("", ExecutorWork.recipeContext(Json.parse("{}")));
  }
}
