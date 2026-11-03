package io.agentfarm.core;

import static io.agentfarm.core.Json.*;

import java.net.URI;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.*;
import java.security.spec.PKCS8EncodedKeySpec;
import java.time.*;
import java.util.Base64;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * GitHub token for the control plane: a GitHub App installation token (preferred, minted and
 * cached here) or a personal token file. The App comes from the environment or from what
 * GitHubAppSetup stored beside the database. Tokens are never logged or returned by the API.
 */
@Component
public class GitHubCredentials {
  static final String STORED_APP = "github-app.json", STORED_KEY = "github-app.pem";
  private final String tokenFile, appId, keyFile, installation;
  private final Path storedDir;
  private final Exchange exchange;
  private final Clock clock;

  /** Trades an app JWT for an installation token response body; replaced in tests. */
  @FunctionalInterface
  public interface Exchange {
    String tokenResponse(String installation, String jwt) throws Exception;
  }
  private record App(String id, String key, String installation) {}
  private String cached;
  private Instant expires = Instant.EPOCH;

  @org.springframework.beans.factory.annotation.Autowired
  public GitHubCredentials(
      @Value("${agentfarm.github-token-file:}") String tokenFile,
      @Value("${agentfarm.github-app-id:}") String appId,
      @Value("${agentfarm.github-app-key-file:}") String keyFile,
      @Value("${agentfarm.github-installation-id:}") String installation,
      @Value("${agentfarm.db:}") String db) {
    this(
        tokenFile,
        appId,
        keyFile,
        installation,
        GitHubCredentials::github,
        Clock.systemUTC(),
        db == null || db.isBlank() ? null : Path.of(db).toAbsolutePath().getParent());
  }

  public GitHubCredentials(
      String tokenFile, String appId, String keyFile, String installation, Exchange exchange, Clock clock) {
    this(tokenFile, appId, keyFile, installation, exchange, clock, null);
  }

  private GitHubCredentials(
      String tokenFile, String appId, String keyFile, String installation, Exchange exchange, Clock clock, Path storedDir) {
    this.storedDir = storedDir;
    if (!appId.isBlank() && (!appId.matches("[0-9]{1,12}") || !installation.matches("[0-9]{1,15}")))
      throw new IllegalArgumentException("GitHub App id and installation id must be numeric");
    this.tokenFile = tokenFile;
    this.appId = appId;
    this.keyFile = keyFile;
    this.installation = installation;
    this.exchange = exchange;
    this.clock = clock;
  }

  public static GitHubCredentials file(String tokenFile) {
    return new GitHubCredentials(tokenFile, "", "", "", GitHubCredentials::github, Clock.systemUTC());
  }

  /** The operator's environment wins. A stored App counts once it has its installation. */
  private App app() {
    if (!appId.isBlank()) return new App(appId, keyFile, installation);
    if (storedDir == null || !Files.isReadable(storedDir.resolve(STORED_APP))) return null;
    try {
      var stored = parse(Files.readString(storedDir.resolve(STORED_APP)));
      String id = stored.path("app_id").asText(), at = stored.path("installation_id").asText();
      return id.isBlank() || at.isBlank() ? null : new App(id, storedDir.resolve(STORED_KEY).toString(), at);
    } catch (java.io.IOException | IllegalArgumentException e) {
      return null;
    }
  }

  public String mode() {
    return app() != null ? "github-app" : configured() ? "token-file" : "none";
  }

  /** The kind of credential, for advice; never its value. */
  public String kind() {
    if (app() != null) return "github-app";
    if (!configured()) return "none";
    try {
      String token = Files.readString(Path.of(tokenFile)).trim();
      return token.startsWith("github_pat_")
          ? "fine-grained-token"
          : token.startsWith("ghp_") ? "classic-token" : "token";
    } catch (java.io.IOException e) {
      return "none";
    }
  }

  public boolean configured() {
    var app = app();
    if (app != null) return Files.isReadable(Path.of(app.key()));
    return !tokenFile.isBlank() && Files.isReadable(Path.of(tokenFile));
  }

  public synchronized String token() throws Exception {
    var app = app();
    if (app == null) return Files.readString(Path.of(tokenFile)).trim();
    if (cached != null && clock.instant().isBefore(expires.minusSeconds(300))) return cached;
    var body = parse(exchange.tokenResponse(app.installation(), jwtFor(app.id(), app.key())));
    cached = body.path("token").asText();
    expires = Instant.parse(body.path("expires_at").asText());
    return cached;
  }

  /** A token minted for another App must not outlive the change to this one. */
  synchronized void forgetToken() {
    cached = null;
    expires = Instant.EPOCH;
  }

  static String github(String installation, String jwt) throws Exception {
    var response =
        HttpClient.newHttpClient()
            .send(
                HttpRequest.newBuilder(
                        URI.create(GitHubApi.base() + "/app/installations/" + installation + "/access_tokens"))
                    .timeout(Duration.ofSeconds(20))
                    .header("Authorization", "Bearer " + jwt)
                    .header("Accept", "application/vnd.github+json")
                    .POST(HttpRequest.BodyPublishers.noBody())
                    .build(),
                HttpResponse.BodyHandlers.ofString());
    if (response.statusCode() != 201)
      throw new IllegalStateException("GitHub App token request failed (HTTP " + response.statusCode() + ")");
    return response.body();
  }

  /** RS256 app JWT; GitHub allows at most 10 minutes, so it is backdated for clock skew. */
  String jwt() throws Exception {
    var app = app();
    return jwtFor(app.id(), app.key());
  }

  String jwtFor(String appId, String keyFile) throws Exception {
    long now = clock.instant().getEpochSecond();
    var url = Base64.getUrlEncoder().withoutPadding();
    String header = url.encodeToString("{\"alg\":\"RS256\",\"typ\":\"JWT\"}".getBytes(StandardCharsets.UTF_8));
    String claims =
        url.encodeToString(
            encode(obj("iat", now - 60, "exp", now + 540, "iss", appId)).getBytes(StandardCharsets.UTF_8));
    var signer = Signature.getInstance("SHA256withRSA");
    signer.initSign(privateKey(Files.readString(Path.of(keyFile))));
    signer.update((header + "." + claims).getBytes(StandardCharsets.US_ASCII));
    return header + "." + claims + "." + url.encodeToString(signer.sign());
  }

  static PrivateKey privateKey(String pem) throws Exception {
    if (pem.contains("BEGIN RSA PRIVATE KEY"))
      throw new IllegalArgumentException(
          "Convert the GitHub App key to PKCS#8: openssl pkcs8 -topk8 -nocrypt -in app.pem -out app-pkcs8.pem");
    String base64 = pem.replaceAll("-----[A-Z ]+-----", "").replaceAll("\\s", "");
    return KeyFactory.getInstance("RSA").generatePrivate(new PKCS8EncodedKeySpec(Base64.getDecoder().decode(base64)));
  }
}
