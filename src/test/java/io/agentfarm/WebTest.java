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
        List.of(
            "/api/audit",
            "/api/executors",
            "/api/budget/another-account",
            "/api/work",
            "/api/evidence",
            "/api/providers",
            "/api/initiatives",
            "/api/recipes",
            "/api/automation"))
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
    var wrongAccount =
        HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/api/providers/admission"))
            .header("Authorization", bearer)
            .header("Content-Type", "application/json")
            .header(csrf.path("header").asText(), csrf.path("token").asText())
            .POST(
                HttpRequest.BodyPublishers.ofString(
                    "{\"provider\":\"codex\",\"account\":\"another-account\",\"profile\":\"balanced\"}"))
            .build();
    assertEquals(
        403, executor.send(wrongAccount, HttpResponse.BodyHandlers.ofString()).statusCode());
    claim.header(csrf.path("header").asText(), csrf.path("token").asText());
    assertEquals(
        200, executor.send(claim.build(), HttpResponse.BodyHandlers.ofString()).statusCode());
    for (var agent : Map.of("test-executor-s2", 200, "other-executor", 403, "test-executor-x", 403).entrySet())
      assertEquals(
          agent.getValue(),
          executor
              .send(
                  claim
                      .copy()
                      .POST(HttpRequest.BodyPublishers.ofString("{\"agent\":\"" + agent.getKey() + "\"}"))
                      .build(),
                  HttpResponse.BodyHandlers.ofString())
              .statusCode(),
          agent.getKey());
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

  @Test
  void meteredAccountOnlyReservesAndSettles() throws Exception {
    store.tx(
        c -> {
          Store.exec(c, "INSERT OR IGNORE INTO farm_nodes(id,role) VALUES('metered-node','napoleon')");
          Store.exec(c, "INSERT OR IGNORE INTO budget_accounts VALUES('sub-m','subscription','{}')");
          Store.exec(c, "INSERT OR IGNORE INTO budget_accounts VALUES('api-m','api','{\"monthly\":10000}')");
          return null;
        });
    assertThrows(
        IllegalArgumentException.class,
        () -> nodeCredentials.issue("metered-node", "sub-m", "sub-m", "test"));
    var issued = Json.MAPPER.valueToTree(nodeCredentials.issue("metered-node", "sub-m", "api-m", "test"));
    String bearer = "Bearer " + issued.path("token").asText();
    HttpClient node =
        HttpClient.newBuilder().cookieHandler(new CookieManager(null, CookiePolicy.ACCEPT_ALL)).build();
    var csrf =
        Json.parse(
            node.send(
                    HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/api/csrf"))
                        .header("Authorization", bearer)
                        .build(),
                    HttpResponse.BodyHandlers.ofString())
                .body());
    java.util.function.BiFunction<String, String, Integer> post =
        (path, body) -> {
          try {
            return node.send(
                    HttpRequest.newBuilder(URI.create("http://localhost:" + port + path))
                        .header("Authorization", bearer)
                        .header("Content-Type", "application/json")
                        .header(csrf.path("header").asText(), csrf.path("token").asText())
                        .POST(HttpRequest.BodyPublishers.ofString(body))
                        .build(),
                    HttpResponse.BodyHandlers.ofString())
                .statusCode();
          } catch (Exception e) {
            throw new RuntimeException(e);
          }
        };
    assertEquals(
        200,
        node.send(
                HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/api/budget/api-m"))
                    .header("Authorization", bearer)
                    .build(),
                HttpResponse.BodyHandlers.ofString())
            .statusCode());
    assertNotEquals(403, post.apply("/api/budget/reserve", "{\"account\":\"api-m\",\"run_id\":\"r1\",\"amounts\":{\"monthly\":1}}"));
    assertEquals(403, post.apply("/api/budget/configure", "{\"account\":\"api-m\",\"mode\":\"api\",\"shares\":{\"monthly\":1}}"));
    assertEquals(403, post.apply("/api/budget/rollover", "{\"account\":\"api-m\"}"));
    assertEquals(403, post.apply("/api/budget/settle", "{\"account\":\"sub-m\",\"run_id\":\"r1\",\"actual\":null}"));
  }

  @Test
  void evidenceApiIsOwnerOnlyAndMutationsRequireCsrf() throws Exception {
    assertEquals(200, get("/api/evidence", true).statusCode());
    assertEquals(409, get("/api/evidence/unknown-task", true).statusCode());
    var request =
        HttpRequest.newBuilder(
                URI.create("http://localhost:" + port + "/api/evidence/unknown-task/decisions"))
            .header("Authorization", auth())
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString("{}"))
            .build();
    assertEquals(403, client.send(request, HttpResponse.BodyHandlers.ofString()).statusCode());
  }

  @Test
  void providerCatalogAndAdmissionKeepAuthenticationAndCsrf() throws Exception {
    var providers = get("/api/providers", true);
    assertEquals(200, providers.statusCode());
    assertTrue(providers.body().contains("claude-code"));
    assertTrue(providers.body().contains("api-cost-observed"));
    var req =
        HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/api/providers/admission"))
            .header("Authorization", auth())
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString("{}"))
            .build();
    assertEquals(403, client.send(req, HttpResponse.BodyHandlers.ofString()).statusCode());
  }

  @Test
  void initiativesRequireOwnerCsrfAndPreserveRequestIdentity() throws Exception {
    assertTrue(Set.of(401, 302).contains(get("/api/initiatives", false).statusCode()));
    String body =
        Json.encode(
            Json.obj(
                "request_id",
                "web-initiative-123",
                "title",
                "Release",
                "goal",
                "Coordinate accepted changes",
                "review_limit",
                2,
                "milestones",
                List.of(Json.obj("id", "M1", "title", "First milestone"))));
    var builder =
        HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/api/initiatives"))
            .header("Authorization", auth())
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(body));
    assertEquals(
        403, client.send(builder.build(), HttpResponse.BodyHandlers.ofString()).statusCode());
    var csrf = Json.parse(get("/api/csrf", true).body());
    builder.header(csrf.path("header").asText(), csrf.path("token").asText());
    var first = client.send(builder.build(), HttpResponse.BodyHandlers.ofString());
    assertEquals(200, first.statusCode());
    assertEquals(
        first.body(), client.send(builder.build(), HttpResponse.BodyHandlers.ofString()).body());
    assertEquals(200, get("/api/initiatives/web-initiative-123", true).statusCode());
    var pause =
        HttpRequest.newBuilder(
                URI.create(
                    "http://localhost:" + port + "/api/initiatives/web-initiative-123/pause"))
            .header("Authorization", auth())
            .header("Content-Type", "application/json")
            .header(csrf.path("header").asText(), csrf.path("token").asText())
            .POST(HttpRequest.BodyPublishers.ofString("{\"version\":1,\"paused\":true}"))
            .build();
    assertEquals(200, client.send(pause, HttpResponse.BodyHandlers.ofString()).statusCode());
    assertEquals(409, client.send(pause, HttpResponse.BodyHandlers.ofString()).statusCode());
    assertTrue(get("/api/audit", true).body().contains("initiative.pause"));
  }

  @Test
  void recipesAreAuthenticatedAndCreateAResearchTaskWithCsrf() throws Exception {
    assertTrue(Set.of(401, 302).contains(get("/api/recipes", false).statusCode()));
    var recipes = Json.parse(get("/api/recipes", true).body());
    assertEquals(5, recipes.size());
    var selected = recipes.get(0);
    var body =
        Json.obj(
            "request_id",
            "web-recipe-001",
            "repository",
            "owner/repo",
            "title",
            "Bug",
            "request",
            "Reproduce the reported bug",
            "recipe",
            Json.obj(
                "id", selected.path("id").asText(), "version", selected.path("version").asInt()));
    var builder =
        HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/api/work"))
            .header("Authorization", auth())
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(Json.encode(body)));
    assertEquals(
        403, client.send(builder.build(), HttpResponse.BodyHandlers.ofString()).statusCode());
    var csrf = Json.parse(get("/api/csrf", true).body());
    builder.header(csrf.path("header").asText(), csrf.path("token").asText());
    var first = client.send(builder.build(), HttpResponse.BodyHandlers.ofString());
    assertEquals(200, first.statusCode());
    assertEquals(selected, Json.parse(first.body()).path("recipe"));
    assertEquals("queued", Json.parse(first.body()).path("state").asText());
    assertEquals(
        first.body(), client.send(builder.build(), HttpResponse.BodyHandlers.ofString()).body());
  }

  @Test
  void concurrentSessionPollingKeepsDatabaseEndpointsResponsive() throws Exception {
    var csrf = Json.parse(get("/api/csrf", true).body());
    String password = Files.readString(dir.resolve("admin-password.txt")).trim();
    var login =
        HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/login"))
            .header(csrf.path("header").asText(), csrf.path("token").asText())
            .header("Content-Type", "application/x-www-form-urlencoded")
            .POST(
                HttpRequest.BodyPublishers.ofString(
                    "username=admin&password="
                        + URLEncoder.encode(password, StandardCharsets.UTF_8)))
            .build();
    assertEquals(302, client.send(login, HttpResponse.BodyHandlers.ofString()).statusCode());
    String cookie =
        ((CookieManager) client.cookieHandler().orElseThrow())
            .getCookieStore().getCookies().stream()
                .map(c -> c.getName() + "=" + c.getValue())
                .collect(java.util.stream.Collectors.joining("; "));
    var session = HttpClient.newHttpClient();
    var pool = java.util.concurrent.Executors.newFixedThreadPool(16);
    try {
      var jobs = new ArrayList<java.util.concurrent.Future<?>>();
      for (int i = 0; i < 16; i++)
        jobs.add(
            pool.submit(
                () -> {
                  for (int j = 0; j < 12; j++) {
                    try {
                      var request =
                          HttpRequest.newBuilder(
                                  URI.create(
                                      "http://localhost:"
                                          + port
                                          + (j % 2 == 0 ? "/api/work" : "/api/runtime")))
                              .timeout(java.time.Duration.ofSeconds(10))
                              .header("Cookie", cookie)
                              .GET()
                              .build();
                      var response = session.send(request, HttpResponse.BodyHandlers.ofString());
                      assertEquals(200, response.statusCode());
                      assertNotNull(Json.parse(response.body()));
                    } catch (Exception e) {
                      throw new RuntimeException(e);
                    }
                  }
                }));
      for (var job : jobs) job.get(30, java.util.concurrent.TimeUnit.SECONDS);
    } finally {
      pool.shutdownNow();
    }
    assertEquals(200, get("/api/runtime", true).statusCode());
  }

  @AfterAll
  static void cleanup() throws Exception {
    try (var files = Files.walk(dir)) {
      for (Path p : files.sorted(Comparator.reverseOrder()).toList()) Files.deleteIfExists(p);
    }
  }
}
