package io.agentfarm.adapters;

import static io.agentfarm.core.Json.sha256;
import static io.agentfarm.core.Store.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.agentfarm.core.GitHubReader;
import io.agentfarm.core.Store;
import io.agentfarm.core.WorkflowService;
import java.time.Clock;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

/**
 * The open issues of a repository the panel already knows, for the owner to choose which ones the
 * agents start on. Starting one opens a research task exactly as the {@code labeled_issue} trigger
 * would (same request text, same dedupe key), so the two never duplicate each other. Each issue is
 * read again from GitHub when started: nothing the browser sends is used as the request.
 */
@Service
public class RepoIssues {
  public static final int MAX_START = 10, PAGE = 50, PAGES = 2;

  /**
   * Issues AgentFarm itself opens to track its tasks carry this mark: they are not work to start.
   */
  static final String OWN_MARK = "<!-- agentfarm:";

  private static final ObjectMapper MAPPER = new ObjectMapper();
  private final Store db;
  private final WorkflowService work;
  private final Clock clock;
  private final GitHubReader.Source github;

  @Autowired
  public RepoIssues(Store db, WorkflowService work, Clock clock, GitHubReader reader) {
    this(db, work, clock, reader::get);
  }

  public RepoIssues(Store db, WorkflowService work, Clock clock, GitHubReader.Source github) {
    this.db = db;
    this.work = work;
    this.clock = clock;
    this.github = github;
    // The same table the trigger writes: a started issue and a labelled one are one and the same.
    db.tx(c -> {
      statement(
          c,
          "CREATE TABLE IF NOT EXISTS trigger_events(key TEXT PRIMARY KEY,repo TEXT NOT"
              + " NULL,kind TEXT NOT NULL,item TEXT NOT NULL,at INTEGER NOT NULL)");
      return null;
    });
  }

  static String key(String repo, int number) {
    return "issue:" + repo + "#" + number;
  }

  static String taskId(String repo, int number) {
    return "trg-" + sha256(key(repo, number)).substring(0, 32);
  }

  private static String repo(String repo) {
    if (repo == null || !repo.matches("[A-Za-z0-9_.-]{1,100}/[A-Za-z0-9_.-]{1,100}"))
      throw new IllegalArgumentException("Repositorio no válido: usa propietario/nombre");
    for (String part : repo.split("/"))
      if (part.equals(".") || part.equals(".."))
        throw new IllegalArgumentException("Repositorio no válido: usa propietario/nombre");
    return repo;
  }

  private void known(String repo) {
    boolean ok = db.tx(c -> one(c, "SELECT 1 FROM work_items WHERE repo=? LIMIT 1", repo) != null);
    if (!ok)
      throw new IllegalArgumentException("El panel no conoce ese repositorio: adóptalo primero");
  }

  static String excerpt(String body) {
    String t =
        body == null
            ? ""
            : body.replaceAll("[\\p{Cntrl}&&[^\\n\\t]]", "").replaceAll("\\s+", " ").strip();
    return t.length() > 300 ? t.substring(0, 300).strip() + "…" : t;
  }

