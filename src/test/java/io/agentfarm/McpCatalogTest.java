package io.agentfarm;

import static org.junit.jupiter.api.Assertions.*;

import io.agentfarm.core.*;
import java.util.*;
import org.junit.jupiter.api.Test;

class McpCatalogTest {
  static final String PROFILE =
      "version: 1\nrepository: o/r\nsetup: \"\"\ntests: {unit: u, integration: i}\n"
          + "allowed_paths: [src/]\nenvironment: container\nreview: independent\nsecret_refs: {}\n";

  @Test
  void profilesNameServersAndTheExecutorDefinesThem() {
    var profile = ProjectProfile.parse(PROFILE + "mcp: [sentry]\n");
    assertEquals(List.of("sentry"), profile.mcp());
    for (String bad : List.of("mcp: [\"npx -y evil\"]\n", "mcp: {sentry: {command: sh}}\n", "mcp: [a, a]\n"))
      assertThrows(IllegalArgumentException.class, () -> ProjectProfile.parse(PROFILE + bad), bad);
    var catalog =
        McpCatalog.parse(
            Json.parse("{\"sentry\":{\"command\":\"npx\",\"args\":[\"-y\",\"@sentry/mcp-server\"],\"env\":[\"SENTRY_TOKEN\"]}}"));
    var servers = catalog.resolve(profile.mcp());
    var claude = Json.MAPPER.valueToTree(McpCatalog.claudeConfig(servers, Map.of("SENTRY_TOKEN", "t")));
    assertEquals("npx", claude.path("mcpServers").path("sentry").path("command").asText());
    assertEquals("t", claude.path("mcpServers").path("sentry").path("env").path("SENTRY_TOKEN").asText());
    assertThrows(IllegalArgumentException.class, () -> catalog.resolve(List.of("postgres")), "undefined names fail");
    assertThrows(IllegalArgumentException.class, () -> McpCatalog.claudeConfig(servers, Map.of()), "missing env");
    assertThrows(
        IllegalArgumentException.class,
        () -> McpCatalog.parse(Json.parse("{\"x\":{\"command\":\"c\",\"env\":[\"TOKEN=secret\"]}}")));
  }

  @Test
  void claudeGetsMcpOnlyForReadOnlyRoles() {
    var review = ClaudeCodeAdapter.command("claude", "review", "", List.of("sentry"), "/tmp/m.json");
    assertTrue(review.get(review.indexOf("--allowedTools") + 1).endsWith(",mcp__sentry"));
    assertTrue(review.containsAll(List.of("--mcp-config", "/tmp/m.json", "--strict-mcp-config")));
    assertThrows(
        IllegalArgumentException.class,
        () -> ClaudeCodeAdapter.command("claude", "implement", "", List.of("sentry"), "/tmp/m.json"));
  }
}
