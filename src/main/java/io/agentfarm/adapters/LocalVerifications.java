package io.agentfarm.adapters;

import static io.agentfarm.core.Store.*;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.agentfarm.core.GitHubCredentials;
import io.agentfarm.core.Redaction;
import io.agentfarm.core.SchemaMigrations;
import io.agentfarm.core.Store;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.BooleanSupplier;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

/**
 * Local CI (#241): for a candidate in review in a repository whose owner chose it, the panel orders
 * one verification of that exact commit, an executor runs the approved test commands in its sandbox
 * on a fresh checkout, and the panel publishes the result as check-runs with its own App.
 *
 * <p>The commands come from the approved proposal held by the panel, never from the candidate. The
 * executor's token cannot write checks; only the panel does, and only for the commit it ordered.
 * The node that runs the agents also runs this, so each check says it is not independent.
 */
@Service
public final class LocalVerifications {
  /** An order that failed to run is ordered again after this long. */
  static final long RETRY_SECONDS = 600;

  private static final int OUTPUT_TAIL = 4000;
  private static final ObjectMapper MAPPER = new ObjectMapper();

  /** Publishes one check-run; replaced in tests. */
  @FunctionalInterface
  public interface Checks {
    void publish(String repo, Map<String, Object> checkRun) throws Exception;
  }

  private final Store db;
  private final Clock clock;
  private final BooleanSupplier withApp;
  private final Checks checks;

  @Autowired
  public LocalVerifications(
      Store db, Clock clock, GitHubCredentials credentials, SchemaMigrations schema) {
    this(db, clock, () -> credentials.mode().equals("github-app"), github(credentials));
  }

  public LocalVerifications(Store db, Clock clock, BooleanSupplier withApp, Checks checks) {
    this.db = db;
    this.clock = clock;
    this.withApp = withApp;
    this.checks = checks;
  }

  /** Orders the verifications that are missing, then publishes what has run but is not out yet. */
  @Scheduled(initialDelay = 10_000, fixedDelay = 15_000)
  public void tick() {
    if (!withApp.getAsBoolean()) return;
    enqueue();
    publishPending();
  }

  /** One order per candidate commit; a commit that failed to run is ordered again later. */
  public int enqueue() {
    long now = clock.instant().getEpochSecond();
    return db.tx(c -> {
      // An order lost with its executor is retried like one that failed to run.
      exec(
          c,
          "UPDATE local_verifications SET state='error',reason='Orden interrumpida' WHERE"
              + " state='queued' AND command IN (SELECT id FROM work_commands WHERE"
              + " state='interrupted')");
      int ordered = 0;
      for (var item :
          rows(
              c,
              "SELECT i.id,i.sha,i.version,i.branch FROM work_items i JOIN repo_ci_modes m ON"
                  + " m.repo=i.repo WHERE i.state='review' AND i.sha IS NOT NULL AND NOT EXISTS"
                  + "(SELECT 1 FROM local_verifications v WHERE v.item=i.id AND v.sha=i.sha AND"
                  + " (v.state<>'error' OR v.at>?))",
              now - RETRY_SECONDS)) {
        var scope =
            one(
                c,
                "SELECT proposal FROM acceptance_scopes WHERE item=? AND version=?",
                item.get("id"),
                item.get("version"));
        if (scope == null) continue;
        var approved = MAPPER.readTree(scope.get("proposal").toString());
        var payload = new LinkedHashMap<String, Object>();
        payload.put("sha", item.get("sha"));
        payload.put("branch", item.get("branch"));
        for (String key : List.of("unit_test", "integration_test", "setup"))
          payload.put(key, approved.path(key).asText(""));
        payload.put("sandbox", approved.path("sandbox"));
        String command = UUID.randomUUID().toString();
        exec(
            c,
            "INSERT INTO work_commands(id,item,kind,payload,updated) VALUES(?,?,?,?,?)",
            command,
            item.get("id"),
            "verify",
            MAPPER.writeValueAsString(payload),
            now);
        exec(
            c,
            "INSERT OR REPLACE INTO local_verifications(item,sha,command,state,reason,results,at)"
                + " VALUES(?,?,?,'queued','','[]',?)",
            item.get("id"),
            item.get("sha"),
            command,
            now);
        audit(c, "control-plane", "ci.local-ordered", item.get("id") + " " + item.get("sha"));
        ordered++;
      }
      return ordered;
    });
  }

