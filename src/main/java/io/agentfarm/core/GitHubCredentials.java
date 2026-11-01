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
 * cached here) or a personal token file. Tokens are never logged or returned by the API.
 */
@Component
public class GitHubCredentials {
  private final String tokenFile, appId, keyFile, installation;
  private final Exchange exchange;
  private final Clock clock;

  /** Trades an app JWT for an installation token response body; replaced in tests. */
  @FunctionalInterface
  public interface Exchange {
    String tokenResponse(String installation, String jwt) throws Exception;
  }
  private String cached;
  private Instant expires = Instant.EPOCH;

  @org.springframework.beans.factory.annotation.Autowired
  public GitHubCredentials(
      @Value("${agentfarm.github-token-file:}") String tokenFile,
      @Value("${agentfarm.github-app-id:}") String appId,
      @Value("${agentfarm.github-app-key-file:}") String keyFile,
      @Value("${agentfarm.github-installation-id:}") String installation) {
    this(tokenFile, appId, keyFile, installation, GitHubCredentials::github, Clock.systemUTC());
  }

  public GitHubCredentials(
      String tokenFile, String appId, String keyFile, String installation, Exchange exchange, Clock clock) {
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

  boolean app() {
    return !appId.isBlank();
  }

  public String mode() {
    return app() ? "github-app" : configured() ? "token-file" : "none";
  }

  public boolean configured() {
    if (app()) return Files.isReadable(Path.of(keyFile));
    return !tokenFile.isBlank() && Files.isReadable(Path.of(tokenFile));
  }

  public synchronized String token() throws Exception {
    if (!app()) return Files.readString(Path.of(tokenFile)).trim();
    if (cached != null && clock.instant().isBefore(expires.minusSeconds(300))) return cached;
    var body = parse(exchange.tokenResponse(installation, jwt()));
    cached = body.path("token").asText();
    expires = Instant.parse(body.path("expires_at").asText());
    return cached;
  }

  static String github(String installation, String jwt) throws Exception {
    var response =
        HttpClient.newHttpClient()
            .send(
                HttpRequest.newBuilder(
                        URI.create(
                            "https://api.github.com/app/installations/" + installation + "/access_tokens"))
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
