package io.agentfarm.core;

import static io.agentfarm.core.Json.*;
import static io.agentfarm.core.Store.*;

import com.fasterxml.jackson.databind.JsonNode;
import java.time.Clock;
import java.util.*;
import org.springframework.stereotype.Service;

/** Durable owner-approved workflow. Remote side effects are performed by the WSL agent. */
@Service
public class WorkflowService {
  private final Store db;
  private final Clock clock;

  private final int maxParallel;
  private final int maxPerRepository;

  public WorkflowService(Store db, Clock clock) {
    this(db, clock, 1, 1);
  }

  @org.springframework.beans.factory.annotation.Autowired
  public WorkflowService(
      Store db,
      Clock clock,
      @org.springframework.beans.factory.annotation.Value("${agentfarm.max-parallel:2}")
          int maxParallel,
      @org.springframework.beans.factory.annotation.Value("${agentfarm.max-per-repository:1}")
          int maxPerRepository) {
    if (maxParallel < 1
        || maxParallel > 16
        || maxPerRepository < 1
        || maxPerRepository > maxParallel)
      throw new IllegalArgumentException("Invalid parallelism limits");
    this.maxParallel = maxParallel;
    this.maxPerRepository = maxPerRepository;
    this.db = db;
    this.clock = clock;
    db.tx(c -> {
      statement(
          c,
          "CREATE TABLE IF NOT EXISTS work_items(id TEXT PRIMARY KEY,repo TEXT NOT NULL,title"
              + " TEXT NOT NULL,request TEXT NOT NULL,state TEXT NOT NULL,version INTEGER NOT"
              + " NULL DEFAULT 0,proposal TEXT NOT NULL DEFAULT '{}',evidence TEXT NOT NULL"
              + " DEFAULT '{}',issue INTEGER,pr INTEGER,branch TEXT,sha TEXT,error TEXT,created"
              + " INTEGER NOT NULL)");
      statement(
          c,
          "CREATE TABLE IF NOT EXISTS work_commands(id TEXT PRIMARY KEY,item TEXT NOT NULL"
              + " REFERENCES work_items(id),kind TEXT NOT NULL,payload TEXT NOT NULL,state TEXT"
              + " NOT NULL DEFAULT 'queued',claimed_by TEXT,updated INTEGER NOT NULL)");
      statement(
          c,
          "CREATE UNIQUE INDEX IF NOT EXISTS one_open_command ON work_commands(item) WHERE"
              + " state IN ('queued','claimed')");
      statement(
          c,
          "CREATE TABLE IF NOT EXISTS work_recipes(item TEXT PRIMARY KEY REFERENCES"
              + " work_items(id),snapshot TEXT NOT NULL)");
      statement(
          c,
          "CREATE TABLE IF NOT EXISTS notifications(id INTEGER PRIMARY KEY,item TEXT NOT"
              + " NULL,state TEXT NOT NULL,title TEXT NOT NULL,created INTEGER NOT NULL,sent"
              + " INTEGER NOT NULL DEFAULT 0,attempts INTEGER NOT NULL DEFAULT 0)");
      ensureColumn(c, "work_commands", "claimed_at", "INTEGER");
      // A revert and the integrated task it undoes name each other.
      ensureColumn(c, "work_items", "reverts", "TEXT");
      ensureColumn(c, "work_items", "reverted_by", "TEXT");
      statement(
          c,
          "CREATE TABLE IF NOT EXISTS autonomy_policies(recipe TEXT PRIMARY KEY,level TEXT NOT"
              + " NULL,updated INTEGER NOT NULL)");
      EvidenceService.schema(c);
      InitiativeService.schema(c);
      return null;
    });
  }

  public void requireAcceptance(String id) {
    db.tx(c -> {
      EvidenceService.requireReady(c, EvidenceService.item(c, id));
      return null;
    });
  }

  public List<Map<String, Object>> items() {
    return db.tx(c -> {
      var items = rows(c, "SELECT * FROM work_items ORDER BY created DESC");
      for (var item : items) withRecipe(c, redacted(item));
      return items;
    });
  }

  /** Heavy columns kept out of listings: the full text is one task away, at {@link #get}. */
  static final Set<String> DETAIL_ONLY = Set.of("proposal", "evidence", "request");

