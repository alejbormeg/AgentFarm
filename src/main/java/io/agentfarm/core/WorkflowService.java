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

  public WorkflowService(Store db, Clock clock) {
    this.db = db;
    this.clock = clock;
    db.tx(
        c -> {
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
          EvidenceService.schema(c);
          InitiativeService.schema(c);
          return null;
        });
  }

  public void requireAcceptance(String id) {
    db.tx(
        c -> {
          EvidenceService.requireReady(c, EvidenceService.item(c, id));
          return null;
        });
  }

  public List<Map<String, Object>> items() {
    return db.tx(
        c -> {
          var items = rows(c, "SELECT * FROM work_items ORDER BY created DESC");
          for (var item : items) withRecipe(c, item);
          return items;
        });
  }

  public Map<String, Object> get(String id) {
    return db.tx(
        c -> {
          var w = one(c, "SELECT * FROM work_items WHERE id=?", id);
          if (w == null) throw new IllegalArgumentException("Unknown work item");
          return withRecipe(c, w);
        });
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
    return db.tx(
        c -> {
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
              throw new IllegalArgumentException(
                  "Request identifier already used with another recipe");
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
        });
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
    return db.tx(
        c -> {
          var prior = one(c, "SELECT * FROM work_items WHERE id=?", id);
          if (prior != null) {
            if (Objects.equals(prior.get("sha"), sha) && !prior.get("state").equals("blocked"))
              return prior;
            if (!prior.get("state").equals("blocked")
                || !prior.get("repo").equals(repo)
                || num(prior, "issue") != number(n, "issue"))
              throw new IllegalArgumentException(
                  "Existing task does not match recoverable candidate");
            exec(
                c,
                "UPDATE work_items SET sha=?,branch=?,proposal=?,state='approved',error=NULL WHERE"
                    + " id=?",
                sha,
                branch,
                encode(proposal),
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
              encode(proposal),
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
    return db.tx(
        c -> {
          var w = one(c, "SELECT * FROM work_items WHERE id=?", id);
          if (w == null) throw new IllegalArgumentException("Unknown work item");
          String state = w.get("state").toString();
          if (action.equals("cancel")) {
            exec(
                c,
                "UPDATE work_commands SET state='cancelled',updated=? WHERE item=? AND state IN"
                    + " ('queued','claimed')",
                clock.instant().getEpochSecond(),
                id);
            exec(c, "UPDATE work_items SET state='cancelled' WHERE id=?", id);
          } else {
            if (one(
                    c,
                    "SELECT id FROM work_commands WHERE item=? AND state IN ('queued','claimed')",
                    id)
                != null) throw new IllegalArgumentException("Operation already pending");
            switch (action) {
              case "implement" -> {
                if (!state.equals("proposed") || number(n, "version") != num(w, "version"))
                  throw new IllegalArgumentException("Approve the current proposal version");
                EvidenceService.capture(c, w);
                command(c, id, "implement", obj("version", num(w, "version")));
                exec(c, "UPDATE work_items SET state='approved',error=NULL WHERE id=?", id);
              }
              case "merge" -> {
                if (!state.equals("review")
                    || w.get("pr") == null
                    || !text(n, "sha").equals(w.get("sha")))
                  throw new IllegalArgumentException("Approve the current PR commit");
                EvidenceService.requireReady(c, w);
                command(c, id, "merge", obj("sha", text(n, "sha")));
                exec(c, "UPDATE work_items SET state='integrating',error=NULL WHERE id=?", id);
              }
              case "correct" -> {
                if (!Set.of("proposed", "review", "blocked").contains(state))
                  throw new IllegalArgumentException("No result to correct");
                String feedback = text(n, "feedback");
                command(c, id, "research", obj("feedback", feedback));
                exec(c, "UPDATE work_items SET state='queued',error=NULL WHERE id=?", id);
              }
              case "retry" -> {
                if (!state.equals("blocked"))
                  throw new IllegalArgumentException("Only blocked operations can retry");
                var previous =
                    one(
                        c,
                        "SELECT kind,payload FROM work_commands WHERE item=? ORDER BY updated"
                            + " DESC,rowid DESC LIMIT 1",
                        id);
                if (previous == null || previous.get("kind").equals("merge"))
                  throw new IllegalArgumentException("Integration needs a new explicit approval");
                command(
                    c,
                    id,
                    previous.get("kind").toString(),
                    parse(previous.get("payload").toString()));
                exec(c, "UPDATE work_items SET state='queued',error=NULL WHERE id=?", id);
              }
              default -> throw new IllegalArgumentException("Unknown action");
            }
          }
          audit(c, actor, "work." + action, id);
          return one(c, "SELECT * FROM work_items WHERE id=?", id);
        });
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
    return db.tx(
        c -> {
          if (num(one(c, "SELECT paused FROM governance WHERE id=1"), "paused") == 1)
            return Map.of();
          // Expired claims require operator reconciliation, never automatic duplicate inference.
          long now = clock.instant().getEpochSecond();
          var expired =
              rows(
                  c,
                  "SELECT item FROM work_commands WHERE state='claimed' AND updated<?",
                  now - 120);
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
          if (one(c, "SELECT id FROM work_commands WHERE state='claimed'") != null) return Map.of();
          Map<String, Object> cmd = null;
          for (var candidate :
              rows(
                  c,
                  "SELECT * FROM work_commands WHERE state='queued' "
                      + (onlyIntegration
                          ? "AND kind='merge' "
                          : integration ? "" : "AND kind!='merge' ")
                      + "ORDER BY updated,rowid")) {
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
              "UPDATE work_commands SET state='claimed',claimed_by=?,updated=? WHERE id=?",
              agent,
              now,
              cmd.get("id"));
          var item = one(c, "SELECT * FROM work_items WHERE id=?", cmd.get("item"));
          return obj("command", cmd, "item", withRecipe(c, item));
        });
  }

  public Object report(String command, JsonNode n, String agent) {
    return db.tx(
        c -> {
          var cmd = one(c, "SELECT * FROM work_commands WHERE id=?", command);
          if (cmd == null
              || !agent.equals(cmd.get("claimed_by"))
              || !cmd.get("state").equals("claimed"))
            throw new IllegalArgumentException("Command no longer owned");
          String id = cmd.get("item").toString(), phase = text(n, "state");
          exec(
              c,
              "UPDATE work_commands SET updated=? WHERE id=?",
              clock.instant().getEpochSecond(),
              command);
          if (phase.equals("heartbeat"))
            return Map.of(
                "paused", num(one(c, "SELECT paused FROM governance WHERE id=1"), "paused") == 1);
          String kind = cmd.get("kind").toString();
          if (!phase.equals("blocked")
              && !phase.equals("checkpoint")
              && !((kind.equals("research") && phase.equals("proposed"))
                  || (Set.of("implement", "review_existing").contains(kind)
                      && phase.equals("review"))
                  || (kind.equals("merge") && phase.equals("integrated"))))
            throw new IllegalArgumentException("Invalid result transition");
          for (String field : List.of("issue", "pr", "branch", "sha"))
            if (n.has(field)) {
              if (field.equals("sha") && !text(n, field).matches("[a-f0-9]{40}"))
                throw new IllegalArgumentException("Full SHA required");
              exec(
                  c, "UPDATE work_items SET " + field + "=? WHERE id=?", n.get(field).asText(), id);
            }
          if (phase.equals("proposed")) {
            var p = n.path("proposal");
            if (p.has("project_memory"))
              ProjectMemory.validate(
                  p.get("project_memory"),
                  one(c, "SELECT repo FROM work_items WHERE id=?", id).get("repo").toString());
            EvidenceService.criteria(p);
            for (String key :
                List.of("summary", "acceptance", "as_is", "to_be", "unit_test", "integration_test"))
              text(p, key);
            exec(c, "UPDATE work_items SET proposal=?,version=version+1 WHERE id=?", encode(p), id);
          }
          if (n.has("evidence"))
            exec(c, "UPDATE work_items SET evidence=? WHERE id=?", encode(n.get("evidence")), id);
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
          return Map.of("state", phase);
        });
  }
}
