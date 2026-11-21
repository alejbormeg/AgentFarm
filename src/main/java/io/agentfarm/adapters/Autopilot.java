package io.agentfarm.adapters;

import static io.agentfarm.core.Store.*;

import com.fasterxml.jackson.databind.JsonNode;
import io.agentfarm.core.Store;
import io.agentfarm.core.WorkflowService;
import java.sql.Connection;
import java.time.Clock;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import org.springframework.stereotype.Service;

/**
 * "Autonomía hasta la PR": tasks the owner chose to run on their own approve their own plan and
 * correct once if the result does not pass; integrating stays the owner's. Choosing a task (an
 * issue from the list, a new task, or a repository set to run on its own) is the owner's decision.
 * The plan's scope is still the profile's approved paths, tests still run in the container, a
 * paused farm stops it and a daily cap bounds the quota it can spend unattended.
 */
@Service
public final class Autopilot implements WorkflowService.Autopilot {
  public static final int DEFAULT_ROUNDS = 1, DEFAULT_DAILY = 10, MAX_ROUNDS = 3, MAX_DAILY = 50;

  /**
   * The adoption writes the profile and the memory rewrites its lessons: those wait for a person.
   */
  static final Set<String> NEVER = Set.of("adopt-repo", "project-memory");

  private final Store db;
  private final Clock clock;

  public Autopilot(Store db, Clock clock, WorkflowService work) {
    this.db = db;
    this.clock = clock;
    db.tx(c -> {
      statement(
          c,
          "CREATE TABLE IF NOT EXISTS work_autopilot(item TEXT PRIMARY KEY,rounds INTEGER NOT NULL"
              + " DEFAULT 0,created INTEGER NOT NULL,actor TEXT NOT NULL)");
      statement(
          c,
          "CREATE TABLE IF NOT EXISTS repo_autopilot(repo TEXT PRIMARY KEY,enabled INTEGER NOT NULL"
              + " DEFAULT 0,max_rounds INTEGER NOT NULL DEFAULT 1,daily_cap INTEGER NOT NULL DEFAULT"
              + " 10,updated INTEGER NOT NULL,actor TEXT NOT NULL)");
      return null;
    });
    work.autopilot(this);
  }

  private static String repo(String repo) {
    if (repo == null || !repo.matches("[A-Za-z0-9_.-]{1,100}/[A-Za-z0-9_.-]{1,100}"))
      throw new IllegalArgumentException("Repositorio no válido: usa propietario/nombre");
    for (String part : repo.split("/"))
      if (part.equals(".") || part.equals(".."))
        throw new IllegalArgumentException("Repositorio no válido: usa propietario/nombre");
    return repo;
  }

  private static void human(String actor) {
    if (actor.startsWith("node:") || actor.startsWith("requester:") || actor.startsWith("trigger:"))
      throw new IllegalArgumentException("Solo el propietario decide la autonomía");
  }

  // ---- the owner's choices ----

  /** Settings of one repository, with what the last day has already used. */
  public Map<String, Object> settings(String repoName) {
    String repo = repo(repoName);
    return db.tx(c -> settings(c, repo));
  }

  private Map<String, Object> settings(Connection c, String repo) throws Exception {
    var row = one(c, "SELECT * FROM repo_autopilot WHERE repo=?", repo);
    var out = new LinkedHashMap<String, Object>();
    out.put("repo", repo);
    out.put("enabled", row != null && num(row, "enabled") == 1);
    out.put("max_rounds", row == null ? DEFAULT_ROUNDS : num(row, "max_rounds"));
    out.put("daily_cap", row == null ? DEFAULT_DAILY : num(row, "daily_cap"));
    out.put("used_today", usedToday(c));
    return out;
  }

  /** The settings of every repository the panel knows. */
  public java.util.List<Map<String, Object>> all() {
    return db.tx(c -> {
      var out = new java.util.ArrayList<Map<String, Object>>();
      for (var r : rows(c, "SELECT DISTINCT repo FROM work_items ORDER BY repo"))
        out.add(settings(c, r.get("repo").toString()));
      return out;
    });
  }

  public Map<String, Object> configure(
      String repoName, boolean enabled, int maxRounds, int dailyCap, String actor) {
    human(actor);
    String repo = repo(repoName);
    if (maxRounds < 0 || maxRounds > MAX_ROUNDS)
      throw new IllegalArgumentException("Rondas de corrección: de 0 a " + MAX_ROUNDS);
    if (dailyCap < 1 || dailyCap > MAX_DAILY)
      throw new IllegalArgumentException("Tope diario: de 1 a " + MAX_DAILY);
    return db.tx(c -> {
      exec(
          c,
          "INSERT INTO repo_autopilot(repo,enabled,max_rounds,daily_cap,updated,actor) VALUES(?,?,?,?,?,?)"
              + " ON CONFLICT(repo) DO UPDATE SET enabled=excluded.enabled,max_rounds=excluded.max_rounds,"
              + "daily_cap=excluded.daily_cap,updated=excluded.updated,actor=excluded.actor",
          repo,
          enabled ? 1 : 0,
          maxRounds,
          dailyCap,
          clock.instant().getEpochSecond(),
          actor);
      audit(
          c,
          actor,
          "autopilot.configure",
          repo + (enabled ? " on" : " off") + " rounds=" + maxRounds + " cap=" + dailyCap);
      return settings(c, repo);
    });
  }