  /**
   * The task list as summaries, newest first: what a board, an inbox or a search needs, without the
   * proposal, the evidence or the request text. {@code limit} 0 means all.
   */
  public List<Map<String, Object>> summaries(int limit, int offset) {
    if (limit < 0 || offset < 0 || limit > 1000)
      throw new IllegalArgumentException("limit must be 0-1000 and offset nonnegative");
    return db.tx(c -> {
      var columns = new ArrayList<String>();
      for (var col : rows(c, "PRAGMA table_info(work_items)"))
        if (!DETAIL_ONLY.contains(col.get("name").toString()))
          columns.add(col.get("name").toString());
      var items =
          rows(
              c,
              "SELECT "
                  + String.join(",", columns)
                  + " FROM work_items ORDER BY created DESC,id LIMIT ? OFFSET ?",
              limit == 0 ? -1 : limit,
              offset);
      for (var item : items) withRecipe(c, redactedError(item));
      return items;
    });
  }

  private static Map<String, Object> redactedError(Map<String, Object> item) {
    if (item.get("error") instanceof String text) item.put("error", TelemetryService.clean(text));
    return item;
  }

  public Map<String, Object> get(String id) {
    return db.tx(c -> {
      var w = one(c, "SELECT * FROM work_items WHERE id=?", id);
      if (w == null) throw new IllegalArgumentException("Unknown work item");
      return withRecipe(c, redacted(w));
    });
  }

  /** What agents wrote is redacted on the way in; rows from before that, on the way out too. */
  static Map<String, Object> redacted(Map<String, Object> item) {
    for (String field : List.of("proposal", "evidence", "error"))
      if (item.get(field) instanceof String text) item.put(field, Redaction.secrets(text));
    return item;
  }

  private static Map<String, Object> withRecipe(java.sql.Connection c, Map<String, Object> item)
      throws Exception {
    var saved = one(c, "SELECT snapshot FROM work_recipes WHERE item=?", item.get("id"));
    if (saved != null) item.put("recipe", parse(saved.get("snapshot").toString()));
    return item;
  }

  private void command(java.sql.Connection c, String id, String kind, Object payload)
      throws Exception {
    exec(
        c,
        "INSERT INTO work_commands(id,item,kind,payload,updated) VALUES(?,?,?,?,?)",
        UUID.randomUUID().toString(),
        id,
        kind,
        encode(payload),
        clock.instant().getEpochSecond());
  }

  public Map<String, Object> create(JsonNode n, String actor) {
    return db.tx(c -> create(c, n, actor));
  }

  /** Same as {@link #create(JsonNode, String)} inside an existing transaction. */
  Map<String, Object> create(java.sql.Connection c, JsonNode n, String actor) throws Exception {
    String id = text(n, "request_id"),
        repo = text(n, "repository"),
        title = text(n, "title"),
        request = text(n, "request");
    if (!id.matches("[a-zA-Z0-9-]{8,80}")
        || !repo.matches("[A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+")
        || title.length() > 180
        || request.length() > 20000) throw new IllegalArgumentException("Invalid request");
    var selected = n.get("recipe");
    if (selected != null
        && (!selected.isObject()
            || !selected.path("id").isTextual()
            || !selected.path("version").isIntegralNumber()))
      throw new IllegalArgumentException("Recipe id and integer version required");
    var old = one(c, "SELECT * FROM work_items WHERE id=?", id);
    if (old != null) {
      if (!old.get("repo").equals(repo)
          || !old.get("title").equals(title)
          || !old.get("request").equals(request))
        throw new IllegalArgumentException("Request identifier already used");
      var prior = withRecipe(c, old);
      var saved = MAPPER.valueToTree(prior).path("recipe");
      if ((selected == null) != saved.isMissingNode()
          || (selected != null
              && (!text(selected, "id").equals(saved.path("id").asText())
                  || number(selected, "version") != saved.path("version").asLong())))
        throw new IllegalArgumentException("Request identifier already used with another recipe");
      return prior;
    }
    var recipe =
        selected == null
            ? null
            : RecipeCatalog.snapshot(text(selected, "id"), number(selected, "version"));
    exec(
        c,
        "INSERT INTO work_items(id,repo,title,request,state,created) VALUES(?,?,?,?,?,?)",
        id,
        repo,
        title,
        request,
        "queued",
        clock.instant().getEpochSecond());
    if (recipe != null) {
      exec(c, "INSERT INTO work_recipes VALUES(?,?)", id, encode(recipe));
      audit(
          c,
          actor,
          "work.recipe",
          id
              + " "
              + recipe.path("id").asText()
              + "@"
              + recipe.path("version").asLong()
              + " "
              + recipe.path("hash").asText());
    }
    command(c, id, "research", Map.of());
    audit(c, actor, "work.request", id);
    return withRecipe(c, one(c, "SELECT * FROM work_items WHERE id=?", id));
  }

