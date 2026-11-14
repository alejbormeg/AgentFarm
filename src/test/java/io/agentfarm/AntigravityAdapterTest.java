package io.agentfarm;

import static org.junit.jupiter.api.Assertions.*;

import io.agentfarm.adapters.AntigravityAdapter;
import io.agentfarm.core.AgentAdapters;
import io.agentfarm.core.Json;
import java.util.List;
import org.junit.jupiter.api.Test;

class AntigravityAdapterTest {
  static final String RESULT =
      "{\"event\":\"result\",\"result\":{\"conversation_id\":\"c-1\",\"status\":\"SUCCESS\",\"response\":\"hecho\\n\",\"duration_seconds\":3,\"num_turns\":1,"
          + "\"usage\":{\"input_tokens\":1000000,\"output_tokens\":200000,\"thinking_tokens\":150000,\"cache_read_tokens\":0,\"total_tokens\":1200000}}}";
  static final String STREAM =
      "{\"event\":\"init\",\"conversation_id\":\"c-1\",\"init\":{\"permission_mode\":\"request-review\"}}\n"
          + "{\"event\":\"step_update\",\"step_update\":{\"step_type\":\"agent_response\",\"state\":\"DONE\"}}\n"
          + RESULT
          + "\n";

  @Test
  void theImplementerAloneMayEditAndThePromptNeverTravelsAsAnArgument() {
    var research = AntigravityAdapter.command("agy", "research", "");
    assertFalse(research.contains("--mode"), "read roles keep edits and shell refused");
    assertFalse(research.contains("-p"));
    assertTrue(research.containsAll(List.of("--input-format", "stream-json", "--output-format")));
    var implement = AntigravityAdapter.command("agy", "implement", "");
    assertEquals("accept-edits", implement.get(implement.indexOf("--mode") + 1));
    assertFalse(String.join(" ", implement).contains("dangerously"));
    assertThrows(
        IllegalArgumentException.class, () -> AntigravityAdapter.command("agy", "root", ""));
    assertThrows(
        IllegalArgumentException.class,
        () -> AntigravityAdapter.command("agy", "review", "x; rm -rf /"));
    assertTrue(
        AntigravityAdapter.command("agy", "review", "gemini-3.8-flash-medium").contains("--model"));
  }

  @Test
  void theUserTurnIsOneJsonLine() {
    var line = Json.parse(AntigravityAdapter.userEvent("Hola \"mundo\"\ncon salto"));
    assertEquals("user", line.path("event").asText());
    assertEquals("Hola \"mundo\"\ncon salto", line.path("message").path("content").asText());
    assertFalse(AntigravityAdapter.userEvent("a\nb").contains("\n"), "NDJSON: a line per message");
  }

  @Test
  void theCostComesFromTheTokensAtTheOperatorsPrices() {
    var result = AntigravityAdapter.parse(STREAM, 1.25, 10.0);
    assertEquals("hecho\n", result.text());
    assertEquals("c-1", result.conversation());
    // 1.0M input at 1.25 USD + 0.2M output (thinking included) at 10 USD = 3.25 USD.
    assertEquals(3_250_000, result.costMicros());
    assertEquals(1_200_000, result.tokens());
  }

  @Test
  void anErrorOrMissingUsageIsNeverAccepted() {
    var failed =
        RESULT
            .replace("SUCCESS", "ERROR")
            .replace(
                "\"response\":\"hecho\\n\"",
                "\"response\":\"\",\"error\":\"authentication required\"");
    var error =
        assertThrows(
            AntigravityAdapter.Failed.class, () -> AntigravityAdapter.parse(failed, 1.25, 10));
    assertTrue(error.getMessage().contains("authentication required"));
    assertEquals(3_250_000L, error.costMicros, "a failed turn still settles what it used");
    var none =
        "{\"event\":\"result\",\"result\":{\"status\":\"ERROR\",\"error\":\"auth\",\"usage\":{\"total_tokens\":0}}}";
    assertNull(
        assertThrows(AntigravityAdapter.Failed.class, () -> AntigravityAdapter.parse(none, 1, 1))
            .costMicros);
    var noUsage = "{\"event\":\"result\",\"result\":{\"status\":\"SUCCESS\",\"response\":\"x\"}}";
    assertThrows(
        AntigravityAdapter.Failed.class,
        () -> AntigravityAdapter.parse(noUsage, 1, 1),
        "no token count");
    assertThrows(
        IllegalArgumentException.class, () -> AntigravityAdapter.parse("sin json\n", 1, 1));
  }

  @Test
  void refusedActionsAreReported() {
    var denied =
        RESULT.replace(
            "\"num_turns\":1,", "\"num_turns\":1,\"denied_actions\":[\"run_command(npm test)\"],");
    assertEquals(List.of("run_command(npm test)"), AntigravityAdapter.parse(denied, 1, 1).denied());
  }

  @Test
  void aBareEnvelopeAndBannerLinesAreAlsoAccepted() {
    String bare = RESULT.substring(RESULT.indexOf("\"result\":") + 9, RESULT.length() - 1);
    assertEquals("hecho\n", AntigravityAdapter.parse("Loaded.\n" + bare, 1, 1).text());
  }

  @Test
  void theProviderIsInTheCatalogAndNeedsTheCli() {
    assertTrue(AgentAdapters.catalog().stream().anyMatch(d -> d.get("id").equals("antigravity")));
    var adapter = AgentAdapters.get("antigravity");
    adapter.validateAuthentication(Json.parse("{\"version\":\"1.1.27\"}"));
    assertThrows(
        IllegalArgumentException.class, () -> adapter.validateAuthentication(Json.parse("{}")));
  }
}
