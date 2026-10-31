package io.agentfarm.core;

import static io.agentfarm.core.Json.*;
import static io.agentfarm.core.Store.*;

import java.time.Clock;
import java.util.*;
import org.springframework.stereotype.Service;

@Service
public class ControlService {
  private final Store store;
  private final Clock clock;
  private final BudgetService budgets;

  public ControlService(Store store, Clock clock, BudgetService budgets) {
    this.store = store;
    this.clock = clock;
    this.budgets = budgets;
  }

  public static void identifier(String id) {
    if (id == null || !id.matches("[a-zA-Z0-9_-]{1,80}"))
      throw new IllegalArgumentException("Use 1-80 letters, digits, hyphens or underscores");
  }

  public List<Map<String, Object>> nodes() {
    return store.tx(c -> decorate(rows(c, "SELECT * FROM farm_nodes ORDER BY id")));
  }

  public List<Map<String, Object>> workers() {
    return store.tx(
        c -> {
          var all =
              decorate(
                  rows(
                      c,
                      "SELECT w.*,n.enabled node_enabled,n.heartbeat node_heartbeat FROM"
                          + " farm_workers w JOIN farm_nodes n ON n.id=w.node ORDER BY w.id"));
          all.forEach(
              w -> {
                if (num(w, "node_enabled") == 0
                    || w.get("node_heartbeat") == null
                    || (clock.instant().getEpochSecond() - num(w, "node_heartbeat") < 0
                        || clock.instant().getEpochSecond() - num(w, "node_heartbeat") > 45))
                  w.put("status", "node_unavailable");
              });
          return all;
        });
  }

  private List<Map<String, Object>> decorate(List<Map<String, Object>> all) {
    long now = clock.instant().getEpochSecond();
    all.forEach(
        row ->
            row.put(
                "status",
                num(row, "enabled") == 0
                    ? "disabled"
                    : row.get("heartbeat") == null
                        ? "unknown"
                        : now - num(row, "heartbeat") < 0
                            ? "unknown"
                            : now - num(row, "heartbeat") > 45
                                ? "stale"
                                : row.get("reported_state")));
    return all;
  }

  public void registerNode(String id, String role, String actor) {
    identifier(id);
    if (!Set.of("napoleon", "snowball").contains(role))
      throw new IllegalArgumentException("Node role must be napoleon or snowball");
    store.tx(
        c -> {
          exec(c, "INSERT INTO farm_nodes(id,role) VALUES(?,?)", id, role);
          Store.audit(c, actor, "node.register", id);
          return null;
        });
  }

  public void registerWorker(String id, String node, String actor) {
    identifier(id);
    identifier(node);
    store.tx(
        c -> {
          if (one(c, "SELECT 1 FROM farm_nodes WHERE id=?", node) == null)
            throw new IllegalArgumentException("Unknown node");
          exec(c, "INSERT INTO farm_workers(id,node) VALUES(?,?)", id, node);
          Store.audit(c, actor, "worker.register", id);
          return null;
        });
  }

  public void enable(String kind, String id, boolean enabled, String actor) {
    identifier(id);
    String table =
        switch (kind) {
          case "node" -> "farm_nodes";
          case "worker" -> "farm_workers";
          default -> throw new IllegalArgumentException("Unknown entity type");
        };
    store.tx(
        c -> {
          if (exec(c, "UPDATE " + table + " SET enabled=? WHERE id=?", enabled ? 1 : 0, id) == 0)
            throw new IllegalArgumentException("Unknown entity");
          Store.audit(c, actor, kind + (enabled ? ".enable" : ".disable"), id);
          return null;
        });
  }

  public void heartbeat(String kind, String id, String state, String actor) {
    identifier(id);
    if (!Set.of("active", "idle", "busy", "error").contains(state))
      throw new IllegalArgumentException("Unknown heartbeat state");
    String table =
        switch (kind) {
          case "node" -> "farm_nodes";
          case "worker" -> "farm_workers";
          default -> throw new IllegalArgumentException("Unknown entity type");
        };
    store.tx(
        c -> {
          if (exec(
                  c,
                  "UPDATE " + table + " SET heartbeat=?,reported_state=? WHERE id=?",
                  clock.instant().getEpochSecond(),
                  state,
                  id)
              == 0) throw new IllegalArgumentException("Unknown entity");
          return null;
        });
  }

  public void pause(boolean paused, String actor) {
    store.tx(
        c -> {
          exec(c, "UPDATE governance SET paused=? WHERE id=1", paused ? 1 : 0);
          Store.audit(c, actor, paused ? "farm.pause" : "farm.resume", "farm");
          return null;
        });
  }

  public boolean paused() {
    return store.tx(c -> num(one(c, "SELECT paused FROM governance WHERE id=1"), "paused") == 1);
  }

  public List<Map<String, Object>> audit() {
    return store.tx(c -> rows(c, "SELECT * FROM audit ORDER BY id DESC LIMIT 200"));
  }

