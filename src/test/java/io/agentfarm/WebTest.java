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

  @AfterAll
  static void cleanup() throws Exception {
    try (var files = Files.walk(dir)) {
      for (Path p : files.sorted(Comparator.reverseOrder()).toList()) Files.deleteIfExists(p);
    }
  }
}