  public Map<String, Object> importCandidate(JsonNode n, String actor) {
    String id = text(n, "request_id"),
        repo = text(n, "repository"),
        sha = text(n, "sha"),
        branch = text(n, "branch");
    if (!id.matches("[a-zA-Z0-9-]{8,80}")
        || !repo.matches("[A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+")
        || !sha.matches("[a-f0-9]{40}")
        || !branch.matches("agentfarm/[a-zA-Z0-9/_-]+"))
      throw new IllegalArgumentException("Invalid candidate");
    var proposal = n.path("proposal");
    if (proposal.has("project_memory"))
      ProjectMemory.validate(proposal.get("project_memory"), repo);
    for (String key :
        List.of(
            "summary", "acceptance", "as_is", "to_be", "unit_test", "integration_test", "base_sha"))
      text(proposal, key);
    return db.tx(c -> {
      var prior = one(c, "SELECT * FROM work_items WHERE id=?", id);
      if (prior != null) {
        if (Objects.equals(prior.get("sha"), sha) && !prior.get("state").equals("blocked"))
          return prior;
        if (!prior.get("state").equals("blocked")
            || !prior.get("repo").equals(repo)
            || num(prior, "issue") != number(n, "issue"))
          throw new IllegalArgumentException("Existing task does not match recoverable candidate");
        exec(
            c,
            "UPDATE work_items SET sha=?,branch=?,proposal=?,state='approved',error=NULL WHERE"
                + " id=?",
            sha,
            branch,
            Redaction.secrets(encode(proposal)),
            id);
        command(c, id, "review_existing", Map.of());
        audit(c, actor, "work.recover-candidate", id + " @ " + sha);
        return one(c, "SELECT * FROM work_items WHERE id=?", id);
      }
      exec(
          c,
          "INSERT INTO"
              + " work_items(id,repo,title,request,state,version,proposal,issue,branch,sha,created)"
              + " VALUES(?,?,?,?,?,?,?,?,?,?,?)",
          id,
          repo,
          text(n, "title"),
          text(n, "request"),
          "approved",
          1,
          Redaction.secrets(encode(proposal)),
          number(n, "issue"),
          branch,
          sha,
          clock.instant().getEpochSecond());
      command(c, id, "review_existing", Map.of());
      audit(c, actor, "work.import-candidate", id + " @ " + sha);
      return one(c, "SELECT * FROM work_items WHERE id=?", id);
    });
  }

