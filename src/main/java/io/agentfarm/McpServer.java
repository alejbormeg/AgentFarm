package io.agentfarm;

import static io.agentfarm.core.Json.*;

import com.fasterxml.jackson.databind.JsonNode;
import java.io.*;
import java.net.*;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.Duration;
import java.util.*;

/**
 * Model Context Protocol server over stdio (newline-delimited JSON-RPC 2.0). It lets Claude Code
 * or Claude Desktop read the decision inbox, proposals and metrics and file new requests. It
 * deliberately has no tool to approve, implement or merge: a model must not approve agent work.
 */
public final class McpServer {
  static final String VERSION = "2025-06-18";

  /** Panel access; replaced in tests. */
  public interface Panel {
    JsonNode get(String path) throws Exception;

    JsonNode post(String path, Object body) throws Exception;
  }

  private final Panel panel;

  public McpServer(Panel panel) {
    this.panel = panel;
  }

  public static Panel http(String base, String password) {
    var client =
        HttpClient.newBuilder()
            .cookieHandler(new CookieManager(null, CookiePolicy.ACCEPT_ALL))
            .connectTimeout(Duration.ofSeconds(5))
            .build();
    String auth =
        "Basic "
            + Base64.getEncoder()
                .encodeToString(("admin:" + password).getBytes(StandardCharsets.UTF_8));
    return new Panel() {
      JsonNode csrf;

      HttpRequest.Builder request(String path) {
        return HttpRequest.newBuilder(URI.create(base + "/api" + path))
            .timeout(Duration.ofSeconds(20))
            .header("Authorization", auth);
      }

      JsonNode send(HttpRequest r) throws Exception {
        var response = client.send(r, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() != 200)
          throw new IOException("Panel HTTP " + response.statusCode() + ": " + response.body());
        return parse(response.body());
      }

      public JsonNode get(String path) throws Exception {
        return send(request(path).GET().build());
      }

      public JsonNode post(String path, Object body) throws Exception {
        if (csrf == null) csrf = get("/csrf");
        return send(
            request(path)
                .header("Content-Type", "application/json")
                .header(csrf.path("header").asText(), csrf.path("token").asText())
                .POST(HttpRequest.BodyPublishers.ofString(encode(body)))
                .build());
      }
    };
  }

  static Map<String, Object> tool(String name, String description, Map<String, Object> props, List<String> required) {
    return obj(
        "name",
        name,
        "description",
        description,
        "inputSchema",
        obj("type", "object", "properties", props, "required", required));
  }

  static final List<Map<String, Object>> TOOLS =
      List.of(
          tool(
              "agentfarm_inbox",
              "Tareas que esperan una decisión humana (propuesta, revisión, bloqueada) y trabajo en curso.",
              Map.of(),
              List.of()),
          tool(
              "agentfarm_work_item",
              "Propuesta, criterios, evidencias y enlaces de una tarea por su id.",
              Map.of("id", obj("type", "string")),
              List.of("id")),
          tool(
              "agentfarm_create_request",
              "Crea una petición: AgentFarm abrirá una issue e investigará antes de proponer cambios. No aprueba nada.",
              Map.of(
                  "repository", obj("type", "string", "description", "owner/repo"),
                  "title", obj("type", "string"),
                  "request", obj("type", "string"),
                  "recipe",
                  obj("type", "string", "enum", List.of("bugfix", "dependency-update", "test-improvement", "migration", "project-memory"))),
              List.of("repository", "title", "request")),
          tool(
              "agentfarm_metrics",
              "Resultados por semana: propuestas, aprobación, correcciones, integraciones, ciclo y consumo.",
              Map.of("weeks", obj("type", "integer", "minimum", 1, "maximum", 26)),
              List.of()),
          tool(
              "agentfarm_status",
              "Estado de la granja: pausa, ejecutores conectados y cuota observada.",
              Map.of(),
              List.of()));

