package io.agentfarm.core;

import static io.agentfarm.core.Json.*;
import static io.agentfarm.core.Store.*;

import com.fasterxml.jackson.databind.JsonNode;
import java.time.*;
import java.time.temporal.IsoFields;
import java.util.*;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

/**
 * Turns repository signals into research proposals, never into implementation or merge. Each
 * signal is processed once (trigger_events), each repository opens at most {@code max_new}
 * proposals per pass, and a paused farm creates nothing.
 */
@Service
public class TriggerService {
  public static final Set<String> KINDS =
      Set.of("labeled_issue", "failing_ci", "dependabot", "weekly", "pr_feedback");

  private final Store db;
  private final Clock clock;
  private final WorkflowService work;
  private final GitHubReader github;

  public TriggerService(Store db, Clock clock, WorkflowService work, GitHubReader github) {
    this.db = db;
    this.clock = clock;
    this.work = work;
    this.github = github;
    db.tx(
        c -> {
          statement(
              c,
              "CREATE TABLE IF NOT EXISTS trigger_policies(repo TEXT PRIMARY KEY,kinds TEXT NOT"
                  + " NULL,max_new INTEGER NOT NULL,enabled INTEGER NOT NULL,last_run"
                  + " INTEGER,last_error TEXT)");
          statement(
              c,
              "CREATE TABLE IF NOT EXISTS trigger_events(key TEXT PRIMARY KEY,repo TEXT NOT"
                  + " NULL,kind TEXT NOT NULL,item TEXT NOT NULL,at INTEGER NOT NULL)");
          return null;
        });
  }

  public List<Map<String, Object>> policies() {
    return db.tx(
        c -> {
          var rows = rows(c, "SELECT * FROM trigger_policies ORDER BY repo");
          for (var r : rows) r.put("kinds", parse(r.get("kinds").toString()));
          return rows;
        });
  }

  public Map<String, Object> configure(JsonNode n, String actor) {
    String repo = text(n, "repository");
    if (!repo.matches("[A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+"))
      throw new IllegalArgumentException("Invalid repository");
    var kinds = new TreeSet<String>();
    for (var k : n.path("kinds")) {
      if (!KINDS.contains(k.asText())) throw new IllegalArgumentException("Unknown trigger");
      kinds.add(k.asText());
    }
    long max = n.path("max_new").asLong(2);
    if (max < 1 || max > 10) throw new IllegalArgumentException("max_new must be 1-10");
    boolean enabled = n.path("enabled").asBoolean(true);
    return db.tx(
        c -> {
          exec(
              c,
              "INSERT INTO trigger_policies(repo,kinds,max_new,enabled) VALUES(?,?,?,?) ON"
                  + " CONFLICT(repo) DO UPDATE SET"
                  + " kinds=excluded.kinds,max_new=excluded.max_new,enabled=excluded.enabled",
              repo,
              encode(kinds),
              max,
              enabled ? 1 : 0);
          audit(c, actor, "trigger.configure", repo + " " + kinds + (enabled ? "" : " disabled"));
          return obj("repository", repo, "kinds", kinds, "max_new", max, "enabled", enabled);
        });
  }

  @Scheduled(fixedDelayString = "${agentfarm.trigger-interval-ms:300000}", initialDelay = 60000)
  public void scheduled() {
    if (github.configured()) tick(github::get);
  }

  /** One pass over every enabled policy. Returns the request ids created or corrected. */
  public List<String> tick(GitHubReader.Source source) {
    var touched = new ArrayList<String>();
    if (db.tx(c -> num(one(c, "SELECT paused FROM governance WHERE id=1"), "paused") == 1))
      return touched;
    for (var policy : policies()) {
      if (num(policy, "enabled") != 1) continue;
      String repo = policy.get("repo").toString();
      String error = null;
      try {
        touched.addAll(run(source, repo, (JsonNode) policy.get("kinds"), (int) num(policy, "max_new")));
      } catch (Exception e) {
        error = TelemetryService.clean(String.valueOf(e.getMessage()));
      }
      String failure = error;
      db.tx(
          c ->
              exec(
                  c,
                  "UPDATE trigger_policies SET last_run=?,last_error=? WHERE repo=?",
                  clock.instant().getEpochSecond(),
                  failure,
                  repo));
    }
    return touched;
  }

  private record Signal(String key, String kind, String title, String request, String recipe) {}