  public Map<String, Object> action(String id, String action, JsonNode n, String actor) {
    return db.tx(c -> {
      var w = one(c, "SELECT * FROM work_items WHERE id=?", id);
      if (w == null) throw new IllegalArgumentException("Unknown work item");
      String state = w.get("state").toString();
      var rule = WorkStates.require(action, state);
      if (action.equals("revert")) {
        // Undoing integrated work is its own task, through every gate; both sides name each
        // other, and asking again returns the same one.
        String revertId = "revert-" + id;
        if (revertId.length() > 80) revertId = "revert-" + sha256(id).substring(0, 40);
        String where =
            (w.get("pr") == null ? "" : "la PR #" + w.get("pr") + ", ")
                + (w.get("sha") == null ? "" : "commit " + w.get("sha") + ", ");
        var request =
            Json.obj(
                "request_id",
                revertId,
                "repository",
                w.get("repo"),
                "title",
                truncate("Revertir: " + w.get("title"), 180),
                "request",
                "Revierte el cambio integrado por la tarea "
                    + id
                    + " ("
                    + where
                    + "«"
                    + w.get("title")
                    + "»). Propón cómo deshacerlo sin romper lo que vino después y con sus pruebas.");
        var created = create(c, parse(encode(request)), actor);
        exec(c, "UPDATE work_items SET reverts=? WHERE id=?", id, revertId);
        exec(c, "UPDATE work_items SET reverted_by=? WHERE id=?", revertId, id);
        audit(c, actor, "work.revert", id + " -> " + revertId);
        return created;
      }
      if (action.equals("cancel")) {
        Long seen = null;
        try {
          seen = number(n, "version");
        } catch (IllegalArgumentException absent) {
          // Older clients send no version: the state rules above still hold.
        }
        if (seen != null && seen != num(w, "version"))
          throw new IllegalArgumentException(
              "La tarea ha cambiado desde que la abriste: recarga antes de cancelarla.");
        exec(
            c,
            "UPDATE work_commands SET state='cancelled',updated=? WHERE item=? AND state IN"
                + " ('queued','claimed')",
            clock.instant().getEpochSecond(),
            id);
        exec(c, "UPDATE work_items SET state='cancelled' WHERE id=? AND state=?", id, state);
        audit(c, actor, "work.cancel", id + " desde " + state);
      } else {
        if (one(
                c,
                "SELECT id FROM work_commands WHERE item=? AND state IN ('queued','claimed')",
                id)
            != null) throw new IllegalArgumentException("Operation already pending");
        switch (action) {
          case "implement" -> {
            if (rule.version() && number(n, "version") != num(w, "version"))
              throw new IllegalArgumentException("Approve the current proposal version");
            EvidenceService.capture(c, w);
            command(c, id, "implement", obj("version", num(w, "version")));
            exec(c, "UPDATE work_items SET state='approved',error=NULL WHERE id=?", id);
          }
          case "merge" -> {
            if (w.get("pr") == null || !text(n, "sha").equals(w.get("sha")))
              throw new IllegalArgumentException("Approve the current PR commit");
            EvidenceService.requireReady(c, w);
            command(c, id, "merge", obj("sha", text(n, "sha")));
            exec(c, "UPDATE work_items SET state='integrating',error=NULL WHERE id=?", id);
          }
          case "correct" -> {
            String feedback = text(n, "feedback");
            if (n.path("retire").isArray() && !n.path("retire").isEmpty()) {
              ApprovedCriteria.retire(
                  c, w, n.path("retire"), feedback, actor, clock.instant().getEpochSecond());
              audit(c, actor, "work.retire-criterion", id + " " + n.path("retire"));
            }
            command(c, id, "research", obj("feedback", feedback));
            exec(c, "UPDATE work_items SET state='queued',error=NULL WHERE id=?", id);
            proposeLesson(c, w, feedback, actor);
          }
          case "retry" -> {
            var previous =
                one(
                    c,
                    "SELECT kind,payload FROM work_commands WHERE item=? ORDER BY updated"
                        + " DESC,rowid DESC LIMIT 1",
                    id);
            if (previous == null || previous.get("kind").equals("merge"))
              throw new IllegalArgumentException("Integration needs a new explicit approval");
            command(
                c, id, previous.get("kind").toString(), parse(previous.get("payload").toString()));
            exec(c, "UPDATE work_items SET state='queued',error=NULL WHERE id=?", id);
          }
          default -> throw new IllegalArgumentException("Unknown action");
        }
      }
      audit(c, actor, "work." + action, id);
      return one(c, "SELECT * FROM work_items WHERE id=?", id);
    });
  }

  /**
   * The repository ({@code repo}) and kind ({@code kind}) of an order this agent holds right now.
   * Integrating is the panel's, never a node's.
   */
  public Map<String, Object> heldRepository(String command, String agent) {
    return db.tx(c -> {
      var held =
          one(
              c,
              "SELECT w.repo repo,k.kind kind FROM work_commands k JOIN work_items w ON"
                  + " w.id=k.item WHERE k.id=? AND k.state='claimed' AND k.claimed_by=? AND"
                  + " k.kind<>'merge'",
              command,
              agent);
      if (held == null) throw new IllegalArgumentException("Not an order this executor holds");
      return held;
    });
  }

