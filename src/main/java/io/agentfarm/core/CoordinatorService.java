package io.agentfarm.core;

import static io.agentfarm.core.Json.*;
import static io.agentfarm.core.Store.*;

import com.fasterxml.jackson.databind.JsonNode;
import java.sql.Connection;
import java.time.Clock;
import java.time.Instant;
import java.util.*;
import org.springframework.stereotype.Service;

@Service
public class CoordinatorService {
  private final Store store;
  private final Clock clock;

  public CoordinatorService(Store store, Clock clock) {
    this.store = store;
    this.clock = clock;
  }

  public static void validate(JsonNode spec) {
    if (spec == null || !spec.isObject())
      throw new IllegalArgumentException("Task must be an object");
    for (String key : List.of("id", "project", "title")) text(spec, key);
    for (String key : List.of("acceptance", "allowed_paths")) {
      JsonNode list = spec.get(key);
      if (list == null || !list.isArray() || list.isEmpty())
        throw new IllegalArgumentException("Missing nonempty list: " + key);
      list.forEach(v -> {
        if (!v.isTextual() || v.asText().isBlank())
          throw new IllegalArgumentException("Invalid entries: " + key);
      });
    }
    spec.get("allowed_paths")
        .forEach(v -> {
          String p = v.asText();
          if (p.startsWith("/")
              || p.contains("\\")
              || p.contains(":")
              || List.of(p.split("/")).contains("..")
              || p.equals(".")
              || p.equals("./"))
            throw new IllegalArgumentException("Paths must be relative, scoped POSIX paths");
        });
  }

  public Map<String, Object> enqueue(JsonNode spec, String actor) {
    validate(spec);
    String payload = canonical(spec),
        hash = sha256(payload),
        project = text(spec, "project"),
        id = text(spec, "id");
    return store.tx(c -> {
      var prior = one(c, "SELECT hash,spec FROM tasks WHERE project=? AND id=?", project, id);
      if (prior != null) {
        if (!hash.equals(prior.get("hash"))
            && !canonical(parse((String) prior.get("spec"))).equals(payload))
          throw new IllegalArgumentException(
              "Task ID already exists with a different specification");
        return obj("state", "already_enqueued");
      }
      exec(c, "INSERT INTO tasks VALUES(?,?,?,?,?)", project, id, payload, hash, "ready");
      exec(c, "INSERT INTO task_runtime(project,id) VALUES(?,?)", project, id);
      audit(c, actor, "task.enqueue", project + "/" + id);
      return obj("state", "enqueued", "spec_hash", hash);
    });
  }

  public Map<String, Object> simulate(int maxPending, int maxDaily, String actor) {
    if (maxPending < 1 || maxDaily < 1)
      throw new IllegalArgumentException("Limits must be positive");
    return store.tx(c -> {
      if (num(one(c, "SELECT paused FROM governance WHERE id=1"), "paused") == 1)
        return obj("state", "paused", "reason", "human_pause");
      if (count(c, "state IN ('assigned','running')") > 0)
        return obj("state", "paused", "reason", "running_task_requires_reconciliation");
      if (count(c, "state='review'") >= maxPending)
        return obj("state", "paused", "reason", "review_capacity");
      String now = Instant.now(clock).toString();
      if (num(
              one(
                  c,
                  "SELECT count(*) n FROM runs WHERE substr(started,1,10)=?",
                  now.substring(0, 10)),
              "n")
          >= maxDaily) return obj("state", "paused", "reason", "daily_limit");
      var task = one(c, "SELECT * FROM tasks WHERE state='ready' ORDER BY project,id LIMIT 1");
      if (task == null) return obj("state", "idle");
      exec(
          c,
          "UPDATE tasks SET state='running' WHERE project=? AND id=?",
          task.get("project"),
          task.get("id"));
      exec(
          c,
          "INSERT INTO runs(project,task,hash,started,mode) VALUES(?,?,?,?,?)",
          task.get("project"),
          task.get("id"),
          task.get("hash"),
          now,
          "simulation");
      long runId = num(one(c, "SELECT last_insert_rowid() id"), "id");
      var result =
          obj(
              "run_id",
              runId,
              "state",
              "review",
              "mode",
              "simulation",
              "task",
              task.get("id"),
              "project",
              task.get("project"),
              "spec_hash",
              task.get("hash"),
              "summary",
              "Coordination simulated; no code implemented, no agent invoked.",
              "implementation_evidence",
              List.of(),
              "cost",
              null);
      exec(c, "UPDATE runs SET result=? WHERE id=?", encode(result), runId);
      exec(
          c,
          "UPDATE tasks SET state='review' WHERE project=? AND id=?",
          task.get("project"),
          task.get("id"));
      audit(c, actor, "task.simulate", task.get("project") + "/" + task.get("id"));
      return result;
    });
  }

  private long count(Connection c, String condition) throws Exception {
    return num(one(c, "SELECT count(*) n FROM tasks WHERE " + condition), "n");
  }

  public List<Map<String, Object>> tasks() {
    return store.tx(c ->
        rows(
            c,
            "SELECT"
                + " t.project,t.id,t.hash,t.state,t.spec,r.worker,r.progress,r.candidate_sha,r.cancel_requested"
                + " FROM tasks t LEFT JOIN task_runtime r USING(project,id) ORDER BY"
                + " t.project,t.id"));
  }

  public List<Map<String, Object>> report() {
    return store.tx(c -> {
      var result = rows(c, "SELECT * FROM runs ORDER BY id");
      result.forEach(r -> {
        if (r.get("result") != null) r.put("result", parse((String) r.get("result")));
      });
      return result;
    });
  }
}