  private List<String> run(GitHubReader.Source gh, String repo, JsonNode kinds, int max)
      throws Exception {
    var wanted = new HashSet<String>();
    kinds.forEach(k -> wanted.add(k.asText()));
    var signals = new ArrayList<Signal>();
    if (wanted.contains("labeled_issue"))
      for (var issue : gh.get(repo, "/issues?labels=agentfarm&state=open&per_page=20"))
        if (!issue.has("pull_request"))
          signals.add(
              new Signal(
                  "issue:" + repo + "#" + issue.path("number").asInt(),
                  "labeled_issue",
                  "#" + issue.path("number").asInt() + " " + issue.path("title").asText(),
                  "Origen: issue " + issue.path("html_url").asText() + "\n\n" + issue.path("body").asText(""),
                  null));
    if (wanted.contains("failing_ci")) {
      String branch = gh.get(repo, "/").path("default_branch").asText("main");
      for (var run : gh.get(repo, "/actions/runs?branch=" + branch + "&status=completed&per_page=1").path("workflow_runs"))
        if (run.path("conclusion").asText().equals("failure"))
          signals.add(
              new Signal(
                  "ci:" + repo + ":" + run.path("id").asLong(),
                  "failing_ci",
                  "CI roto en " + branch + ": " + run.path("name").asText(),
                  "La última ejecución de CI en "
                      + branch
                      + " falla ("
                      + run.path("html_url").asText()
                      + ", commit "
                      + run.path("head_sha").asText()
                      + "). Reproduce el fallo, identifica la causa y propone la corrección mínima.",
                  "bugfix"));
    }
    if (wanted.contains("dependabot"))
      for (var alert : gh.get(repo, "/dependabot/alerts?state=open&per_page=20")) {
        var advisory = alert.path("security_advisory");
        signals.add(
            new Signal(
                "dep:" + repo + ":" + alert.path("number").asInt(),
                "dependabot",
                "Seguridad: " + alert.path("dependency").path("package").path("name").asText()
                    + " (" + advisory.path("severity").asText() + ")",
                "Alerta Dependabot " + alert.path("html_url").asText() + ": "
                    + advisory.path("summary").asText()
                    + ". Propón la actualización mínima que la corrige y su impacto.",
                "dependency-update"));
      }
    if (wanted.contains("weekly")) {
      var date = LocalDate.ofInstant(clock.instant(), ZoneOffset.UTC);
      String week = date.get(IsoFields.WEEK_BASED_YEAR) + "-W" + date.get(IsoFields.WEEK_OF_WEEK_BASED_YEAR);
      signals.add(
          new Signal(
              "weekly:" + repo + ":" + week,
              "weekly",
              "Revisión semanal " + week,
              "Analiza el estado del proyecto (issues abiertas, deuda, pruebas, dependencias) y"
                  + " propone hasta tres mejoras priorizadas y viables, con fuentes. No"
                  + " implementes nada.",
              null));
    }
    var touched = new ArrayList<String>();
    for (var s : signals) {
      if (touched.size() >= max) break;
      String id = "trg-" + sha256(s.key()).substring(0, 32);
      boolean created =
          db.tx(
              c -> {
                if (one(c, "SELECT 1 FROM trigger_events WHERE key=?", s.key()) != null) return false;
                var request =
                    obj(
                        "request_id",
                        id,
                        "repository",
                        repo,
                        "title",
                        s.title().length() > 180 ? s.title().substring(0, 180) : s.title(),
                        "request",
                        s.request().length() > 18000 ? s.request().substring(0, 18000) : s.request());
                if (s.recipe() != null) request.put("recipe", obj("id", s.recipe(), "version", 1));
                work.create(c, MAPPER.valueToTree(request), "trigger:" + s.kind());
                exec(
                    c,
                    "INSERT INTO trigger_events VALUES(?,?,?,?,?)",
                    s.key(),
                    repo,
                    s.kind(),
                    id,
                    clock.instant().getEpochSecond());
                return true;
              });
      if (created) touched.add(id);
    }
    if (wanted.contains("pr_feedback")) touched.addAll(feedback(gh, repo));
    return touched;
  }

  /** Requested changes and failed checks on an open AgentFarm PR become a correction request. */
  private List<String> feedback(GitHubReader.Source gh, String repo) throws Exception {
    var touched = new ArrayList<String>();
    var reviewing =
        db.tx(
            c ->
                rows(
                    c,
                    "SELECT id,pr,sha FROM work_items WHERE repo=? AND state='review' AND pr IS NOT"
                        + " NULL",
                    repo));
    for (var item : reviewing) {
      String id = item.get("id").toString(), sha = Objects.toString(item.get("sha"), "");
      var notes = new ArrayList<String>();
      var keys = new ArrayList<String>();
      for (var review : gh.get(repo, "/pulls/" + item.get("pr") + "/reviews?per_page=100"))
        if (review.path("state").asText().equals("CHANGES_REQUESTED")
            && review.path("commit_id").asText().equals(sha)) {
          keys.add("review:" + repo + ":" + review.path("id").asLong());
          notes.add(review.path("user").path("login").asText() + ": " + review.path("body").asText());
        }
      for (var check : gh.get(repo, "/commits/" + sha + "/check-runs?per_page=100").path("check_runs"))
        if (Set.of("failure", "timed_out").contains(check.path("conclusion").asText())) {
          keys.add("check:" + repo + ":" + check.path("id").asLong());
          notes.add("Check " + check.path("name").asText() + " falla: " + check.path("html_url").asText());
        }
      if (keys.isEmpty()) continue;
      boolean fresh =
          db.tx(
              c -> {
                for (String k : keys)
                  if (one(c, "SELECT 1 FROM trigger_events WHERE key=?", k) == null) return true;
                return false;
              });
      if (!fresh) continue;
      try {
        work.action(
            id,
            "correct",
            MAPPER.valueToTree(obj("feedback", "Comentarios de la PR y CI:\n" + String.join("\n", notes))),
            "trigger:pr_feedback");
      } catch (IllegalArgumentException busy) {
        continue; // an operation is already pending; retry next pass
      }
      db.tx(
          c -> {
            for (String k : keys)
              exec(
                  c,
                  "INSERT OR IGNORE INTO trigger_events VALUES(?,?,?,?,?)",
                  k,
                  repo,
                  "pr_feedback",
                  id,
                  clock.instant().getEpochSecond());
            return null;
          });
      touched.add(id);
    }
    return touched;
  }
}
