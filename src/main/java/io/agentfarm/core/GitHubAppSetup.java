package io.agentfarm.core;

import static io.agentfarm.core.Json.*;

import java.net.URI;
import java.net.http.*;
import java.nio.file.*;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.SecureRandom;
import java.time.*;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Creates the panel's GitHub App through GitHub's manifest flow, so the owner never has to copy a
 * private key. Each step carries a single-use state: otherwise anyone could send the owner a link
 * that connects the panel to an App of theirs.
 */
@Component
public class GitHubAppSetup {
  /** What docs/TEAM.md lists: only what integration and the triggers read or write. */
  static final Map<String, String> PERMISSIONS =
      // Issues and workflows are for the executor: it opens the issue and may add the CI template.
      Map.of(
          "contents", "write",
          "pull_requests", "write",
          "issues", "write",
          "workflows", "write",
          "actions", "read",
          "checks", "read",
          "statuses", "read",
          "vulnerability_alerts", "read",
          "metadata", "read");

  private static final Duration STATE_LIFETIME = Duration.ofHours(1);

  /** One call to GitHub's API; replaced in tests. */
  @FunctionalInterface
  public interface Api {
    HttpResponse<String> send(HttpRequest request) throws Exception;
  }

  private final Store store;
  private final GitHubCredentials credentials;
  private final Path config, key;
  private final Api api;
  private final Clock clock;
  private final Map<String, Instant> states = new ConcurrentHashMap<>();

  @Autowired
  public GitHubAppSetup(
      Store store, GitHubCredentials credentials, @Value("${agentfarm.db}") String db) {
    this(
        store,
        credentials,
        Path.of(db).toAbsolutePath().getParent(),
        request -> HttpClient.newHttpClient().send(request, HttpResponse.BodyHandlers.ofString()),
        Clock.systemUTC());
  }

  public GitHubAppSetup(
      Store store, GitHubCredentials credentials, Path dir, Api api, Clock clock) {
    this.store = store;
    this.credentials = credentials;
    config = dir.resolve(GitHubCredentials.STORED_APP);
    key = dir.resolve(GitHubCredentials.STORED_KEY);
    this.api = api;
    this.clock = clock;
  }

  private String newState() {
    byte[] bytes = new byte[24];
    new SecureRandom().nextBytes(bytes);
    String state = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    states.put(state, clock.instant().plus(STATE_LIFETIME));
    return state;
  }

  private void consume(String state) {
    Instant until = state == null ? null : states.remove(state);
    if (until == null || clock.instant().isAfter(until))
      throw new IllegalArgumentException(
          "Este enlace ya no vale. Empieza otra vez desde Gobierno.");
  }

  private static String web() {
    String configured = System.getenv("AGENTFARM_GITHUB_WEB");
    return configured == null || configured.isBlank() ? "https://github.com" : configured;
  }

  /** What the browser posts to GitHub. The name must be unique across GitHub, hence the suffix. */
  public Map<String, Object> start(String panel) {
    String suffix = newState().substring(0, 6).replaceAll("[^A-Za-z0-9]", "x");
    states.keySet().removeIf(s -> states.get(s).isBefore(clock.instant()));
    String state = newState();
    var manifest =
        obj(
            "name",
            "AgentFarm " + suffix,
            "url",
            panel,
            "redirect_url",
            panel + "/github-app/created",
            "setup_url",
            panel + "/github-app/installed",
            "public",
            false,
            "default_permissions",
            PERMISSIONS,
            "default_events",
            List.of(),
            "hook_attributes",
            obj("url", panel + "/github-app/unused", "active", false));
    return obj("action", web() + "/settings/apps/new?state=" + state, "manifest", encode(manifest));
  }

  /**
   * GitHub came back with a one-time code: it turns into the App and its key. Returns where to
   * install it.
   */
  public String created(String code, String state, String actor) throws Exception {
    consume(state);
    if (code == null || !code.matches("[A-Za-z0-9_-]{1,100}"))
      throw new IllegalArgumentException("GitHub no devolvió un código válido.");
    var response =
        api.send(
            HttpRequest.newBuilder(
                    URI.create(GitHubApi.base() + "/app-manifests/" + code + "/conversions"))
                .timeout(Duration.ofSeconds(20))
                .header("Accept", "application/vnd.github+json")
                .POST(HttpRequest.BodyPublishers.noBody())
                .build());
    if (response.statusCode() != 201)
      throw new IllegalStateException(
          "GitHub rechazó crear la App (HTTP " + response.statusCode() + ").");
    var app = parse(response.body());
    String id = app.path("id").asText(), slug = app.path("slug").asText();
    if (!id.matches("[0-9]{1,12}") || !slug.matches("[a-z0-9-]{1,100}"))
      throw new IllegalStateException("GitHub devolvió una App que no se reconoce.");
    writePrivate(key, pkcs8(app.path("pem").asText()));
    writePrivate(config, encode(obj("app_id", id, "slug", slug)));
    store.tx(c -> {
      Store.audit(c, actor, "github-app.create", slug);
      return null;
    });
    return web() + "/apps/" + slug + "/installations/new?state=" + newState();
  }