  public void cancel(String project, String id, String actor) {
    store.tx(
        c -> {
          var row = one(c, "SELECT state FROM tasks WHERE project=? AND id=?", project, id);
          if (row == null) throw new IllegalArgumentException("Unknown task");
          if (row.get("state").equals("ready"))
            exec(c, "UPDATE tasks SET state='cancelled' WHERE project=? AND id=?", project, id);
          else if (Set.of("assigned", "running").contains(row.get("state")))
            exec(
                c,
                "INSERT INTO task_runtime(project,id,cancel_requested) VALUES(?,?,1) ON"
                    + " CONFLICT(project,id) DO UPDATE SET cancel_requested=1",
                project,
                id);
          else throw new IllegalArgumentException("Only ready or running tasks can be cancelled");
          Store.audit(c, actor, "task.cancel", project + "/" + id);
          return null;
        });
  }

  public Map<String, Object> assign(com.fasterxml.jackson.databind.JsonNode request, String actor) {
    String project = text(request, "project"),
        id = text(request, "id"),
        worker = text(request, "worker"),
        account = text(request, "account"),
        run = text(request, "run_id");
    identifier(worker);
    identifier(run);
    return store.tx(
        c -> {
          if (num(one(c, "SELECT paused FROM governance WHERE id=1"), "paused") == 1)
            return obj("state", "paused", "reason", "human_pause");
          var task = one(c, "SELECT * FROM tasks WHERE project=? AND id=?", project, id);
          if (task == null || !task.get("state").equals("ready"))
            throw new IllegalArgumentException("Task must be ready");
          var w =
              one(
                  c,
                  "SELECT w.*,n.enabled ne,n.heartbeat nh FROM farm_workers w JOIN farm_nodes n ON"
                      + " n.id=w.node WHERE w.id=?",
                  worker);
          long now = clock.instant().getEpochSecond();
          if (w == null
              || num(w, "enabled") == 0
              || num(w, "ne") == 0
              || w.get("heartbeat") == null
              || w.get("nh") == null
              || now - num(w, "heartbeat") < 0
              || now - num(w, "nh") < 0
              || now - num(w, "heartbeat") > 45
              || now - num(w, "nh") > 45
              || !w.get("reported_state").equals("idle"))
            throw new IllegalArgumentException("Worker and node must be enabled, fresh and idle");
          if (num(one(c, "SELECT count(*) n FROM tasks WHERE state IN ('assigned','running')"), "n")
              > 0) return obj("state", "paused", "reason", "worker_capacity");
          if (num(one(c, "SELECT count(*) n FROM tasks WHERE state='review'"), "n") >= 2)
            return obj("state", "paused", "reason", "review_capacity");
          String day = clock.instant().toString().substring(0, 10);
          if (num(one(c, "SELECT count(*) n FROM runs WHERE substr(started,1,10)=?", day), "n")
              >= 5) return obj("state", "paused", "reason", "daily_limit");
          var reservation = budgets.reserve(c, account, request);
          if (!reservation.get("state").equals("reserved")) return reservation;
          exec(c, "UPDATE tasks SET state='assigned' WHERE project=? AND id=?", project, id);
          exec(
              c,
              "INSERT INTO task_runtime(project,id,worker,progress) VALUES(?,?,?,NULL) ON"
                  + " CONFLICT(project,id) DO UPDATE SET"
                  + " worker=excluded.worker,progress=NULL,cancel_requested=0",
              project,
              id,
              worker);
          exec(
              c,
              "INSERT INTO runs(project,task,hash,started,mode,result) VALUES(?,?,?,?,?,?)",
              project,
              id,
              task.get("hash"),
              clock.instant().toString(),
              "reported",
              encode(
                  obj(
                      "state",
                      "assigned",
                      "budget_account",
                      account,
                      "budget_run",
                      run,
                      "implementation_evidence",
                      List.of(),
                      "implementation_verified",
                      false)));
          exec(c, "UPDATE farm_workers SET reported_state='busy' WHERE id=?", worker);
          Store.audit(c, actor, "task.assign", project + "/" + id + " -> " + worker);
          return obj(
              "state",
              "assigned",
              "worker",
              worker,
              "spec_hash",
              task.get("hash"),
              "execution_started",
              false);
        });
  }

  public void progress(String project, String id, String worker, int progress, String actor) {
    if (progress < 0 || progress > 100)
      throw new IllegalArgumentException("Progress must be 0..100");
    store.tx(
        c -> {
          var task = one(c, "SELECT state FROM tasks WHERE project=? AND id=?", project, id);
          if (task == null || !Set.of("assigned", "running").contains(task.get("state")))
            throw new IllegalArgumentException("Task is not assigned or running");
          var assigned =
              one(c, "SELECT worker FROM task_runtime WHERE project=? AND id=?", project, id);
          if (assigned == null || !Objects.equals(worker, assigned.get("worker")))
            throw new IllegalArgumentException("Worker is not assigned to this task");
          exec(c, "UPDATE tasks SET state='running' WHERE project=? AND id=?", project, id);
          exec(
              c,
              "UPDATE task_runtime SET progress=? WHERE project=? AND id=?",
              progress,
              project,
              id);
          return null;
        });
  }
}
