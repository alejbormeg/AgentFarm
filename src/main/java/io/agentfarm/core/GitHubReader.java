package io.agentfarm.core;

import static io.agentfarm.core.Json.*;

import com.fasterxml.jackson.databind.JsonNode;
import java.net.URI;
import java.net.http.*;
import java.nio.file.*;
import java.time.Duration;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/** Read-only GitHub REST access with the owner's token file; used by triggers. */
@Component
public class GitHubReader {
  /** Seam for tests. */
  @FunctionalInterface
  public interface Source {
    JsonNode get(String repo, String path) throws Exception;
  }

  private final String tokenFile;
  private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();

  public GitHubReader(@Value("${agentfarm.github-token-file:}") String tokenFile) {
    this.tokenFile = tokenFile;
  }

  public boolean configured() {
    return !tokenFile.isBlank() && Files.isReadable(Path.of(tokenFile));
  }

  public JsonNode get(String repo, String path) throws Exception {
    if (!repo.matches("[A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+") || !path.startsWith("/"))
      throw new IllegalArgumentException("Invalid GitHub path");
    var response =
        http.send(
            HttpRequest.newBuilder(URI.create("https://api.github.com/repos/" + repo + path))
                .timeout(Duration.ofSeconds(20))
                .header("Authorization", "Bearer " + Files.readString(Path.of(tokenFile)).trim())
                .header("Accept", "application/vnd.github+json")
                .GET()
                .build(),
            HttpResponse.BodyHandlers.ofString());
    if (response.statusCode() != 200)
      throw new IllegalStateException("GitHub HTTP " + response.statusCode() + " for " + path);
    return parse(response.body());
  }
}
