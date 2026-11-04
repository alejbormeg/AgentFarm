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
    var clock = new Clock() {
      Instant at = now;
      public ZoneId getZone() { return ZoneOffset.UTC; }
      public Clock withZone(ZoneId z) { return this; }
      public Instant instant() { return at; }
    };
    var calls = new ArrayList<String>();
    var credentials =
        new GitHubCredentials(
            "", "12345", key.toString(), "678",
            (installation, jwt) -> {
              calls.add(jwt);
              String[] parts = jwt.split("\\.");
              var verifier = Signature.getInstance("SHA256withRSA");
              verifier.initVerify(keys.getPublic());
              verifier.update((parts[0] + "." + parts[1]).getBytes(StandardCharsets.US_ASCII));
              assertTrue(verifier.verify(Base64.getUrlDecoder().decode(parts[2])), "valid RS256 signature");
              var claims = Json.parse(new String(Base64.getUrlDecoder().decode(parts[1]), StandardCharsets.UTF_8));
              assertEquals("12345", claims.path("iss").asText());
              assertTrue(claims.path("exp").asLong() - claims.path("iat").asLong() <= 600);
              assertEquals("678", installation);
              return "{\"token\":\"ghs_" + calls.size() + "\",\"expires_at\":\"" + clock.at.plusSeconds(3600) + "\"}";
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
    Files.writeString(key, "-----BEGIN RSA PRIVATE KEY-----\nAAAA\n-----END RSA PRIVATE KEY-----\n");
    var credentials = new GitHubCredentials("", "1", key.toString(), "2", (i, j) -> "{}", Clock.systemUTC());
    var error = assertThrows(IllegalArgumentException.class, credentials::token);
    assertTrue(error.getMessage().contains("pkcs8"));
    assertThrows(IllegalArgumentException.class, () -> new GitHubCredentials("", "abc", "", "2", (i, j) -> "{}", Clock.systemUTC()));
    assertEquals("none", GitHubCredentials.file("").mode());
  }

  private GitHubCredentials withApp(String selection, java.util.List<String> bodies) throws Exception {
    var keys = KeyPairGenerator.getInstance("RSA");
    keys.initialize(2048);
    Path key = dir.resolve("scoped.pem");
    Files.writeString(
        key,
        "-----BEGIN PRIVATE KEY-----\n"
            + Base64.getEncoder().encodeToString(keys.generateKeyPair().getPrivate().getEncoded())
            + "\n-----END PRIVATE KEY-----\n");
    return new GitHubCredentials(
        "", "1", key.toString(), "2",
        new GitHubCredentials.Exchange() {
          @Override
          public String tokenResponse(String installation, String jwt) {
            throw new AssertionError("the unlimited call must not be used");
          }

          @Override
          public String tokenResponse(String installation, String jwt, String body) {
            bodies.add(body);
            return "{\"token\":\"ghs_x\",\"expires_at\":\"2099-01-01T00:00:00Z\",\"repository_selection\":\"" + selection + "\"}";
          }
        },
        Clock.systemUTC());
  }

  @Test
  void anExecutorTokenIsLimitedToItsRepository() throws Exception {
    var bodies = new ArrayList<String>();
    var token = withApp("selected", bodies).repositoryToken("owner/forja");
    assertEquals("ghs_x", token.get("token"));
    assertEquals("{\"repositories\":[\"forja\"]}", bodies.get(0));
  }

  @Test
  void aWiderTokenThanRequestedIsRefused() throws Exception {
    assertThrows(IllegalStateException.class, () -> withApp("all", new ArrayList<>()).repositoryToken("owner/forja"));
  }

  @Test
  void withoutAnAppThereIsNoExecutorToken() throws Exception {
    assertNull(GitHubCredentials.file("").repositoryToken("owner/forja"));
  }
}
