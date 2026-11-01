package io.agentfarm.core;

import static io.agentfarm.core.Json.*;

import com.fasterxml.jackson.databind.JsonNode;
import java.nio.file.*;
import java.util.*;

/**
 * Executor-side catalog of MCP servers ({@code STATE/mcp-servers.json}), written by the operator:
 * {"sentry": {"command": "npx", "args": ["-y", "@sentry/mcp-server"], "env": ["SENTRY_TOKEN"]}}.
 * Project profiles may only reference these names, so a repository can never choose what runs on
 * the executor host. Environment values are taken from the executor's environment by name.
 */
public final class McpCatalog {
  public record Server(String name, String command, List<String> args, List<String> env) {}

  private final Map<String, Server> servers;

  private McpCatalog(Map<String, Server> servers) {
    this.servers = servers;
  }

  public static McpCatalog load(Path file) throws Exception {
    if (!Files.exists(file)) return new McpCatalog(Map.of());
    return parse(Json.parse(Files.readString(file)));
  }

  public static McpCatalog parse(JsonNode root) {
    if (!root.isObject()) throw new IllegalArgumentException("MCP catalog must be an object");
    var out = new LinkedHashMap<String, Server>();
    for (var e : root.properties()) {
      String name = e.getKey();
      var n = e.getValue();
      if (!name.matches("[a-z0-9][a-z0-9-]{0,39}") || !n.path("command").isTextual())
        throw new IllegalArgumentException("Invalid MCP server " + name);
      var args = new ArrayList<String>();
      n.path("args").forEach(a -> args.add(a.asText()));
      var env = new ArrayList<String>();
      for (var v : n.path("env")) {
        if (!v.asText().matches("[A-Z_][A-Z0-9_]*"))
          throw new IllegalArgumentException("MCP env entries are variable names, not values");
        env.add(v.asText());
      }
      out.put(name, new Server(name, n.path("command").asText(), List.copyOf(args), List.copyOf(env)));
    }
    return new McpCatalog(out);
  }

  /** Servers for the names a profile requests; an unknown name is an error, never skipped. */
  public List<Server> resolve(List<String> names) {
    var out = new ArrayList<Server>();
    for (String name : names) {
      var server = servers.get(name);
      if (server == null)
        throw new IllegalArgumentException("MCP server '" + name + "' is not defined on this executor");
      out.add(server);
    }
    return out;
  }

  /** Claude Code --mcp-config document. */
  public static Map<String, Object> claudeConfig(List<Server> servers, Map<String, String> environment) {
    var map = new LinkedHashMap<String, Object>();
    for (var s : servers) map.put(s.name(), obj("command", s.command(), "args", s.args(), "env", values(s, environment)));
    return obj("mcpServers", map);
  }

  /** Codex thread config override (mcp_servers table). */
  public static Map<String, Object> codexConfig(List<Server> servers, Map<String, String> environment) {
    var map = new LinkedHashMap<String, Object>();
    for (var s : servers) map.put(s.name(), obj("command", s.command(), "args", s.args(), "env", values(s, environment)));
    return map;
  }

  private static Map<String, String> values(Server s, Map<String, String> environment) {
    var env = new LinkedHashMap<String, String>();
    for (String k : s.env()) {
      String v = environment.get(k);
      if (v == null) throw new IllegalArgumentException("Executor environment lacks " + k + " for MCP " + s.name());
      env.put(k, v);
    }
    return env;
  }
}
