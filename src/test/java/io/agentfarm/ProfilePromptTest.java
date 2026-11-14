package io.agentfarm;

import static org.junit.jupiter.api.Assertions.*;

import io.agentfarm.adapters.ProfilePrompt;
import io.agentfarm.core.Json;
import org.junit.jupiter.api.Test;

class ProfilePromptTest {
  @Test
  void aSystemMessageAndASkillReachOnlyTheirRoles() {
    var profile =
        ProfilePrompt.sanitize(
            Json.parse(
                "{\"roles\":{\"implement\":{\"system\":\"Usa tabs\"}},\"skills\":[{\"id\":\"casa\",\"title\":\"Casa\",\"roles\":[\"review\"],\"text\":\"Revisa nombres\"}]}"));
    var implement = ProfilePrompt.section(profile, "implement");
    assertTrue(implement.text().contains("Usa tabs"));
    assertTrue(implement.text().contains("prevalecen"), "the approved scope comes first");
    assertFalse(implement.text().contains("Revisa nombres"));
    var review = ProfilePrompt.section(profile, "review");
    assertTrue(review.text().contains("Revisa nombres"));
    assertEquals("casa", review.references().get(0).get("id"));
    assertEquals(
        "", ProfilePrompt.section(profile, "research").text(), "nothing for an unconfigured role");
    assertEquals("", ProfilePrompt.section(Json.parse("{}"), "review").text());
  }

  @Test
  void theDefaultIsNotStoredAndRestoresWhenEmpty() {
    var profile =
        ProfilePrompt.sanitize(
            Json.parse(
                "{\"roles\":{\"review\":{\"system\":\""
                    + ProfilePrompt.DEFAULTS.get("review")
                    + "\"}}}"));
    assertFalse(profile.path("roles").has("review"));
    assertEquals(
        ProfilePrompt.DEFAULTS.get("research"),
        ProfilePrompt.forEditing(profile).path("roles").path("research").path("system").asText());
  }

  @Test
  void badSkillsAreRefusedWithAReason() {
    for (String bad :
        new String[] {
          "{\"skills\":[{\"id\":\"../x\",\"text\":\"a\"}]}",
          "{\"skills\":[{\"id\":\"a\",\"text\":\"\"}]}",
          "{\"skills\":[{\"id\":\"a\",\"text\":\"x\"},{\"id\":\"a\",\"text\":\"y\"}]}",
          "{\"skills\":[{\"id\":\"a\",\"text\":\"" + "x".repeat(40000) + "\"}]}",
        })
      assertThrows(
          IllegalArgumentException.class,
          () -> ProfilePrompt.sanitize(Json.parse(bad)),
          bad.substring(0, 30));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            ProfilePrompt.sanitize(
                Json.parse("{\"roles\":{\"review\":{\"system\":\"" + "x".repeat(9000) + "\"}}}")));
  }

  @Test
  void theProfileExportsForClaudeChatGptAndGemini() {
    var profile =
        ProfilePrompt.sanitize(
            Json.parse("{\"skills\":[{\"id\":\"casa\",\"title\":\"Casa\",\"text\":\"Texto\"}]}"));
    for (String[] f :
        new String[][] {{"claude", "CLAUDE.md"}, {"codex", "AGENTS.md"}, {"gemini", "GEMINI.md"}}) {
      String out = ProfilePrompt.export(profile, f[0]);
      assertTrue(out.startsWith("# " + f[1]));
      assertTrue(
          out.contains("Napoleon") && out.contains("Benjamin") && out.contains("Skill: Casa"));
    }
    assertThrows(IllegalArgumentException.class, () -> ProfilePrompt.export(profile, "otro"));
  }
}
