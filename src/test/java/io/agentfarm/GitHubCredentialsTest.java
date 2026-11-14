package io.agentfarm;

import static org.junit.jupiter.api.Assertions.*;

import io.agentfarm.core.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.*;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class GitHubCredentialsTest {
  @TempDir Path dir;

  @Test
  void appTokenIsMintedWithAValidJwtAndCachedUntilNearExpiry() throws Exception {
    var pair = KeyPairGenerator.getInstance("RSA");
    pair.initialize(2048);
    var keys = pair.generateKeyPair();
    Path key = dir.resolve("app.pem");
    Files.writeString(
        key,
        "-----BEGIN PRIVATE KEY-----\n"
            + Base64.getMimeEncoder().encodeToString(keys.getPrivate().getEncoded())
            + "\n-----END PRIVATE KEY-----\n");
    var now = Instant.parse("2026-11-01T10:00:00Z");
    var clock =
        new Clock() {
          Instant at = now;

          public ZoneId getZone() {
            return ZoneOffset.UTC;
          }

          public Clock withZone(ZoneId z) {
            return this;
          }

          public Instant instant() {
            return at;
          }
        };
    var calls = new ArrayList<String>();
    var credentials =
        new GitHubCredentials(
            "",
            "12345",
            key.toString(),
            "678",
            (installation, jwt) -> {
              calls.add(jwt);
              String[] parts = jwt.split("\\.");
              var verifier = Signature.getInstance("SHA256withRSA");
              verifier.initVerify(keys.getPublic());
              verifier.update((parts[0] + "." + parts[1]).getBytes(StandardCharsets.US_ASCII));
              assertTrue(
                  verifier.verify(Base64.getUrlDecoder().decode(parts[2])),
                  "valid RS256 signature");
              var claims =
                  Json.parse(
                      new String(Base64.getUrlDecoder().decode(parts[1]), StandardCharsets.UTF_8));
              assertEquals("12345", claims.path("iss").asText());
              assertTrue(claims.path("exp").asLong() - claims.path("iat").asLong() <= 600);
              assertEquals("678", installation);
              return "{\"token\":\"ghs_"
                  + calls.size()
                  + "\",\"expires_at\":\""
                  + clock.at.plusSeconds(3600)
                  + "\"}";
            },
            clock);
    assertEquals("github-app", credentials.mode());
    assertEquals("ghs_1", credentials.token());
    clock.at = now.plusSeconds(1800);
    assertEquals("ghs_1", credentials.token(), "cached");
    clock.at = now.plusSeconds(3400);
    assertEquals("ghs_2", credentials.token(), "refreshed before expiry");
  }

  @Test
  void rejectsPkcs1KeysAndNonNumericIds() throws Exception {
    Path key = dir.resolve("rsa.pem");
    Files.writeString(
        key, "-----BEGIN RSA PRIVATE KEY-----\nAAAA\n-----END RSA PRIVATE KEY-----\n");
    var credentials =
        new GitHubCredentials("", "1", key.toString(), "2", (i, j) -> "{}", Clock.systemUTC());
    var error = assertThrows(IllegalArgumentException.class, credentials::token);
    assertTrue(error.getMessage().contains("pkcs8"));
    assertThrows(
        IllegalArgumentException.class,
        () -> new GitHubCredentials("", "abc", "", "2", (i, j) -> "{}", Clock.systemUTC()));
    assertEquals("none", GitHubCredentials.file("").mode());
  }

  private GitHubCredentials withApp(String selection, java.util.List<String> bodies)
      throws Exception {
    return withApp(selection, bodies, null);
  }

  /** {@code permissions}: what the fake GitHub answers; null grants exactly what was asked. */
  private GitHubCredentials withApp(
      String selection, java.util.List<String> bodies, String permissions) throws Exception {
    var keys = KeyPairGenerator.getInstance("RSA");
    keys.initialize(2048);
    Path key = dir.resolve("scoped.pem");
    Files.writeString(
        key,
        "-----BEGIN PRIVATE KEY-----\n"
            + Base64.getEncoder().encodeToString(keys.generateKeyPair().getPrivate().getEncoded())
            + "\n-----END PRIVATE KEY-----\n");
    return new GitHubCredentials(
        "",
        "1",
        key.toString(),
        "2",
        new GitHubCredentials.Exchange() {
          @Override
          public String tokenResponse(String installation, String jwt) {
            throw new AssertionError("the unlimited call must not be used");
          }

          @Override
          public String tokenResponse(String installation, String jwt, String body) {
            bodies.add(body);
            String granted =
                permissions != null
                    ? permissions
                    : Json.encode(Json.parse(body).path("permissions"));
            return "{\"token\":\"ghs_x\",\"expires_at\":\"2099-01-01T00:00:00Z\","
                + "\"repository_selection\":\""
                + selection
                + "\",\"permissions\":"
                + granted
                + "}";
          }
        },
        Clock.systemUTC());
  }

  @Test
  void anExecutorTokenIsLimitedToItsRepositoryAndItsOrder() throws Exception {
    var bodies = new ArrayList<String>();
    var token = withApp("selected", bodies).repositoryToken("owner/forja", "implement");
    assertEquals("ghs_x", token.get("token"));
    var asked = Json.parse(bodies.get(0));
    assertEquals("forja", asked.path("repositories").get(0).asText());
    assertEquals(1, asked.path("repositories").size());
    assertEquals("write", asked.path("permissions").path("contents").asText());
    assertEquals("write", asked.path("permissions").path("pull_requests").asText());
    assertEquals(
        "write", asked.path("permissions").path("issues").asText(), "implement writes the result");

    for (String kind : java.util.List.of("research", "review_existing")) {
      bodies.clear();
      withApp("selected", bodies).repositoryToken("owner/forja", kind);
      var read = Json.parse(bodies.get(0)).path("permissions");
      // The research creates the task's issue, so it may write issues and nothing else.
      read.properties()
          .forEach(e ->
              assertEquals(
                  kind.equals("research") && e.getKey().equals("issues") ? "write" : "read",
                  e.getValue().asText(),
                  kind + " " + e.getKey()));
      assertFalse(read.has("workflows"), kind);
    }
  }

  @Test
  void aWiderTokenThanRequestedIsRefused() throws Exception {
    assertThrows(
        IllegalStateException.class,
        () -> withApp("all", new ArrayList<>()).repositoryToken("owner/forja", "implement"));
    // More permissions than asked: an App that grants a level or a right the order did not need.
    for (String granted :
        java.util.List.of(
            "{\"metadata\":\"read\",\"contents\":\"write\"}",
            "{\"metadata\":\"read\",\"contents\":\"read\",\"administration\":\"read\"}",
            "{\"metadata\":\"read\",\"contents\":\"admin\"}",
            "{\"metadata\":\"read\",\"contents\":\"surprise\"}")) {
      var e =
          assertThrows(
              IllegalStateException.class,
              () ->
                  withApp("selected", new ArrayList<>(), granted)
                      .repositoryToken("owner/forja", "research"),
              granted);
      assertTrue(e.getMessage().contains("more permissions than asked"), e.getMessage());
    }
    // A response that does not say what the token can do is not trusted either.
    assertThrows(
        IllegalStateException.class,
        () ->
            withApp("selected", new ArrayList<>(), "\"nothing\"")
                .repositoryToken("owner/forja", "research"));
    // Less than asked is fine: the App may not hold every right.
    assertEquals(
        "ghs_x",
        withApp("selected", new ArrayList<>(), "{\"metadata\":\"read\"}")
            .repositoryToken("owner/forja", "implement")
            .get("token"));
  }

  @Test
  void anOrderOfAnUnknownKindOrAMergeGetsNoToken() {
    for (String kind : java.util.List.of("merge", "", "anything"))
      assertThrows(
          IllegalArgumentException.class,
          () -> withApp("selected", new ArrayList<>()).repositoryToken("owner/forja", kind),
          kind);
  }

  @Test
  void withoutAnAppThereIsNoExecutorToken() throws Exception {
    assertNull(GitHubCredentials.file("").repositoryToken("owner/forja", "implement"));
  }
}
