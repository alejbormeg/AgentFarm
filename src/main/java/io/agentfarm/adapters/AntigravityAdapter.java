package io.agentfarm.adapters;

import static io.agentfarm.core.Json.*;

import com.fasterxml.jackson.databind.JsonNode;
import io.agentfarm.core.AgentAdapter;
import java.time.Instant;
import java.util.*;

/**
 * Antigravity CLI ({@code agy}) in headless streaming mode: one prompt on standard input as a JSON
 * event, NDJSON back with a final {@code result} event that carries the token usage. It signs in
 * with the Google account of the machine (no API key). Each turn is charged to a Clover API account
 * in USD micros, from the token counts at prices the operator sets: a spending guard, not the real
 * bill. There is no system sandbox: tools that need approval are refused without a person (so no
 * shell command runs), and edits are accepted only for the implementer.
 */
public final class AntigravityAdapter implements AgentAdapter {
  public record Result(
      String text, long costMicros, String conversation, long tokens, List<String> denied) {}

  public String id() {
    return "antigravity";
  }

  public Map<String, Object> descriptor() {
    return obj(
        "id",
        id(),
        "state",
        "implemented",
        "authentication",
        "google-account-login",
        "execution",
        "agy-stream-json",
        "events",
        false,
        "interrupt",
        true,
        "resume",
        false,
        "quota",
        "api-cost-estimated",
        "artifacts",
        "local-executor-reports",
        "independent_verification",
        true,
        "additional_payment",
        false);
  }

  /** {@code response} is the executor's local probe: the CLI version. */
  public void validateAuthentication(JsonNode response) {
    if (response.path("version").asText().isBlank())
      throw new IllegalArgumentException("Antigravity CLI not found");
  }

  public JsonNode normalizeQuota(JsonNode result, Instant now) {
    return MAPPER.valueToTree(
        obj(
            "provider",
            id(),
            "unit",
            "usd_micros",
            "source",
            "estimate",
            "used",
            result.path("cost_micros").asLong(0),
            "observed_at",
            now.toString()));
  }

  /**
   * Stream mode on both sides, so the prompt never travels as an argument. Edits are accepted for
   * the implementer only; everything else that asks for approval is refused in headless mode.
   */
  public static List<String> command(String binary, String role, String model) {
    if (!Set.of("research", "implement", "review").contains(role))
      throw new IllegalArgumentException("Unknown Antigravity role");
    var argv =
        new ArrayList<>(
            List.of(binary, "--input-format", "stream-json", "--output-format", "stream-json"));
    if (role.equals("implement")) argv.addAll(List.of("--mode", "accept-edits"));
    if (model != null && !model.isBlank()) {
      if (!model.matches("[a-z0-9.-]{1,64}")) throw new IllegalArgumentException("Invalid model");
      argv.addAll(List.of("--model", model));
    }
    return argv;
  }

  /** The line the CLI reads: one user turn. */
  public static String userEvent(String prompt) {
    return encode(obj("event", "user", "message", obj("content", prompt)));
  }

  /** A turn that did not succeed; {@code costMicros} is what its tokens still cost, or null. */
  public static final class Failed extends IllegalArgumentException {
    public final Long costMicros;

    public Failed(String message, Long costMicros) {
      super(message);
      this.costMicros = costMicros;
    }
  }

  /**
   * All input tokens at the input price (cache is not discounted, so it never underestimates);
   * output tokens already include the thinking ones.
   */
  static long cost(JsonNode usage, double inputUsdPerMtok, double outputUsdPerMtok) {
    return (long)
        Math.ceil(
            usage.path("input_tokens").asLong(0) * inputUsdPerMtok
                + usage.path("output_tokens").asLong(0) * outputUsdPerMtok);
  }

  /** The {@code result} event of the stream (or a bare json envelope); anything else is refused. */
  public static Result parse(String output, double inputUsdPerMtok, double outputUsdPerMtok) {
    JsonNode result = null;
    for (String line : output.split("\\R")) {
      String t = line.trim();
      if (!t.startsWith("{")) continue;
      JsonNode n;
      try {
        n = io.agentfarm.core.Json.parse(t);
      } catch (Exception e) {
        continue;
      }
      if (n.path("event").asText().equals("result")) result = n.path("result");
      else if (!n.has("event") && n.has("status")) result = n;
    }
    if (result == null) throw new IllegalArgumentException("Antigravity CLI returned no result");
    var usage = result.path("usage");
    Long cost =
        usage.isObject() && usage.path("total_tokens").asLong(0) > 0
            ? cost(usage, inputUsdPerMtok, outputUsdPerMtok)
            : null;
    String status = result.path("status").asText("");
    if (!status.equals("SUCCESS"))
      throw new Failed(
          "Antigravity CLI turn failed: "
              + (status.isEmpty() ? "unknown" : status)
              + " - "
              + result.path("error").asText(""),
          cost);
    if (cost == null) throw new Failed("Antigravity CLI did not report token usage", null);
    var denied = new ArrayList<String>();
    for (var d : result.path("denied_actions"))
      denied.add(d.isTextual() ? d.asText() : d.toString());
    return new Result(
        result.path("response").asText(),
        cost,
        result.path("conversation_id").asText(""),
        usage.path("total_tokens").asLong(0),
        List.copyOf(denied));
  }
}
