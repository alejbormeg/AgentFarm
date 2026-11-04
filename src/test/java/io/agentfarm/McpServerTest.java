package io.agentfarm;

import static io.agentfarm.core.Json.*;
import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.databind.JsonNode;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import org.junit.jupiter.api.Test;

class McpServerTest {
  final List<String> posts = new ArrayList<>();

  McpServer server() {
    return new McpServer(
        new McpServer.Panel() {
          public JsonNode get(String path) {
            return switch (path) {
              case "/work" ->
                  parse(
                      "[{\"id\":\"a\",\"repo\":\"o/r\",\"title\":\"T\",\"state\":\"proposed\",\"version\":2,\"pr\":null,\"error\":null,"
                          + "\"proposal\":\"{\\\"summary\\\":\\\"S\\\"}\",\"evidence\":\"{}\"},"
                          + "{\"id\":\"b\",\"repo\":\"o/r\",\"title\":\"Done\",\"state\":\"integrated\",\"version\":1}]");
              default -> parse("{}");
            };
          }

          public JsonNode post(String path, Object body) {
            posts.add(path + " " + encode(body));
            return parse("{\"id\":\"mcp-x\",\"state\":\"queued\"}");
          }
        });
  }

  List<JsonNode> exchange(String... lines) throws IOException {
    var out = new ByteArrayOutputStream();
    server()
        .serve(
            new BufferedReader(new StringReader(String.join("\n", lines))),
            new PrintStream(out, true, StandardCharsets.UTF_8));
    var replies = new ArrayList<JsonNode>();
    for (String l : out.toString(StandardCharsets.UTF_8).split("\n"))
      if (!l.isBlank()) replies.add(parse(l));
    return replies;
  }

  @Test
  void handshakeListsToolsWithoutAnyApprovalTool() throws Exception {
    var r =
        exchange(
            "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"initialize\",\"params\":{\"protocolVersion\":\"2025-06-18\"}}",
            "{\"jsonrpc\":\"2.0\",\"method\":\"notifications/initialized\"}",
            "{\"jsonrpc\":\"2.0\",\"id\":2,\"method\":\"tools/list\"}",
            "{\"jsonrpc\":\"2.0\",\"id\":3,\"method\":\"nope\"}");
    assertEquals(3, r.size(), "notifications get no reply");
    assertEquals("agentfarm", r.get(0).path("result").path("serverInfo").path("name").asText());
    var names = new ArrayList<String>();
    r.get(1).path("result").path("tools").forEach(t -> names.add(t.path("name").asText()));
    assertTrue(names.contains("agentfarm_inbox"));
    assertTrue(
        names.stream()
            .noneMatch(
                n -> n.contains("approve") || n.contains("merge") || n.contains("implement")));
    assertEquals(-32601, r.get(2).path("error").path("code").asInt());
  }

  @Test
  void inboxHidesFinishedWorkAndCreateNeverApproves() throws Exception {
    var r =
        exchange(
            "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"tools/call\",\"params\":{\"name\":\"agentfarm_inbox\",\"arguments\":{}}}",
            "{\"jsonrpc\":\"2.0\",\"id\":2,\"method\":\"tools/call\",\"params\":{\"name\":\"agentfarm_create_request\",\"arguments\":{\"repository\":\"o/r\",\"title\":\"x\",\"request\":\"y\",\"recipe\":\"bugfix\"}}}",
            "{\"jsonrpc\":\"2.0\",\"id\":3,\"method\":\"tools/call\",\"params\":{\"name\":\"agentfarm_work_item\",\"arguments\":{\"id\":\"zzz\"}}}");
    var inbox = parse(r.get(0).path("result").path("content").get(0).path("text").asText());
    assertEquals(1, inbox.size());
    assertTrue(inbox.get(0).path("needs_owner").asBoolean());
    assertEquals(1, posts.size());
    assertTrue(posts.get(0).startsWith("/work "), "only creates requests");
    assertTrue(posts.get(0).contains("\"bugfix\""));
    assertTrue(r.get(2).path("result").path("isError").asBoolean());
  }
}