  /** Only an installation of this App can be stored: GitHub is asked with the App's own key. */
  public void installed(String installation, String state, String actor) throws Exception {
    consume(state);
    if (installation == null || !installation.matches("[0-9]{1,15}"))
      throw new IllegalArgumentException("GitHub no devolvió una instalación válida.");
    if (!Files.exists(config)) throw new IllegalStateException("Antes hay que crear la App.");
    var stored = parse(Files.readString(config));
    String jwt = credentials.jwtFor(stored.path("app_id").asText(), key.toString());
    var response =
        api.send(
            HttpRequest.newBuilder(
                    URI.create(GitHubApi.base() + "/app/installations/" + installation))
                .timeout(Duration.ofSeconds(20))
                .header("Authorization", "Bearer " + jwt)
                .header("Accept", "application/vnd.github+json")
                .GET()
                .build());
    if (response.statusCode() != 200)
      throw new IllegalArgumentException(
          "Esa instalación no es de esta App (HTTP " + response.statusCode() + ").");
    writePrivate(
        config,
        encode(
            obj(
                "app_id",
                stored.path("app_id").asText(),
                "slug",
                stored.path("slug").asText(),
                "installation_id",
                installation)));
    credentials.forgetToken();
    store.tx(c -> {
      Store.audit(c, actor, "github-app.install", installation);
      return null;
    });
  }

  public Map<String, Object> status() throws Exception {
    String slug = "";
    if (Files.isReadable(config)) slug = parse(Files.readString(config)).path("slug").asText();
    return obj("mode", credentials.mode(), "slug", slug);
  }

  private static void writePrivate(Path file, String content) throws Exception {
    Path temp =
        Files.createTempFile(
            file.getParent(),
            ".github-app-",
            ".tmp",
            PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------")));
    Files.writeString(temp, content);
    Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
  }

  /**
   * GitHub hands out PKCS#1 and Java reads PKCS#8: wrapping it saves the owner a step with openssl.
   */
  public static String pkcs8(String pem) {
    if (!pem.contains("BEGIN RSA PRIVATE KEY")) {
      if (pem.contains("BEGIN PRIVATE KEY")) return pem;
      throw new IllegalArgumentException("GitHub devolvió una clave que no se reconoce.");
    }
    byte[] pkcs1 =
        Base64.getDecoder().decode(pem.replaceAll("-----[A-Z ]+-----", "").replaceAll("\\s", ""));
    byte[] algorithm = {
      0x02,
      0x01,
      0x00, // version 0
      0x30,
      0x0d,
      0x06,
      0x09,
      0x2a,
      (byte) 0x86,
      0x48,
      (byte) 0x86,
      (byte) 0xf7,
      0x0d,
      0x01,
      0x01,
      0x01,
      0x05,
      0x00 // rsaEncryption
    };
    byte[] octets = der((byte) 0x04, pkcs1);
    byte[] body = new byte[algorithm.length + octets.length];
    System.arraycopy(algorithm, 0, body, 0, algorithm.length);
    System.arraycopy(octets, 0, body, algorithm.length, octets.length);
    return "-----BEGIN PRIVATE KEY-----\n"
        + Base64.getMimeEncoder().encodeToString(der((byte) 0x30, body))
        + "\n-----END PRIVATE KEY-----\n";
  }

  private static byte[] der(byte tag, byte[] content) {
    int n = content.length;
    byte[] length =
        n < 128
            ? new byte[] {(byte) n}
            : n < 256
                ? new byte[] {(byte) 0x81, (byte) n}
                : new byte[] {(byte) 0x82, (byte) (n >> 8), (byte) n};
    byte[] out = new byte[1 + length.length + n];
    out[0] = tag;
    System.arraycopy(length, 0, out, 1, length.length);
    System.arraycopy(content, 0, out, 1 + length.length, n);
    return out;
  }
}