  Object call(String name, JsonNode args) throws Exception {
    return switch (name) {
      case "agentfarm_inbox" -> {
        var out = new ArrayList<Object>();
        for (var w : panel.get("/work"))
          if (!Set.of("integrated", "cancelled").contains(w.path("state").asText()))
            out.add(
                obj(
                    "id", w.path("id").asText(),
                    "repository", w.path("repo").asText(),
                    "title", w.path("title").asText(),
                    "state", w.path("state").asText(),
                    "needs_owner", Set.of("proposed", "review", "blocked").contains(w.path("state").asText()),
                    "version", w.path("version").asInt(),
                    "pr", w.path("pr").isNull() ? null : w.path("pr").asInt(),
                    "error", w.path("error").isNull() ? null : w.path("error").asText()));
        yield out;
      }
      case "agentfarm_work_item" -> {
        String id = text(args, "id");
        for (var w : panel.get("/work"))
          if (w.path("id").asText().equals(id)) {
            var p = parse(w.path("proposal").asText("{}"));
            var e = parse(w.path("evidence").asText("{}"));
            yield obj(
                "id", id,
                "repository", w.path("repo").asText(),
                "title", w.path("title").asText(),
                "state", w.path("state").asText(),
                "version", w.path("version").asInt(),
                "summary", p.path("summary").asText(),
                "criteria", p.path("criteria"),
                "unit_test", p.path("unit_test").asText(),
                "integration_test", p.path("integration_test").asText(),
                "sandbox", p.path("sandbox"),
                "sha", w.path("sha").asText(null),
                "pr", w.path("pr").isNull() ? null : w.path("pr").asInt(),
                "evidence_passed", e.path("passed").isMissingNode() ? null : e.path("passed").asBoolean(),
                "review_findings", e.path("review").path("findings").asText(null),
                "panel_action", "Aprobar, corregir o integrar se hace en el panel de AgentFarm.");
          }
        throw new IllegalArgumentException("Unknown work item " + id);
      }
      case "agentfarm_create_request" -> {
        var body =
            obj(
                "request_id", "mcp-" + UUID.randomUUID(),
                "repository", text(args, "repository"),
                "title", text(args, "title"),
                "request", text(args, "request"));
        if (args.hasNonNull("recipe")) body.put("recipe", obj("id", args.path("recipe").asText(), "version", 1));
        var created = panel.post("/work", body);
        yield obj("id", created.path("id").asText(), "state", created.path("state").asText());
      }
      case "agentfarm_metrics" -> panel.get("/work/metrics?weeks=" + args.path("weeks").asInt(8));
      case "agentfarm_status" -> {
        var runtime = panel.get("/runtime");
        var overview = panel.get("/overview");
        var executors = new ArrayList<Object>();
        for (var x : runtime.path("executors"))
          executors.add(obj("executor", x.path("executor").asText(), "fresh", x.path("fresh").asBoolean(), "phase", x.path("snapshot").path("run_state").asText()));
        yield obj(
            "paused", overview.path("paused").asBoolean(),
            "executors", executors,
            "quota_fresh", runtime.path("quota_fresh").asBoolean(),
            "weekly_used_percent", runtime.path("snapshot").path("quota").path("weekly_used_percent"));
      }
      default -> throw new IllegalArgumentException("Unknown tool " + name);
    };
  }

  /** Handles one JSON-RPC message; returns null for notifications. */
  public Map<String, Object> handle(JsonNode message) {
    if (!message.has("id")) return null;
    var id = message.get("id");
    String method = message.path("method").asText();
    try {
      Object result =
          switch (method) {
            case "initialize" ->
                obj(
                    "protocolVersion", message.path("params").path("protocolVersion").asText(VERSION),
                    "capabilities", obj("tools", obj("listChanged", false)),
                    "serverInfo", obj("name", "agentfarm", "version", "0.2"),
                    "instructions",
                    "AgentFarm gobierna un equipo de agentes. Puedes consultar y crear peticiones; las aprobaciones e integraciones las hace el propietario en el panel.");
            case "ping" -> Map.of();
            case "tools/list" -> obj("tools", TOOLS);
            case "tools/call" -> {
              var params = message.path("params");
              try {
                var value = call(params.path("name").asText(), params.path("arguments"));
                yield obj(
                    "content",
                    List.of(obj("type", "text", "text", MAPPER.writerWithDefaultPrettyPrinter().writeValueAsString(value))),
                    "isError",
                    false);
              } catch (Exception e) {
                yield obj("content", List.of(obj("type", "text", "text", String.valueOf(e.getMessage()))), "isError", true);
              }
            }
            default -> throw new UnsupportedOperationException(method);
          };
      return obj("jsonrpc", "2.0", "id", id, "result", result);
    } catch (UnsupportedOperationException e) {
      return obj("jsonrpc", "2.0", "id", id, "error", obj("code", -32601, "message", "Method not found: " + method));
    }
  }

  public void serve(BufferedReader in, PrintStream out) throws IOException {
    String line;
    while ((line = in.readLine()) != null) {
      if (line.isBlank()) continue;
      Map<String, Object> response;
      try {
        response = handle(parse(line));
      } catch (Exception e) {
        response = obj("jsonrpc", "2.0", "id", null, "error", obj("code", -32700, "message", "Parse error"));
      }
      if (response != null) {
        out.println(encode(response));
        out.flush();
      }
    }
  }

  public static void main(String panelUrl, Path passwordFile) throws IOException {
    var server = new McpServer(http(panelUrl, Files.readString(passwordFile).trim()));
    server.serve(new BufferedReader(new InputStreamReader(System.in, StandardCharsets.UTF_8)), System.out);
  }
}
