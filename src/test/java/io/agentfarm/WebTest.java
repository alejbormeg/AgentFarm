package io.agentfarm;

import static org.junit.jupiter.api.Assertions.*;

import io.agentfarm.core.*;
import java.net.*;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import org.junit.jupiter.api.*;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class WebTest {
  static Path dir;

  static {
    try {
      dir = Files.createTempDirectory("agentfarm-webtest-");
    } catch (Exception e) {
      throw new ExceptionInInitializerError(e);
    }
  }

  @DynamicPropertySource
  static void properties(DynamicPropertyRegistry r) {
    r.add("agentfarm.db", () -> dir.resolve("farm.db").toString());
  }

  @LocalServerPort int port;
  HttpClient client =
      HttpClient.newBuilder()
          .cookieHandler(new CookieManager(null, CookiePolicy.ACCEPT_ALL))
          .followRedirects(HttpClient.Redirect.NEVER)
          .build();

  String auth() throws Exception {
    return "Basic "
        + Base64.getEncoder()
            .encodeToString(
                ("admin:" + Files.readString(dir.resolve("admin-password.txt")).trim())
                    .getBytes(StandardCharsets.UTF_8));
  }

  HttpResponse<String> get(String path, boolean authenticated) throws Exception {
    var b = HttpRequest.newBuilder(URI.create("http://localhost:" + port + path));
    if (authenticated) b.header("Authorization", auth());
    return client.send(b.GET().build(), HttpResponse.BodyHandlers.ofString());
  }

  @Test
  void anonymousCannotReadControlPlane() throws Exception {
    assertTrue(Set.of(401, 302).contains(get("/api/overview", false).statusCode()));
  }

  @Test
  void ownerGetsOverviewAndMateuAssets() throws Exception {
    assertEquals(200, get("/api/overview", true).statusCode());
    assertEquals(200, get("/", true).statusCode());
    assertTrue(get("/", true).body().contains("mateu-ui"));
    assertEquals(200, get("/assets/mateu-vaadin.js", true).statusCode());
  }

  @Test
  void restMutationRequiresCsrf() throws Exception {
    var req =
        HttpRequest.newBuilder(
                URI.create("http://localhost:" + port + "/api/governance/paused/true"))
            .header("Authorization", auth())
            .POST(HttpRequest.BodyPublishers.noBody())
            .build();
    assertEquals(403, client.send(req, HttpResponse.BodyHandlers.ofString()).statusCode());
  }

  @Test
  void ownerCanPauseWithCsrfAndActionIsAudited() throws Exception {
    var csrf = Json.parse(get("/api/csrf", true).body());
    var req =
        HttpRequest.newBuilder(
                URI.create("http://localhost:" + port + "/api/governance/paused/true"))
            .header("Authorization", auth())
            .header(csrf.get("header").asText(), csrf.get("token").asText())
            .POST(HttpRequest.BodyPublishers.noBody())
            .build();
    assertEquals(200, client.send(req, HttpResponse.BodyHandlers.ofString()).statusCode());
    assertTrue(get("/api/audit", true).body().contains("farm.pause"));
  }

  @Test
  void mateuRejectsCrossOriginMutation() throws Exception {
    var req =
        HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/mateu/v3/route"))
            .header("Authorization", auth())
            .header("Origin", "https://example.invalid")
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString("{}"))
            .build();
    assertEquals(403, client.send(req, HttpResponse.BodyHandlers.ofString()).statusCode());
  }

  @Test
  void nodeIsUnknownUntilReported() throws Exception {
    var csrf = Json.parse(get("/api/csrf", true).body());
    var req =
        HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/api/nodes"))
            .header("Authorization", auth())
            .header(csrf.get("header").asText(), csrf.get("token").asText())
            .header("Content-Type", "application/json")
            .POST(
                HttpRequest.BodyPublishers.ofString(
                    "{\"id\":\"napoleon-web\",\"role\":\"napoleon\"}"))
            .build();
    assertEquals(200, client.send(req, HttpResponse.BodyHandlers.ofString()).statusCode());
    assertTrue(get("/api/nodes", true).body().contains("unknown"));
  }

  @Test
  void workflowRequiresCsrfAndPreservesRequestIdentity() throws Exception {
    String body =
        Json.encode(
            Json.obj(
                "request_id",
                "web-work-123",
                "repository",
                "owner/repo",
                "title",
                "Test",
                "request",
                "Investigate"));
    var builder =
        HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/api/work"))
            .header("Authorization", auth())
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(body));
    assertEquals(
        403, client.send(builder.build(), HttpResponse.BodyHandlers.ofString()).statusCode());
    var token = Json.parse(get("/api/csrf", true).body());
    builder.header(token.path("header").asText(), token.path("token").asText());
    var first = client.send(builder.build(), HttpResponse.BodyHandlers.ofString());
    assertEquals(200, first.statusCode());
    var second = client.send(builder.build(), HttpResponse.BodyHandlers.ofString());
    assertEquals(first.body(), second.body());
    assertTrue(get("/api/work", true).body().contains("web-work-123"));
    assertTrue(Set.of(401, 302).contains(get("/api/work", false).statusCode()));
  }

  @org.springframework.beans.factory.annotation.Autowired NodeCredentials nodeCredentials;
  @org.springframework.beans.factory.annotation.Autowired Store store;

  @Test
  void executorIsScopedRevocableAndCannotBecomeOwner() throws Exception {
    store.tx(
        c -> {
          Store.exec(
              c, "INSERT OR IGNORE INTO farm_nodes(id,role) VALUES('test-executor','napoleon')");
          Store.exec(
              c,
              "INSERT OR IGNORE INTO budget_accounts VALUES('test-account','subscription','{}')");
          return null;
        });
    var issued =
        Json.MAPPER.valueToTree(nodeCredentials.issue("test-executor", "test-account", "test"));
    String bearer = "Bearer " + issued.path("token").asText();
    HttpClient executor =
        HttpClient.newBuilder()
            .cookieHandler(new CookieManager(null, CookiePolicy.ACCEPT_ALL))
            .build();
    java.util.function.Function<String, HttpRequest> request =
        path ->
            HttpRequest.newBuilder(URI.create("http://localhost:" + port + path))
                .header("Authorization", bearer)
                .GET()
                .build();
    assertEquals(
        200,
        executor
            .send(request.apply("/api/runtime"), HttpResponse.BodyHandlers.ofString())
            .statusCode());
    for (String denied :
        List.of("/api/audit", "/api/executors", "/api/budget/another-account", "/api/work"))
      assertEquals(
          403,
          executor.send(request.apply(denied), HttpResponse.BodyHandlers.ofString()).statusCode(),
          denied);
    var csrf =
        Json.parse(
            executor.send(request.apply("/api/csrf"), HttpResponse.BodyHandlers.ofString()).body());
    var claim =
        HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/api/work/agent/claim"))
            .header("Authorization", bearer)
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString("{\"agent\":\"test-executor\"}"));
    assertEquals(
        403, executor.send(claim.build(), HttpResponse.BodyHandlers.ofString()).statusCode());
    claim.header(csrf.path("header").asText(), csrf.path("token").asText());
    assertEquals(
        200, executor.send(claim.build(), HttpResponse.BodyHandlers.ofString()).statusCode());
    assertTrue(
        Set.of(401, 302, 403)
            .contains(
                executor
                    .send(
                        HttpRequest.newBuilder(
                                URI.create("http://localhost:" + port + "/api/audit"))
                            .GET()
                            .build(),
                        HttpResponse.BodyHandlers.ofString())
                    .statusCode()));
    assertTrue(
        Set.of(401, 302)
            .contains(
                executor
                    .send(
                        HttpRequest.newBuilder(
                                URI.create("http://localhost:" + port + "/api/runtime"))
                            .GET()
                            .build(),
                        HttpResponse.BodyHandlers.ofString())
                    .statusCode()));
    assertFalse(Json.encode(nodeCredentials.list()).contains(issued.path("token").asText()));
    nodeCredentials.revoke(issued.path("id").asText(), "test");
    assertEquals(
        401,
        executor
            .send(request.apply("/api/runtime"), HttpResponse.BodyHandlers.ofString())
            .statusCode());
  }

  @AfterAll
  static void cleanup() throws Exception {
    try (var files = Files.walk(dir)) {
      for (Path p : files.sorted(Comparator.reverseOrder()).toList()) Files.deleteIfExists(p);
    }
  }
}