  // Recipes whose proposals may start implementation without the owner; merge stays human.
  static final Set<String> AUTO_IMPLEMENTABLE =
      Set.of("bugfix", "dependency-update", "test-improvement");

  public List<Map<String, Object>> autonomy() {
    return db.tx(c -> rows(c, "SELECT * FROM autonomy_policies ORDER BY recipe"));
  }

  /**
   * manual (default), auto_implement (low-risk recipes only) or, for project-memory,
   * propose_on_feedback: owner corrections open a memory proposal.
   */
  public Map<String, Object> setAutonomy(String recipe, String level, String actor) {
    boolean valid =
        level.equals("manual")
            || (level.equals("auto_implement") && AUTO_IMPLEMENTABLE.contains(recipe))
            || (level.equals("propose_on_feedback") && recipe.equals("project-memory"));
    if (!valid) throw new IllegalArgumentException("Level not allowed for this recipe");
    return db.tx(c -> {
      exec(
          c,
          "INSERT INTO autonomy_policies VALUES(?,?,?) ON CONFLICT(recipe) DO UPDATE SET"
              + " level=excluded.level,updated=excluded.updated",
          recipe,
          level,
          clock.instant().getEpochSecond());
      audit(c, actor, "autonomy." + level, recipe);
      return obj("recipe", recipe, "level", level);
    });
  }

  private static String level(java.sql.Connection c, String recipe) throws Exception {
    var row = one(c, "SELECT level FROM autonomy_policies WHERE recipe=?", recipe);
    return row == null ? "manual" : row.get("level").toString();
  }

  /**
   * Approves the current proposal when its recipe is auto_implement and every criterion is verified
   * by tests in the container sandbox. Anything manual or host-run waits for the owner.
   */
  private void autoApprove(java.sql.Connection c, String id) throws Exception {
    var w = withRecipe(c, one(c, "SELECT * FROM work_items WHERE id=?", id));
    var recipe = MAPPER.valueToTree(w).path("recipe");
    if (recipe.isMissingNode() || !level(c, recipe.path("id").asText()).equals("auto_implement"))
      return;
    var p = parse(w.get("proposal").toString());
    if (p.path("sandbox").path("mode").asText("container").equals("host")) return;
    if (!p.path("criteria").isArray() || p.path("criteria").isEmpty()) return;
    for (var criterion : p.path("criteria"))
      if (!criterion.path("method").asText().equals("test")) return;
    if (num(one(c, "SELECT paused FROM governance WHERE id=1"), "paused") == 1) return;
    EvidenceService.capture(c, w);
    command(c, id, "implement", obj("version", num(w, "version")));
    exec(c, "UPDATE work_items SET state='approved',error=NULL WHERE id=?", id);
    audit(
        c,
        "policy:autonomy/" + recipe.path("id").asText(),
        "work.implement",
        id + " v" + num(w, "version"));
  }

  /** With project-memory set to propose_on_feedback, a correction opens a memory proposal. */
  private void proposeLesson(
      java.sql.Connection c, Map<String, Object> w, String feedback, String actor)
      throws Exception {
    if (feedback.isBlank() || !level(c, "project-memory").equals("propose_on_feedback")) return;
    var recipe = MAPPER.valueToTree(withRecipe(c, new LinkedHashMap<>(w))).path("recipe");
    if (recipe.path("id").asText().equals("project-memory")) return;
    String lesson = ("mem-" + w.get("id") + "-v" + w.get("version"));
    if (lesson.length() > 80) lesson = "mem-" + sha256(lesson).substring(0, 40);
    if (one(c, "SELECT 1 FROM work_items WHERE id=?", lesson) != null) return;
    create(
        c,
        MAPPER.valueToTree(
            obj(
                "request_id",
                lesson,
                "repository",
                w.get("repo"),
                "title",
                "Memoria: lección de «" + truncate(w.get("title").toString(), 120) + "»",
                "request",
                "Propón registrar en la memoria del proyecto la lección de esta corrección del"
                    + " propietario, si es una decisión o restricción duradera. Si no lo es,"
                    + " explica por qué y no cambies nada.\n\nCorrección: "
                    + truncate(feedback, 8000),
                "recipe",
                obj("id", "project-memory", "version", 1))),
        actor);
  }