  /**
   * Open issues (no pull requests, none of AgentFarm's own), each with the task it already has, if
   * any.
   */
  public List<Map<String, Object>> list(String repoName) throws Exception {
    String repo = repo(repoName);
    known(repo);
    var tasks =
        db.tx(c -> {
          var out = new LinkedHashMap<String, Map<String, Object>>();
          for (var r :
              rows(
                  c,
                  "SELECT e.key,e.item,w.state FROM trigger_events e JOIN work_items w ON w.id=e.item"
                      + " WHERE e.repo=? AND e.key LIKE 'issue:%'",
                  repo))
            out.put(r.get("key").toString(), Map.of("id", r.get("item"), "state", r.get("state")));
          return out;
        });
    var out = new ArrayList<Map<String, Object>>();
    for (int page = 1; page <= PAGES; page++) {
      var batch = github.get(repo, "/issues?state=open&per_page=" + PAGE + "&page=" + page);
      for (var issue : batch) {
        if (issue.has("pull_request") || issue.path("body").asText("").contains(OWN_MARK)) continue;
        int number = issue.path("number").asInt();
        var labels = new ArrayList<String>();
        for (var l : issue.path("labels"))
          labels.add(SkillMeta.clean(l.path("name").asText(""), 40));
        var row = new LinkedHashMap<String, Object>();
        row.put("number", number);
        row.put("title", SkillMeta.clean(issue.path("title").asText(""), 180));
        row.put("labels", labels);
        row.put(
            "author", SkillMeta.clean(issue.path("user").path("login").asText("desconocido"), 60));
        row.put("trusted", GitHubAuthors.trusted(issue));
        row.put("comments", issue.path("comments").asInt(0));
        row.put("created", issue.path("created_at").asText(""));
        row.put("url", issue.path("html_url").asText(""));
        row.put("excerpt", excerpt(issue.path("body").asText("")));
        row.put("task", tasks.get(key(repo, number)));
        out.add(row);
      }
      if (batch.size() < PAGE) break;
    }
    return out;
  }

  /** Opens a research task for each chosen issue; one entry per issue says what happened. */
  public List<Map<String, Object>> start(String repoName, List<Integer> numbers, String actor)
      throws Exception {
    if (actor.startsWith("node:"))
      throw new IllegalArgumentException("Solo el propietario empieza issues");
    String repo = repo(repoName);
    known(repo);
    var chosen = new LinkedHashSet<>(numbers == null ? List.<Integer>of() : numbers);
    if (chosen.isEmpty()) throw new IllegalArgumentException("Elige al menos una issue");
    if (chosen.size() > MAX_START)
      throw new IllegalArgumentException("Máximo " + MAX_START + " issues cada vez");
    var results = new ArrayList<Map<String, Object>>();
    for (int number : chosen) {
      var result = new LinkedHashMap<String, Object>();
      result.put("number", number);
      try {
        if (number < 1) throw new IllegalArgumentException("Número de issue no válido");
        var issue = github.get(repo, "/issues/" + number);
        if (issue.has("pull_request"))
          throw new IllegalArgumentException("Es una PR, no una issue");
        if (!issue.path("state").asText().equals("open"))
          throw new IllegalArgumentException("La issue ya no está abierta");
        if (issue.path("body").asText("").contains(OWN_MARK))
          throw new IllegalArgumentException("La abrió AgentFarm para seguir una tarea");
        String key = key(repo, number), id = taskId(repo, number);
        boolean fresh =
            db.tx(c -> {
              if (one(c, "SELECT 1 FROM trigger_events WHERE key=?", key) != null) return false;
              exec(
                  c,
                  "INSERT INTO trigger_events VALUES(?,?,?,?,?)",
                  key,
                  repo,
                  "labeled_issue",
                  id,
                  clock.instant().getEpochSecond());
              return true;
            });
        if (!fresh) {
          result.put("state", "already");
          result.put("task", id);
        } else {
          try {
            String title = "#" + number + " " + issue.path("title").asText();
            String request =
                "Origen: issue "
                    + issue.path("html_url").asText()
                    + " · "
                    + GitHubAuthors.describe(issue)
                    + "\n\n"
                    + issue.path("body").asText("");
            var body = MAPPER.createObjectNode();
            body.put("request_id", id);
            body.put("repository", repo);
            body.put("title", title.length() > 180 ? title.substring(0, 180) : title);
            body.put("request", request.length() > 18000 ? request.substring(0, 18000) : request);
            work.create(body, actor);
            result.put("state", "created");
            result.put("task", id);
          } catch (RuntimeException e) {
            // Without its task the key must not stay: the issue could never be started again.
            db.tx(c -> exec(c, "DELETE FROM trigger_events WHERE key=?", key));
            throw e;
          }
        }
      } catch (IllegalArgumentException | IllegalStateException e) {
        result.put("state", "error");
        result.put("error", SkillMeta.clean(e.getMessage(), 200));
      }
      results.add(result);
    }
    return results;
  }
}
