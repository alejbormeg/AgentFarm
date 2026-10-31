package io.agentfarm;

import static io.agentfarm.core.Json.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import io.agentfarm.core.*;
import java.net.http.*;
import java.nio.file.*;
import java.time.Clock;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;

class IntegrationPublisherTest {
  @TempDir Path dir;
  WorkflowService work;
  IntegrationPublisher publisher;
  AtomicInteger puts = new AtomicInteger();
  String sha = "a".repeat(40), candidate = sha, ci = "success";
  boolean confirmed = true, remoteMerged = false, disconnect = false;
  int githubStatus = 200;

  com.fasterxml.jackson.databind.JsonNode n(Object... v) {
    return MAPPER.valueToTree(obj(v));
  }

  String command(Object claim) {
    return MAPPER.valueToTree(claim).path("command").path("id").asText();
  }

  @BeforeEach
  void setup() throws Exception {
    work = new WorkflowService(new Store(dir.resolve("db").toString()), Clock.systemUTC());
    work.create(
        n(
            "request_id",
            "broker-test",
            "repository",
            "owner/repo",
            "title",
            "Task",
            "request",
            "Fix"),
        "owner");
    work.report(
        command(work.claim("worker")),
        n(
            "state",
            "proposed",
            "proposal",
            obj(
                "summary",
                "Fix",
                "acceptance",
                "Test",
                "as_is",
                "A-->B",
                "to_be",
                "A-->C",
                "unit_test",
                "unit",
                "integration_test",
                "integration")),
        "worker");
    work.action("broker-test", "implement", n("version", 1), "owner");
    work.report(
        command(work.claim("worker")),
        n(
            "state",
            "review",
            "pr",
            1,
            "sha",
            sha,
            "evidence",
            obj("sha", sha, "passed", true, "ci_checks", List.of("unit"))),
        "worker");
    work.action("broker-test", "merge", n("sha", sha), "owner");
    Path token = dir.resolve("token");
    Files.writeString(token, "test-secret");
    HttpClient http = mock(HttpClient.class);
    when(http.send(
            any(HttpRequest.class),
            org.mockito.ArgumentMatchers.<HttpResponse.BodyHandler<String>>any()))
        .thenAnswer(
            invocation -> {
              HttpRequest request = invocation.getArgument(0);
              String body;
              if (request.method().equals("PUT")) {
                puts.incrementAndGet();
                if (disconnect) throw new java.io.IOException("uncertain");
                body = encode(obj("merged", confirmed));
              } else if (request.uri().getPath().endsWith("/check-runs"))
                body =
                    encode(
                        obj(
                            "total_count",
                            1,
                            "check_runs",
                            List.of(obj("name", "unit", "status", "completed", "conclusion", ci))));
              else if (request.uri().getPath().endsWith("/status")) body = "{\"total_count\":0}";
              else if (request.uri().getPath().endsWith("/reviews")) body = "[]";
              else
                body =
                    encode(
                        obj(
                            "head",
                            obj("sha", candidate),
                            "state",
                            remoteMerged ? "closed" : "open",
                            "draft",
                            false,
                            "merged",
                            remoteMerged));
              HttpResponse<String> response = mock(HttpResponse.class);
              when(response.statusCode()).thenReturn(githubStatus);
              when(response.body()).thenReturn(body);
              return response;
            });
    publisher = new IntegrationPublisher(work, token.toString(), http);
  }

  @Test
  void onlyConfirmedMergeCompletesAndPollingDoesNotRepeat() {
    publisher.tick();
    publisher.tick();
    assertEquals("integrated", work.get("broker-test").get("state"));
    assertEquals(1, puts.get());
  }

  @Test
  void changedCommitAndFailedCiCannotBeMerged() {
    candidate = "b".repeat(40);
    publisher.tick();
    assertEquals("review", work.get("broker-test").get("state"));
    assertEquals(0, puts.get());
    candidate = sha;
    ci = "failure";
    work.action("broker-test", "merge", n("sha", sha), "owner");
    publisher.tick();
    assertEquals("review", work.get("broker-test").get("state"));
    assertEquals(0, puts.get());
  }

  @Test
  void githubPermissionAndUnconfirmedResultRemainReview() {
    githubStatus = 403;
    publisher.tick();
    assertEquals("review", work.get("broker-test").get("state"));
    assertEquals(0, puts.get());
    githubStatus = 200;
    confirmed = false;
    work.action("broker-test", "merge", n("sha", sha), "owner");
    publisher.tick();
    assertEquals("review", work.get("broker-test").get("state"));
  }

  @Test
  void uncertainTransportReconcilesOnlyAfterExplicitRetry() {
    disconnect = true;
    publisher.tick();
    assertEquals("review", work.get("broker-test").get("state"));
    assertEquals(1, puts.get());
    publisher.tick();
    assertEquals(1, puts.get());
    remoteMerged = true;
    work.action("broker-test", "merge", n("sha", sha), "owner");
    publisher.tick();
    assertEquals("integrated", work.get("broker-test").get("state"));
    assertEquals(1, puts.get());
  }
}