  /**
   * The executor's answer to a verification order it holds: the results, or why it could not run.
   * Results for another commit than the one ordered are refused.
   */
  public Map<String, Object> finish(String command, String agent, JsonNode report) {
    var done =
        db.tx(c -> {
          var cmd =
              one(
                  c,
                  "SELECT * FROM work_commands WHERE id=? AND kind='verify' AND state='claimed'"
                      + " AND claimed_by=?",
                  command,
                  agent);
          if (cmd == null)
            throw new IllegalArgumentException("Not a verification this executor holds");
          String sha = MAPPER.readTree(cmd.get("payload").toString()).path("sha").asText();
          if (!report.path("sha").asText().equals(sha))
            throw new IllegalArgumentException("Results for another commit than the one ordered");
          String error = report.path("error").asText("");
          var results = MAPPER.createArrayNode();
          if (error.isEmpty())
            for (String kind : CiPolicy.KINDS) {
              JsonNode found = null;
              for (var r : report.path("results"))
                if (r.path("kind").asText().equals(kind)) found = r;
              if (found == null)
                throw new IllegalArgumentException("Missing result for " + kind + " tests");
              results.add(
                  MAPPER.valueToTree(
                      Map.of(
                          "kind", kind,
                          "status", found.path("status").asText(),
                          "command", found.path("command").asText(""),
                          "output",
                              tail(found.path("output").asText(found.path("reason").asText(""))),
                          "node", agent)));
            }
          exec(c, "UPDATE work_commands SET state='done' WHERE id=?", command);
          exec(
              c,
              "UPDATE local_verifications SET state=?,reason=?,results=?,at=? WHERE command=?",
              error.isEmpty() ? "ran" : "error",
              Redaction.secrets(tail(error)),
              Redaction.secrets(MAPPER.writeValueAsString(results)),
              clock.instant().getEpochSecond(),
              command);
          audit(
              c,
              agent,
              error.isEmpty() ? "ci.local-ran" : "ci.local-error",
              cmd.get("item") + " " + sha);
          return error.isEmpty() ? "ran" : "error";
        });
    if (done.equals("ran")) publishPending();
    return Map.of("state", done);
  }

  /** Publishes every verification that ran and is not out yet; a failure is retried next tick. */
  public void publishPending() {
    var pending =
        db.tx(c ->
            rows(
                c,
                "SELECT v.*,i.repo FROM local_verifications v JOIN work_items i ON i.id=v.item"
                    + " WHERE v.state IN ('ran','unpublished')"));
    for (var row : pending) {
      String repo = row.get("repo").toString(), sha = row.get("sha").toString();
      String state = "published", reason = "";
      try {
        for (var r : MAPPER.readTree(row.get("results").toString()))
          checks.publish(repo, checkRun(sha, r));
      } catch (Exception e) {
        state = "unpublished";
        reason = "GitHub no aceptó el check: " + e.getMessage();
      }
      String finalState = state, finalReason = reason;
      db.tx(c -> {
        exec(
            c,
            "UPDATE local_verifications SET state=?,reason=? WHERE command=? AND state IN"
                + " ('ran','unpublished')",
            finalState,
            finalReason,
            row.get("command"));
        if (finalState.equals("published"))
          audit(c, "control-plane", "ci.local-published", row.get("item") + " " + sha);
        return null;
      });
    }
  }

  /** What the panel tells GitHub about one approved command run on {@code sha}. */
  static Map<String, Object> checkRun(String sha, JsonNode result) {
    String status = result.path("status").asText();
    boolean passed = status.equals("passed") || status.equals("not_applicable");
    String title =
        switch (status) {
          case "passed" -> "Pasa en el sandbox del nodo " + result.path("node").asText();
          case "not_applicable" -> "No aplica, según la propuesta aprobada";
          default -> "Falla en el sandbox del nodo " + result.path("node").asText();
        };
    String summary =
        "Verificación local ordenada por el panel de AgentFarm para este commit. La ejecutó el nodo "
            + result.path("node").asText()
            + ", el mismo equipo que ejecuta a los agentes: **no es una CI independiente**.\n\n"
            + "Comando aprobado: `"
            + result.path("command").asText("").replace("`", "'")
            + "`\n\n```\n"
            + result.path("output").asText("").replace("```", "'''")
            + "\n```";
    var run = new LinkedHashMap<String, Object>();
    run.put("name", CiPolicy.LOCAL + result.path("kind").asText());
    run.put("head_sha", sha);
    run.put("status", "completed");
    run.put("conclusion", passed ? "success" : "failure");
    run.put("output", Map.of("title", title, "summary", Redaction.secrets(summary)));
    return run;
  }

  /** The state of the local verification of each commit of a task, newest first. */
  public List<Map<String, Object>> of(String item) {
    return db.tx(c ->
        new ArrayList<>(
            rows(
                c,
                "SELECT sha,state,reason,at FROM local_verifications WHERE item=? ORDER BY at"
                    + " DESC",
                item)));
  }

  static String tail(String text) {
    return text.length() <= OUTPUT_TAIL ? text : "…" + text.substring(text.length() - OUTPUT_TAIL);
  }

  /** Check-runs go out with the panel's own installation token, which the App lets write checks. */
  static Checks github(GitHubCredentials credentials) {
    var http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
    String base =
        System.getenv("AGENTFARM_GITHUB_API") == null
                || System.getenv("AGENTFARM_GITHUB_API").isBlank()
            ? "https://api.github.com"
            : System.getenv("AGENTFARM_GITHUB_API");
    return (repo, checkRun) -> {
      var response =
          http.send(
              HttpRequest.newBuilder(URI.create(base + "/repos/" + repo + "/check-runs"))
                  .timeout(Duration.ofSeconds(20))
                  .header("Authorization", "Bearer " + credentials.token())
                  .header("Accept", "application/vnd.github+json")
                  .header("Content-Type", "application/json")
                  .POST(HttpRequest.BodyPublishers.ofString(MAPPER.writeValueAsString(checkRun)))
                  .build(),
              HttpResponse.BodyHandlers.ofString());
      if (response.statusCode() != 201)
        throw new IllegalStateException("HTTP " + response.statusCode());
    };
  }
}
