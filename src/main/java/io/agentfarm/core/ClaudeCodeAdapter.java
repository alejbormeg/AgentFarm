package io.agentfarm.core;

import static io.agentfarm.core.Json.*;

import com.fasterxml.jackson.databind.JsonNode;
import java.time.Instant;
import java.util.*;

/**
 * Claude Code in headless print mode ({@code claude -p --output-format json}). Each turn is charged
 * to a Clover API account in USD micros: reserved before the call and settled with the cost Claude
 * Code reports. Subscription quota is not observable, so only API accounts are used.
 */
public final class ClaudeCodeAdapter implements AgentAdapter {
  /** Tools per role; Bash is never granted because tests run in the sandbox, not in the turn. */
  static final Map<String, String> TOOLS =
      Map.of(
          "research", "Read,Glob,Grep,WebSearch,WebFetch",
          "implement", "Read,Glob,Grep,Edit,Write,MultiEdit",
          "review", "Read,Glob,Grep");

  public record Result(String text, String session, long costMicros, String model) {}

  public String id() {
    return "claude-code";
  }

  public Map<String, Object> descriptor() {
    return obj(
        "id",
        id(),
        "state",
        "implemented",
        "authentication",
        "anthropic-api-key-file",
        "execution",
        "claude-cli-print-json",
        "events",
        false,
        "interrupt",
        true,
        "resume",
        false,
        "quota",
        "api-cost-observed",
        "artifacts",
        "local-executor-reports",
        "independent_verification",
        true,
        "additional_payment",
        false);
  }

  /** {@code response} is the executor's local probe: CLI version and whether a key file exists. */
  public void validateAuthentication(JsonNode response) {
    if (response.path("version").asText().isBlank())
      throw new IllegalArgumentException("Claude Code CLI not found");
    if (!response.path("key_present").asBoolean())
      throw new IllegalArgumentException("Anthropic API key file missing");
  }

  /** Cost observation for one finished turn, in the unit of Clover API accounts. */
  public JsonNode normalizeQuota(JsonNode result, Instant now) {
    var parsed = parse(result.toString());
    return MAPPER.valueToTree(
        obj(
            "provider",
            id(),
            "unit",
            "usd_micros",
            "source",
            "provider",
            "used",
            parsed.costMicros(),
            "observed_at",
            now.toString()));
  }

  public static List<String> command(String binary, String role, String model) {
    return command(binary, role, model, List.of(), null);
  }

  /**
   * Claude Code has no system sandbox here: its tools are only held back by these rules, the same
   * paths the Codex profile hides. They also cover what its tools can edit.
   */
  public static String denyRules(List<String> hidden) {
    var deny = new ArrayList<String>();
    for (String path : hidden)
      for (String tool : List.of("Read", "Edit")) {
        deny.add(tool + "(/" + path + ")");
        deny.add(tool + "(/" + path + "/**)");
      }
    return Json.encode(Map.of("permissions", Map.of("deny", deny)));
  }

  public static List<String> command(
      String binary, String role, String model, List<String> mcp, String mcpConfig) {
    return command(binary, role, model, mcp, mcpConfig, List.of());
  }

  /** With MCP servers (research and review only): their tools are allowed by server prefix. */
  public static List<String> command(
      String binary,
      String role,
      String model,
      List<String> mcp,
      String mcpConfig,
      List<String> hidden) {
    String tools = TOOLS.get(role);
    if (tools == null) throw new IllegalArgumentException("Unknown Claude Code role");
    if (!mcp.isEmpty()) {
      if (role.equals("implement"))
        throw new IllegalArgumentException("MCP tools are limited to research and review");
      for (String m : mcp) tools += ",mcp__" + m;
    }
    var argv =
        new ArrayList<>(
            List.of(
                binary,
                "-p",
                "--output-format",
                "json",
                "--permission-mode",
                role.equals("implement") ? "acceptEdits" : "default",
                "--allowedTools",
                tools,
                "--disallowedTools",
                "Bash",
                // A repository's own settings could grant tools or run hooks: they are agents'
                // files.
                "--setting-sources",
                "user",
                "--settings",
                denyRules(hidden),
                "--max-turns",
                "80"));
    if (!mcp.isEmpty()) argv.addAll(List.of("--mcp-config", mcpConfig, "--strict-mcp-config"));
    if (model != null && !model.isBlank()) {
      if (!model.matches("[a-z0-9.-]{1,64}")) throw new IllegalArgumentException("Invalid model");
      argv.addAll(List.of("--model", model));
    }
    return argv;
  }

  /** Parses the final JSON object; an error result or a missing cost is never accepted. */
  public static Result parse(String output) {
    JsonNode n;
    try {
      n = Json.parse(output.trim());
    } catch (Exception e) {
      throw new IllegalArgumentException("Claude Code returned no JSON result");
    }
    if (!n.path("type").asText().equals("result")
        || n.path("is_error").asBoolean(true)
        || !n.path("subtype").asText().equals("success"))
      throw new IllegalArgumentException(
          "Claude Code turn failed: " + n.path("subtype").asText("unknown"));
    if (!n.path("total_cost_usd").isNumber() || n.path("total_cost_usd").asDouble() < 0)
      throw new IllegalArgumentException("Claude Code did not report the turn cost");
    String model = "";
    var usage = n.path("modelUsage");
    if (usage.isObject() && usage.size() > 0) model = usage.fieldNames().next();
    return new Result(
        n.path("result").asText(),
        n.path("session_id").asText(),
        (long) Math.ceil(n.path("total_cost_usd").asDouble() * 1_000_000),
        model);
  }
}
