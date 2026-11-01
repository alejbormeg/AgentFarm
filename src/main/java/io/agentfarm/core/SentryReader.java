package io.agentfarm.core;

import static io.agentfarm.core.Json.*;

import com.fasterxml.jackson.databind.JsonNode;
import java.net.URI;
import java.net.http.*;
import java.nio.file.*;
import java.time.Duration;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/** Read-only Sentry API access with an owner token file; feeds the sentry trigger. */
@Component
public class SentryReader {
  @FunctionalInterface
  public interface Source {
    JsonNode get(String path) throws Exception;
  }

  private final String tokenFile, base;
  private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();

  public SentryReader(
      @Value("${agentfarm.sentry-token-file:}") String tokenFile,
      @Value("${agentfarm.sentry-url:https://sentry.io}") String base) {
    if (!base.startsWith("https://")) throw new IllegalArgumentException("Sentry URL must use https");
    this.tokenFile = tokenFile;
    this.base = base.replaceAll("/+$", "");
  }

  public boolean configured() {
    return !tokenFile.isBlank() && Files.isReadable(Path.of(tokenFile));
  }

  public JsonNode get(String path) throws Exception {
    if (!path.startsWith("/api/0/")) throw new IllegalArgumentException("Invalid Sentry path");
    var response =
        http.send(
            HttpRequest.newBuilder(URI.create(base + path))
                .timeout(Duration.ofSeconds(20))
                .header("Authorization", "Bearer " + Files.readString(Path.of(tokenFile)).trim())
                .GET()
                .build(),
            HttpResponse.BodyHandlers.ofString());
    if (response.statusCode() != 200)
      throw new IllegalStateException("Sentry HTTP " + response.statusCode());
    return parse(response.body());
  }
}
