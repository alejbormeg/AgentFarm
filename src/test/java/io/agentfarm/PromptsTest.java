package io.agentfarm;

import static org.junit.jupiter.api.Assertions.*;

import io.agentfarm.core.Json;
import io.agentfarm.core.Prompts;
import java.util.Map;
import org.junit.jupiter.api.Test;

class PromptsTest {
  @Test
  void everyRoleRendersWithItsTemplateHash() {
    var research =
        Prompts.render(
            "research",
            Map.of("repo", "o/r", "sandbox", "imagen x", "request", "Arregla {{repo}}", "feedback", ""));
    assertEquals("research.v2", research.id());
    assertEquals(Json.sha256(Prompts.template("research.v2")), research.hash());
    assertTrue(research.text().contains("Arregla {{repo}}"), "values are not re-expanded");
    assertFalse(research.text().contains("{{sandbox}}"));
    assertDoesNotThrow(
        () -> Prompts.render("implement", Map.of("ci", "x", "proposal", "{}", "request", "r")));
    assertDoesNotThrow(
        () ->
            Prompts.render(
                "review", Map.of("base", "a", "sha", "b", "proposal", "{}", "tests", "t")));
  }

  @Test
  void missingOrUnusedValuesAndUnknownTemplatesFail() {
    assertThrows(IllegalArgumentException.class, () -> Prompts.render("review", Map.of("base", "a")));
    assertThrows(
        IllegalArgumentException.class,
        () -> Prompts.render("implement", Map.of("ci", "x", "proposal", "{}", "request", "r", "extra", "y")));
    assertThrows(IllegalArgumentException.class, () -> Prompts.template("../secret"));
    assertThrows(IllegalArgumentException.class, () -> Prompts.render("deploy", Map.of()));
  }
}
