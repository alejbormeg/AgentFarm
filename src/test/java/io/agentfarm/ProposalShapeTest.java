package io.agentfarm;

import static org.junit.jupiter.api.Assertions.*;

import io.agentfarm.core.*;
import org.junit.jupiter.api.Test;

class ProposalShapeTest {
  @Test
  void checkShapesFromRealAgentsAreNormalizedWithoutInventingAnything() {
    // Shape returned by Codex in the first real pilot (checks as an object of commands).
    var p =
        Json.parse(
            "{\"criteria\":["
                + "{\"id\":\"AC-1\",\"method\":\"test\",\"checks\":{\"unit\":\"python3 -m unittest\"}},"
                + "{\"id\":\"AC-2\",\"method\":\"test\",\"checks\":\"integration\"},"
                + "{\"id\":\"AC-3\",\"method\":\"test\",\"checks\":[\"Unit\",\"unit\",\"integration\"]},"
                + "{\"id\":\"AC-4\",\"method\":\"test\",\"checks\":{\"e2e\":\"x\"}},"
                + "{\"id\":\"AC-5\",\"method\":\"manual\",\"checks\":{\"unit\":\"x\"}}]}");
    ProposalShape.normalize(p);
    var c = p.path("criteria");
    assertEquals("[\"unit\"]", c.get(0).path("checks").toString());
    assertEquals("[\"integration\"]", c.get(1).path("checks").toString());
    assertEquals("[\"unit\",\"integration\"]", c.get(2).path("checks").toString());
    assertTrue(
        c.get(3).path("checks").isObject(), "unknown checks are left for the panel to reject");
    assertTrue(c.get(4).path("checks").isObject(), "manual criteria untouched");
  }

  @Test
  void changesListOnlyWhatAnOperatorApproves() {
    var before =
        Json.parse("{\"summary\":\"a\",\"allowed_paths\":[\"src/\"],\"prompt\":{\"id\":\"x\"}}");
    var after =
        Json.parse("{\"summary\":\"a\",\"allowed_paths\":[\"lib/\"],\"prompt\":{\"id\":\"y\"}}");
    var changes = ProposalShape.changes(before, after);
    assertEquals(1, changes.size());
    assertEquals("allowed_paths", changes.get(0).path("field").asText());
    assertEquals("[\"src/\"]", changes.get(0).path("before").toString());
    assertEquals("[\"lib/\"]", changes.get(0).path("after").toString());
    assertEquals(
        0,
        ProposalShape.changes(Json.parse("{}"), after).size(),
        "a first version has nothing to compare");
  }

  @Test
  void researchPromptShowsTheChecksShape() {
    assertTrue(
        Prompts.template(Prompts.CURRENT.get("research")).contains("\"checks\": [\"unit\"]"));
  }

  static com.fasterxml.jackson.databind.JsonNode proposal(String description, String unit) {
    return io.agentfarm.core.Json.parse(
        io.agentfarm.core.Json.encode(
            java.util.Map.of(
                "unit_test",
                unit,
                "integration_test",
                "bash tests/integration.sh",
                "criteria",
                java.util.List.of(
                    java.util.Map.of(
                        "id",
                        "AC-1",
                        "description",
                        description,
                        "method",
                        "test",
                        "checks",
                        java.util.List.of("unit"))))));
  }

  @Test
  void warnsAboutEvidenceTheTestsCannotShow() {
    for (String description :
        java.util.List.of(
            "El contenedor corre con la identidad del binario esperada",
            "El servicio se ejecuta como root",
            "Se comprueba manualmente que funciona",
            "Funciona en producción",
            "REVISIÓN HUMANA del resultado")) {
      var doubts = ProposalShape.doubtfulCriteria(proposal(description, "bash tests/unit.sh"));
      assertEquals(1, doubts.size(), description);
      assertEquals("AC-1", doubts.get(0).path("id").asText());
    }
  }

  @Test
  void warnsAboutACommandThatProvesNothing() {
    var doubts = ProposalShape.doubtfulCriteria(proposal("El saludo existe", "true"));
    assertEquals(1, doubts.size());
    assertTrue(doubts.get(0).path("reason").asText().contains("siempre pasa"));
  }

  @Test
  void leavesOrdinaryAndManualCriteriaAlone() {
    assertEquals(
        0,
        ProposalShape.doubtfulCriteria(
                proposal("greeting.txt contiene hola", "test -f greeting.txt"))
            .size());
    assertEquals(
        0,
        ProposalShape.doubtfulCriteria(
                proposal("La guía explica cómo instalar a la primera", "bash tests/unit.sh"))
            .size());
    var manual =
        io.agentfarm.core.Json.parse(
            "{\"criteria\":[{\"id\":\"AC-1\",\"description\":\"Se revisa manualmente\",\"method\":\"manual\"}]}");
    assertEquals(
        0, ProposalShape.doubtfulCriteria(manual).size(), "a person can check a manual criterion");
    assertEquals(0, ProposalShape.doubtfulCriteria(io.agentfarm.core.Json.parse("{}")).size());
  }
}