  /**
   * Marks a task to run on its own. {@code want}: true/false from the owner, null for the
   * repository's default.
   */
  public boolean mark(String item, String repoName, String recipe, Boolean want, String actor) {
    if (actor.startsWith("node:") || actor.startsWith("requester:") || actor.startsWith("trigger:"))
      return false;
    String repo = repo(repoName);
    if (recipe != null && NEVER.contains(recipe)) return false;
    return db.tx(c -> {
      boolean on =
          want != null
              ? want
              : num(
                      one(
                          c,
                          "SELECT COALESCE((SELECT enabled FROM repo_autopilot WHERE repo=?),0) enabled",
                          repo),
                      "enabled")
                  == 1;
      if (!on) return false;
      exec(
          c,
          "INSERT OR IGNORE INTO work_autopilot(item,created,actor) VALUES(?,?,?)",
          item,
          clock.instant().getEpochSecond(),
          actor);
      audit(c, actor, "autopilot.mark", item);
      return true;
    });
  }

  /** Tasks running on their own, with the correction rounds each has used. */
  public Map<String, Integer> marked() {
    return db.tx(c -> {
      var out = new LinkedHashMap<String, Integer>();
      for (var r : rows(c, "SELECT item,rounds FROM work_autopilot"))
        out.put(r.get("item").toString(), (int) num(r, "rounds"));
      return out;
    });
  }

  // ---- the policy the workflow asks ----

  private static long usedToday(Connection c, java.time.Clock clock) throws Exception {
    return num(
        one(
            c,
            "SELECT COUNT(*) n FROM audit WHERE actor='policy:autopilot' AND action='work.implement' AND at>=?",
            clock.instant().minus(Duration.ofHours(24)).toString()),
        "n");
  }

  private long usedToday(Connection c) throws Exception {
    return usedToday(c, clock);
  }

  public boolean approves(
      Object tx, String item, String repoName, String recipe, String proposalJson) {
    try {
      var c = (Connection) tx;
      if (one(c, "SELECT 1 FROM work_autopilot WHERE item=?", item) == null) return false;
      if (recipe != null && NEVER.contains(recipe)) return false;
      if (num(one(c, "SELECT paused FROM governance WHERE id=1"), "paused") == 1) return false;
      var proposal = io.agentfarm.core.Json.parse(proposalJson);
      if (proposal.path("sandbox").path("mode").asText("container").equals("host")) return false;
      if (!proposal.path("criteria").isArray() || proposal.path("criteria").isEmpty()) return false;
      var settings = settings(c, repoName);
      return ((Number) settings.get("used_today")).longValue()
          < ((Number) settings.get("daily_cap")).longValue();
    } catch (Exception e) {
      return false; // when in doubt, wait for the owner
    }
  }

  public String correction(Object tx, String item, String evidenceJson) {
    try {
      var c = (Connection) tx;
      var row = one(c, "SELECT rounds FROM work_autopilot WHERE item=?", item);
      if (row == null) return null;
      var w = one(c, "SELECT repo FROM work_items WHERE id=?", item);
      long max = num(settings(c, w.get("repo").toString()), "max_rounds");
      long used = num(row, "rounds");
      var evidence = io.agentfarm.core.Json.parse(evidenceJson);
      if (used >= max || evidence.path("passed").asBoolean(false)) return null;
      exec(c, "UPDATE work_autopilot SET rounds=rounds+1 WHERE item=?", item);
      return feedback(evidence, used + 1, max);
    } catch (Exception e) {
      return null;
    }
  }

  private static long num(Map<String, Object> row, String key) {
    return ((Number) row.get(key)).longValue();
  }

  private static String clip(String text, int max) {
    String t = text == null ? "" : text.strip();
    return t.length() > max ? t.substring(0, max) + "…" : t;
  }

  /** What failed, in the words the next plan needs. */
  static String feedback(JsonNode evidence, long round, long max) {
    var out =
        new StringBuilder(
            "Corrección automática (ronda "
                + round
                + " de "
                + max
                + "). El resultado anterior no pasó:");
    boolean any = false;
    for (String kind : new String[] {"unit", "integration"}) {
      var r = evidence.path(kind);
      if (!r.isMissingNode() && !r.path("status").asText("passed").equals("passed")) {
        out.append("\n- Las pruebas ")
            .append(kind.equals("unit") ? "unitarias" : "de integración")
            .append(" fallan: ")
            .append(clip(r.path("output").asText(r.path("status").asText()), 700));
        any = true;
      }
    }
    var review = evidence.path("review");
    if (review.has("passed") && !review.path("passed").asBoolean(true)) {
      out.append("\n- Benjamin no lo aprobó: ").append(clip(review.path("findings").asText(), 900));
      any = true;
    }
    if (!any)
      out.append(
          "\n- Las comprobaciones del candidato no pasaron (pruebas, revisión o mandamientos): revisa el dossier.");
    out.append("\nPropón un plan que lo arregle, conservando el alcance aprobado.");
    return out.toString();
  }
}