  private static String truncate(String text, int max) {
    return text.length() <= max ? text : text.substring(0, max);
  }

  public Object claim(String agent) {
    return claim(agent, true);
  }

  public Object claim(String agent, boolean integration) {
    return claim(agent, integration, false);
  }

  public Object claimIntegration(String agent) {
    return claim(agent, true, true);
  }

  private Object claim(String agent, boolean integration, boolean onlyIntegration) {
    ControlService.identifier(agent);
    return db.tx(c -> {
      if (num(one(c, "SELECT paused FROM governance WHERE id=1"), "paused") == 1) return Map.of();
      // Expired claims require operator reconciliation, never automatic duplicate inference.
      long now = clock.instant().getEpochSecond();
      var expired =
          rows(c, "SELECT item FROM work_commands WHERE state='claimed' AND updated<?", now - 120);
      for (var r : expired)
        exec(
            c,
            "UPDATE work_items SET state='blocked',error='Ejecutor desconectado; reconciliar"
                + " antes de reintentar' WHERE id=?",
            r.get("item"));
      exec(
          c,
          "UPDATE work_commands SET state='interrupted' WHERE state='claimed' AND updated<?",
          now - 120);
      // One command per executor; development work shares a global and a per-repository
      // limit, so several executors (slots) can progress different repositories at once.
      var held =
          one(c, "SELECT * FROM work_commands WHERE state='claimed' AND claimed_by=?", agent);
      if (held != null) {
        // A claim whose response was lost (no report since claiming, claimed_at still set) is
        // delivered again; once the executor reported anything it gets nothing new.
        if (held.get("claimed_at") == null) return Map.of();
        exec(
            c,
            "UPDATE work_commands SET updated=?,claimed_at=? WHERE id=?",
            now,
            now,
            held.get("id"));
        held = one(c, "SELECT * FROM work_commands WHERE id=?", held.get("id"));
        held.remove("claimed_at");
        audit(c, agent, "work.claim-redelivered", held.get("item").toString());
        return obj(
            "command",
            held,
            "item",
            withRecipe(c, one(c, "SELECT * FROM work_items WHERE id=?", held.get("item"))));
      }
      long busy =
          num(
              one(
                  c,
                  "SELECT COUNT(*) n FROM work_commands WHERE state='claimed' AND kind!='merge'"),
              "n");
      var busyRepos = new HashMap<String, Long>();
      for (var r :
          rows(
              c,
              "SELECT i.repo, COUNT(*) n FROM work_commands w JOIN work_items i ON i.id=w.item"
                  + " WHERE w.state='claimed' AND w.kind!='merge' GROUP BY i.repo"))
        busyRepos.put(r.get("repo").toString(), num(r, "n"));
      Map<String, Object> cmd = null;
      for (var candidate :
          rows(
              c,
              "SELECT w.*, i.repo FROM work_commands w JOIN work_items i ON i.id=w.item"
                  + " WHERE w.state='queued' "
                  + (onlyIntegration
                      ? "AND w.kind='merge' "
                      : integration ? "" : "AND w.kind!='merge' ")
                  + "ORDER BY w.updated,w.rowid")) {
        if (!candidate.get("kind").equals("merge")
            && (busy >= maxParallel
                || busyRepos.getOrDefault(candidate.get("repo").toString(), 0L)
                    >= maxPerRepository)) continue;
        if (InitiativeService.blockers(
                c, candidate.get("item").toString(), candidate.get("kind").toString())
            .isEmpty()) {
          cmd = candidate;
          break;
        }
      }
      if (cmd == null) return Map.of();
      exec(
          c,
          "UPDATE work_commands SET state='claimed',claimed_by=?,updated=?,claimed_at=? WHERE"
              + " id=?",
          agent,
          now,
          now,
          cmd.get("id"));
      cmd = one(c, "SELECT * FROM work_commands WHERE id=?", cmd.get("id"));
      cmd.remove("claimed_at");
      var item = one(c, "SELECT * FROM work_items WHERE id=?", cmd.get("item"));
      return obj("command", cmd, "item", withRecipe(c, item));
    });
  }

