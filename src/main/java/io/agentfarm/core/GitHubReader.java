package io.agentfarm.core;

import static io.agentfarm.core.Json.*;

import com.fasterxml.jackson.databind.JsonNode;
import java.net.URI;
import java.net.http.*;
import java.nio.file.*;
import java.time.Duration;
import org.springframework.stereotype.Component;

/** Read-only GitHub REST access with the owner's token file; used by triggers. */
@Component
public class GitHubReader {
  /** Seam for tests. */
  @FunctionalInterface
  public interface Source {
    JsonNode get(String repo, String path) throws Exception;
  }

  private final GitHubCredentials credentials;
  private final HttpClient http =
      HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();

  @org.springframework.beans.factory.annotation.Autowired
  public GitHubReader(GitHubCredentials credentials) {
    this.credentials = credentials;
  }

  public GitHubReader(String tokenFile) {
    this(GitHubCredentials.file(tokenFile));
  }

  public boolean configured() {
    return credentials.configured();
  }

  public JsonNode get(String repo, String path) throws Exception {
    if (!repo.matches("[A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+")
        || !(path.isEmpty() || path.startsWith("/")))
      throw new IllegalArgumentException("Invalid GitHub path");
    var response =
        http.send(
            HttpRequest.newBuilder(URI.create(GitHubApi.base() + "/repos/" + repo + path))
                .timeout(Duration.ofSeconds(20))
                .header("Authorization", "Bearer " + credentials.token())
                .header("Accept", "application/vnd.github+json")
                .GET()
                .build(),
            HttpResponse.BodyHandlers.ofString());
    if (response.statusCode() != 200)
      throw new IllegalStateException("GitHub HTTP " + response.statusCode() + " for " + path);
    return parse(response.body());
  }
}
