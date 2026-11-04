package io.agentfarm;

import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.databind.JsonNode;
import io.agentfarm.core.*;
import java.net.http.*;
import java.nio.file.*;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.*;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class GitHubAppSetupTest {
  @TempDir Path dir;
  Store store;
  GitHubCredentials credentials;
  KeyPair keys;
  List<HttpRequest> calls = new ArrayList<>();
  int status = 201;
  Instant now = Instant.parse("2026-11-03T10:00:00Z");
  Clock clock =
      new Clock() {
        public Instant instant() {
          return now;
        }

        public ZoneId getZone() {
          return ZoneOffset.UTC;
        }

        public Clock withZone(ZoneId z) {
          return this;
        }
      };
  GitHubAppSetup setup;

  /** The inner PKCS#1 of an RSA key, as GitHub hands it out: strips Java's PKCS#8 wrapping. */
  static String pkcs1Pem(PrivateKey key) {
    byte[] der = key.getEncoded();
    int at = 26;
    assertEquals((byte) 0x30, der[at], "PKCS#1 starts here in a 2048-bit PKCS#8 key");
    return "-----BEGIN RSA PRIVATE KEY-----\n"
        + Base64.getMimeEncoder().encodeToString(Arrays.copyOfRange(der, at, der.length))
        + "\n-----END RSA PRIVATE KEY-----\n";
  }

  @SuppressWarnings("unchecked")
  HttpResponse<String> response(int code, String body) {
    HttpResponse<String> r = org.mockito.Mockito.mock(HttpResponse.class);
    org.mockito.Mockito.when(r.statusCode()).thenReturn(code);
    org.mockito.Mockito.when(r.body()).thenReturn(body);
    return r;
  }

  @BeforeEach
  void setUp() throws Exception {
    var generator = KeyPairGenerator.getInstance("RSA");
    generator.initialize(2048);
    keys = generator.generateKeyPair();
    store = new Store(dir.resolve("farm.db").toString());
    credentials = new GitHubCredentials("", "", "", "", dir.resolve("farm.db").toString());
    setup =
        new GitHubAppSetup(
            store,
            credentials,
            dir,
            request -> {
              calls.add(request);
              if (request.uri().getPath().endsWith("/conversions"))
                return response(
                    status,
                    Json.encode(
                        Json.obj(
                            "id",
                            4242,
                            "slug",
                            "agentfarm-abc",
                            "pem",
                            pkcs1Pem(keys.getPrivate()))));
              return response(status == 201 ? 200 : status, "{}");
            },
            clock);
  }

  String state(String url) {
    return url.substring(url.indexOf("state=") + 6);
  }

  @Test
  void theManifestAsksOnlyForWhatIntegrationNeeds() {
    var started = setup.start("http://localhost:8080");
    assertTrue(
        started.get("action").toString().startsWith("https://github.com/settings/apps/new?state="));
    JsonNode manifest = Json.parse(started.get("manifest").toString());
    assertEquals(
        "http://localhost:8080/github-app/created", manifest.path("redirect_url").asText());
    assertEquals("http://localhost:8080/github-app/installed", manifest.path("setup_url").asText());
    assertFalse(manifest.path("public").asBoolean(true));
    assertFalse(
        manifest.path("hook_attributes").path("active").asBoolean(true), "no webhook needed");
    assertEquals("write", manifest.path("default_permissions").path("contents").asText());
    assertEquals("read", manifest.path("default_permissions").path("checks").asText());
    assertEquals(
        "write",
        manifest.path("default_permissions").path("issues").asText(),
        "the executor opens issues");
    assertEquals(
        "write",
        manifest.path("default_permissions").path("workflows").asText(),
        "and may add the CI template");
    assertTrue(manifest.path("default_permissions").path("administration").isMissingNode());
    assertTrue(manifest.path("name").asText().matches("AgentFarm [A-Za-z0-9]{6}"));
  }

  @Test
  void createsInstallsAndLeavesTheCredentialReadyWithoutCopyingAnything() throws Exception {
    assertEquals("none", credentials.mode());
    String install =
        setup.created("code-1", state(setup.start("http://p").get("action").toString()), "admin");
    assertTrue(
        install.startsWith("https://github.com/apps/agentfarm-abc/installations/new?state="),
        install);
    assertEquals("none", credentials.mode(), "not usable until it is installed");
    assertTrue(calls.get(0).uri().getPath().endsWith("/app-manifests/code-1/conversions"));

    setup.installed("777", state(install), "admin");
    assertEquals("github-app", credentials.mode());
    assertTrue(credentials.configured());
    assertTrue(calls.get(1).uri().getPath().endsWith("/app/installations/777"));
    String auth = calls.get(1).headers().firstValue("Authorization").orElseThrow();
    assertEquals(3, auth.split("\\.").length, "asks GitHub with a JWT made with the stored key");
    for (Path file : List.of(dir.resolve("github-app.pem"), dir.resolve("github-app.json")))
      assertEquals(
          PosixFilePermissions.fromString("rw-------"),
          Files.getPosixFilePermissions(file),
          file.toString());
    assertTrue(Files.readString(dir.resolve("github-app.pem")).contains("BEGIN PRIVATE KEY"));
    assertEquals("agentfarm-abc", setup.status().get("slug"));
    assertFalse(Json.encode(setup.status()).contains("PRIVATE"), "the key never leaves the volume");
    boolean audited =
        store.tx(
            c -> Store.one(c, "SELECT 1 FROM audit WHERE action='github-app.install'") != null);
    assertTrue(audited);
  }

  @Test
  void aStateWorksOnceAndExpires() throws Exception {
    assertThrows(
        IllegalArgumentException.class,
        () -> setup.created("c", "made-up", "admin"),
        "no forged link");
    assertThrows(IllegalArgumentException.class, () -> setup.created("c", null, "admin"));
    String state = state(setup.start("http://p").get("action").toString());
    setup.created("c", state, "admin");
    assertThrows(
        IllegalArgumentException.class, () -> setup.created("c", state, "admin"), "single use");
    String old = state(setup.start("http://p").get("action").toString());
    now = now.plus(Duration.ofHours(2));
    assertThrows(IllegalArgumentException.class, () -> setup.created("c", old, "admin"), "expired");
    assertTrue(calls.size() == 1, "refused before reaching GitHub");
  }

  @Test
  void refusesWhatGitHubDoesNotConfirm() throws Exception {
    status = 422;
    String state = state(setup.start("http://p").get("action").toString());
    assertThrows(IllegalStateException.class, () -> setup.created("c", state, "admin"));
    assertFalse(Files.exists(dir.resolve("github-app.json")), "nothing is stored");
    status = 201;
    String install =
        setup.created("c", state(setup.start("http://p").get("action").toString()), "admin");
    assertThrows(
        IllegalArgumentException.class,
        () -> setup.installed("no-numeric", state(install), "admin"));
    String again =
        setup.created("c", state(setup.start("http://p").get("action").toString()), "admin");
    status = 404;
    assertThrows(
        IllegalArgumentException.class,
        () -> setup.installed("9", state(again), "admin"),
        "someone else's installation");
    assertEquals("none", credentials.mode());
  }

  @Test
  void theConvertedKeySignsWithTheOriginalKey() throws Exception {
    String pem = GitHubAppSetup.pkcs8(pkcs1Pem(keys.getPrivate()));
    byte[] der =
        Base64.getDecoder().decode(pem.replaceAll("-----[A-Z ]+-----", "").replaceAll("\\s", ""));
    assertArrayEquals(keys.getPrivate().getEncoded(), der);
    String already = "-----BEGIN PRIVATE KEY-----\nabc\n-----END PRIVATE KEY-----\n";
    assertEquals(already, GitHubAppSetup.pkcs8(already));
    assertThrows(IllegalArgumentException.class, () -> GitHubAppSetup.pkcs8("no es una clave"));
  }
}
