package io.agentfarm.core;

import static io.agentfarm.core.Json.*;

import com.fasterxml.jackson.databind.JsonNode;
import java.net.*;
import java.net.http.*;
import java.nio.file.*;
import java.time.Duration;
import java.util.*;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

/** Owner-side integration broker; node credentials cannot invoke GitHub merge. */
@Service
public class IntegrationPublisher {
  private final WorkflowService work;
  private final GitHubCredentials credentials;
  private final HttpClient http;

  @org.springframework.beans.factory.annotation.Autowired
  public IntegrationPublisher(WorkflowService work, GitHubCredentials credentials) {
    this(work, credentials, HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build());
  }

  public IntegrationPublisher(WorkflowService work, String tokenFile, HttpClient http) {
    this(work, GitHubCredentials.file(tokenFile), http);
  }

  public IntegrationPublisher(
      WorkflowService work, GitHubCredentials credentials, HttpClient http) {
    this.work = work;
    this.credentials = credentials;
    this.http = http;
  }

  public boolean configured() {
    return credentials.configured();
  }

  private JsonNode github(String repo, String path, Object body) throws Exception {
    String secret = credentials.token();
    var request =
        HttpRequest.newBuilder(URI.create(GitHubApi.base() + "/repos/" + repo + path))
            .timeout(Duration.ofSeconds(20))
            .header("Authorization", "Bearer " + secret)
            .header("Accept", "application/vnd.github+json");
    if (body == null) request.GET();
    else
      request
          .header("Content-Type", "application/json")
          .PUT(HttpRequest.BodyPublishers.ofString(encode(body)));
    var result = http.send(request.build(), HttpResponse.BodyHandlers.ofString());
    if (result.statusCode() < 200 || result.statusCode() >= 300)
      throw new IllegalArgumentException(
          "GitHub rejected integration (HTTP " + result.statusCode() + ")");
    return parse(result.body());
  }

  @Scheduled(fixedDelay = 2000)
  public synchronized void tick() {
    if (!configured()) return;
    var claim = MAPPER.valueToTree(work.claimIntegration("control-plane"));
    if (!claim.has("command")) return;
    String command = claim.path("command").path("id").asText();
    try {
      var item = claim.path("item");
      String repo = item.path("repo").asText(),
          sha = parse(claim.path("command").path("payload").asText()).path("sha").asText();
      String pr = "/pulls/" + item.path("pr").asInt();
      var remote = github(repo, pr, null);
      if (!remote.path("head").path("sha").asText().equals(sha))
        throw new IllegalArgumentException("PR head changed; approve the current commit");
      if (!remote.path("merged").asBoolean()) {
        work.requireAcceptance(item.path("id").asText());
        validate(
            remote,
            parse(item.path("evidence").asText()),
            github(repo, "/commits/" + sha + "/check-runs?per_page=100", null),
            github(repo, "/commits/" + sha + "/status", null),
            github(repo, pr + "/reviews?per_page=100", null),
            sha);
        var beat =
            MAPPER.valueToTree(
                work.report(
                    command, MAPPER.valueToTree(obj("state", "heartbeat")), "control-plane"));
        if (beat.path("paused").asBoolean())
          throw new IllegalArgumentException("Integration paused by owner");
        if (!github(repo, pr + "/merge", obj("sha", sha, "merge_method", "squash"))
            .path("merged")
            .asBoolean()) throw new IllegalArgumentException("GitHub did not confirm integration");
      }
      work.report(
          command, MAPPER.valueToTree(obj("state", "integrated", "merged", true)), "control-plane");
    } catch (Exception e) {
      try {
        work.report(
            command,
            MAPPER.valueToTree(
                obj(
                    "state",
                    "blocked",
                    "error",
                    e instanceof IllegalArgumentException
                        ? e.getMessage()
                        : "Integration transport unavailable; retry explicitly")),
            "control-plane");
      } catch (Exception ignored) {
      }
    }
  }

  public static void validate(
      JsonNode remote,
      JsonNode evidence,
      JsonNode checks,
      JsonNode statuses,
      JsonNode reviews,
      String sha) {
    if (!sha.matches("[a-f0-9]{40}")
        || !remote.path("head").path("sha").asText().equals(sha)
        || remote.path("draft").asBoolean()
        || !remote.path("state").asText().equals("open"))
      throw new IllegalArgumentException("PR is not eligible");
    if (!evidence.path("passed").asBoolean() || !evidence.path("sha").asText().equals(sha))
      throw new IllegalArgumentException("Evidence does not match commit");
    var required = evidence.path("ci_checks");
    if (!required.isArray()
        || required.isEmpty()
        || checks.path("total_count").asInt() == 0
        || checks.path("total_count").asInt() > 100)
      throw new IllegalArgumentException("Complete expected checks required");
    for (var expected : required) {
      boolean found = false;
      // "*" binds to the project's own CI: any successful run, and every run must pass below.
      for (var check : checks.path("check_runs"))
        if ((expected.asText().equals("*") || check.path("name").asText().equals(expected.asText()))
            && check.path("conclusion").asText().equals("success")) found = true;
      if (!found)
        throw new IllegalArgumentException("Required CI not successful: " + expected.asText());
    }
    for (var check : checks.path("check_runs"))
      if (!check.path("status").asText().equals("completed")
          || !Set.of("success", "neutral", "skipped").contains(check.path("conclusion").asText()))
        throw new IllegalArgumentException("CI pending or failed");
    if (statuses.path("total_count").asInt() > 0
        && !statuses.path("state").asText().equals("success"))
      throw new IllegalArgumentException("Commit status not successful");
    if (reviews.size() >= 100) throw new IllegalArgumentException("Review pagination required");
    var last = new HashMap<String, String>();
    for (var review : reviews)
      if (!review.path("state").asText().equals("COMMENTED"))
        last.put(review.path("user").path("login").asText(), review.path("state").asText());
    if (last.containsValue("CHANGES_REQUESTED"))
      throw new IllegalArgumentException("Review requests changes");
  }
}