  public Object report(String command, JsonNode n, String agent) {
    return db.tx(c -> {
      var cmd = one(c, "SELECT * FROM work_commands WHERE id=?", command);
      if (cmd == null
          || !agent.equals(cmd.get("claimed_by"))
          || !cmd.get("state").equals("claimed"))
        throw new IllegalArgumentException("Command no longer owned");
      String id = cmd.get("item").toString(), phase = text(n, "state");
      // Any report confirms delivery: the claim is no longer eligible for redelivery.
      exec(
          c,
          "UPDATE work_commands SET updated=?,claimed_at=NULL WHERE id=?",
          clock.instant().getEpochSecond(),
          command);
      if (phase.equals("heartbeat"))
        return Map.of(
            "paused", num(one(c, "SELECT paused FROM governance WHERE id=1"), "paused") == 1);
      String kind = cmd.get("kind").toString();
      if (!phase.equals("blocked")
          && !phase.equals("checkpoint")
          && !((kind.equals("research") && phase.equals("proposed"))
              || (Set.of("implement", "review_existing").contains(kind) && phase.equals("review"))
              || (kind.equals("merge") && phase.equals("integrated"))))
        throw new IllegalArgumentException("Invalid result transition");
      for (String field : List.of("issue", "pr", "branch", "sha"))
        if (n.has(field)) {
          if (field.equals("sha") && !text(n, field).matches("[a-f0-9]{40}"))
            throw new IllegalArgumentException("Full SHA required");
          exec(c, "UPDATE work_items SET " + field + "=? WHERE id=?", n.get(field).asText(), id);
        }
      if (phase.equals("proposed")) {
        var p = n.path("proposal");
        if (p.has("project_memory"))
          ProjectMemory.validate(
              p.get("project_memory"),
              one(c, "SELECT repo FROM work_items WHERE id=?", id).get("repo").toString());
        var previous =
            parse(
                one(c, "SELECT proposal FROM work_items WHERE id=?", id)
                    .get("proposal")
                    .toString());
        ApprovedCriteria.apply(c, id, previous, (com.fasterxml.jackson.databind.node.ObjectNode) p);
        EvidenceService.criteria(p);
        for (String key :
            List.of("summary", "acceptance", "as_is", "to_be", "unit_test", "integration_test"))
          text(p, key);
        ((com.fasterxml.jackson.databind.node.ObjectNode) p)
            .set("doubtful_criteria", ProposalShape.doubtfulCriteria(p));
        ((com.fasterxml.jackson.databind.node.ObjectNode) p)
            .set("previous_changes", ProposalShape.changes(previous, p));
        exec(
            c,
            "UPDATE work_items SET proposal=?,version=version+1 WHERE id=?",
            Redaction.secrets(encode(p)),
            id);
      }
      if (n.has("evidence"))
        exec(
            c,
            "UPDATE work_items SET evidence=? WHERE id=?",
            Redaction.secrets(encode(n.get("evidence"))),
            id);
      if (phase.equals("checkpoint")) return Map.of("state", "recorded");
      if (phase.equals("integrated") && !n.path("merged").asBoolean())
        throw new IllegalArgumentException("GitHub merge confirmation required");
      if (phase.equals("review")) {
        var w = one(c, "SELECT pr,sha FROM work_items WHERE id=?", id);
        if (w.get("pr") == null || w.get("sha") == null)
          throw new IllegalArgumentException("PR and SHA required");
      }
      exec(
          c,
          "UPDATE work_items SET state=?,error=? WHERE id=?",
          (phase.equals("blocked") && kind.equals("merge") ? "review" : phase),
          phase.equals("blocked") ? TelemetryService.clean(text(n, "error")) : null,
          id);
      exec(c, "UPDATE work_commands SET state='done' WHERE id=?", command);
      audit(c, agent, "work." + phase, id);
      if (phase.equals("proposed")) autoApprove(c, id);
      String now = one(c, "SELECT state FROM work_items WHERE id=?", id).get("state").toString();
      // Outbox for owner notifications: only states that wait for a person.
      if (Set.of("proposed", "review", "blocked").contains(now))
        exec(
            c,
            "INSERT INTO notifications(item,state,title,created) VALUES(?,?,?,?)",
            id,
            now,
            one(c, "SELECT title FROM work_items WHERE id=?", id).get("title"),
            clock.instant().getEpochSecond());
      return Map.of("state", phase);
    });
  }
}
