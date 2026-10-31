package io.agentfarm.core;

import static io.agentfarm.core.Json.*;
import static io.agentfarm.core.Store.*;

import com.fasterxml.jackson.databind.JsonNode;
import java.sql.Connection;
import java.time.Clock;
import java.util.*;
import org.springframework.stereotype.Service;

/** Coordinates already approved workflow tasks. Never approves scope or merge. */
@Service
public class InitiativeService {
  private final Store db;
  private final Clock clock;

  public InitiativeService(Store db, Clock clock) {
    this.db = db;
    this.clock = clock;
    db.tx(
        c -> {
          schema(c);
          return null;
        });
  }

  static void schema(Connection c) throws Exception {
    statement(
        c,
        "CREATE TABLE IF NOT EXISTS initiatives(id TEXT PRIMARY KEY,title TEXT NOT NULL,goal TEXT"
            + " NOT NULL,review_limit INTEGER NOT NULL,paused INTEGER NOT NULL DEFAULT 0,version"
            + " INTEGER NOT NULL DEFAULT 1,milestones TEXT NOT NULL,request_hash TEXT NOT"
            + " NULL,created INTEGER NOT NULL,actor TEXT NOT NULL)");
    statement(
        c,
        "CREATE TABLE IF NOT EXISTS initiative_tasks(item TEXT PRIMARY KEY REFERENCES"
            + " work_items(id),initiative TEXT NOT NULL REFERENCES initiatives(id),milestone TEXT"
            + " NOT NULL)");
    statement(
        c,
        "CREATE TABLE IF NOT EXISTS work_dependencies(item TEXT NOT NULL REFERENCES"
            + " work_items(id),predecessor TEXT NOT NULL REFERENCES work_items(id),PRIMARY"
            + " KEY(item,predecessor),CHECK(item!=predecessor))");
  }

  private static void human(String actor) {
    if (actor.startsWith("node:"))
      throw new IllegalArgumentException("Only the operator can govern initiatives");
  }

  private static Map<String, Object> initiative(Connection c, String id) throws Exception {
    var row = one(c, "SELECT * FROM initiatives WHERE id=?", id);
    if (row == null) throw new IllegalArgumentException("Unknown initiative");
    return row;
  }

  private static void version(Map<String, Object> row, JsonNode n) {
    if (number(n, "version") != num(row, "version"))
      throw new IllegalArgumentException(
          "Initiative changed; reload before applying this decision");
  }

  public Object create(JsonNode n, String actor) {
    human(actor);
    String id = text(n, "request_id"), title = text(n, "title"), goal = text(n, "goal");
    long limit = number(n, "review_limit");
    var milestones = n.path("milestones");
    if (!id.matches("[a-zA-Z0-9-]{8,80}")
        || title.length() > 160
        || goal.length() > 4000
        || limit < 1
        || limit > 10
        || !milestones.isArray()
        || milestones.isEmpty()
        || milestones.size() > 20)
      throw new IllegalArgumentException(
          "Invalid initiative; review limit 1–10 and 1–20 milestones required");
    Set<String> ids = new HashSet<>();
    for (var milestone : milestones) {
      String key = text(milestone, "id");
      if (!key.matches("[A-Za-z0-9_-]{1,40}")
          || !ids.add(key)
          || text(milestone, "title").length() > 160)
        throw new IllegalArgumentException("Invalid or duplicate milestone");
    }
    String hash = sha256(canonical(n));
    return db.tx(
        c -> {
          var old = one(c, "SELECT * FROM initiatives WHERE id=?", id);
          if (old != null) {
            if (!old.get("request_hash").equals(hash))
              throw new IllegalArgumentException("Initiative request id already used");
            return snapshot(c, old);
          }
          exec(
              c,
              "INSERT INTO"
                  + " initiatives(id,title,goal,review_limit,milestones,request_hash,created,actor)"
                  + " VALUES(?,?,?,?,?,?,?,?)",
              id,
              title,
              goal,
              limit,
              encode(milestones),
              hash,
              clock.instant().getEpochSecond(),
              actor);
          audit(c, actor, "initiative.create", id);
          return snapshot(c, initiative(c, id));
        });
  }

  public Object pause(String id, JsonNode n, String actor) {
    human(actor);
    if (!n.path("paused").isBoolean())
      throw new IllegalArgumentException("Boolean pause state required");
    return db.tx(
        c -> {
          var row = initiative(c, id);
          version(row, n);
          exec(
              c,
              "UPDATE initiatives SET paused=?,version=version+1 WHERE id=?",
              n.path("paused").asBoolean() ? 1 : 0,
              id);
          audit(c, actor, "initiative.pause", id + "=" + n.path("paused").asBoolean());
          return snapshot(c, initiative(c, id));
        });
  }

  public Object link(String id, JsonNode n, String actor) {
    human(actor);
    String item = text(n, "item"), milestone = text(n, "milestone");
    var supplied = n.path("depends_on");
    if (!supplied.isArray() || supplied.size() > 100)
      throw new IllegalArgumentException("Provide an explicit dependency array, maximum 100");
    Set<String> dependencies = new TreeSet<>();
    for (var predecessor : supplied) {
      if (!predecessor.isTextual()
          || predecessor.asText().isBlank()
          || !dependencies.add(predecessor.asText()))
        throw new IllegalArgumentException("Invalid or duplicate dependency");
    }
    return db.tx(
        c -> {
          var row = initiative(c, id);
          boolean valid = false;
          for (var entry : parse(row.get("milestones").toString()))
            if (entry.path("id").asText().equals(milestone)) valid = true;
          if (!valid) throw new IllegalArgumentException("Unknown milestone");
          var work = one(c, "SELECT * FROM work_items WHERE id=?", item);
          if (work == null) throw new IllegalArgumentException("Unknown task");
          var old = one(c, "SELECT * FROM initiative_tasks WHERE item=?", item);
          if (old != null && !old.get("initiative").equals(id))
            throw new IllegalArgumentException("Task already belongs to another initiative");
          var existing = dependencies(c, item);
          if (old != null
              && old.get("milestone").equals(milestone)
              && new TreeSet<>(existing).equals(dependencies)) return snapshot(c, row);
          version(row, n);
          if (one(c, "SELECT id FROM work_commands WHERE item=? AND state='claimed'", item) != null)
            throw new IllegalArgumentException(
                "Cannot change coordination while an order is executing");
          boolean frozen =
              Set.of("review", "integrating", "integrated", "cancelled")
                  .contains(work.get("state").toString());
          if (frozen
              && (old != null
                  || !dependencies.isEmpty()
                  || !Set.of("review", "integrated").contains(work.get("state"))))
            throw new IllegalArgumentException(
                "Reviewed or completed tasks cannot change dependencies");
          for (String predecessor : dependencies) {
            if (predecessor.equals(item)
                || one(
                        c,
                        "SELECT item FROM initiative_tasks WHERE item=? AND initiative=?",
                        predecessor,
                        id)
                    == null)
              throw new IllegalArgumentException(
                  "Predecessor must be another task in this initiative");
          }
          if (old == null)
            exec(c, "INSERT INTO initiative_tasks VALUES(?,?,?)", item, id, milestone);
          else exec(c, "UPDATE initiative_tasks SET milestone=? WHERE item=?", milestone, item);
          exec(c, "DELETE FROM work_dependencies WHERE item=?", item);
          for (String predecessor : dependencies)
            exec(c, "INSERT INTO work_dependencies VALUES(?,?)", item, predecessor);
          if (one(
                  c,
                  "WITH RECURSIVE ancestors(id) AS (SELECT predecessor FROM work_dependencies WHERE"
                      + " item=? UNION SELECT d.predecessor FROM work_dependencies d JOIN ancestors"
                      + " a ON d.item=a.id) SELECT id FROM ancestors WHERE id=?",
                  item,
                  item)
              != null) throw new IllegalArgumentException("Dependency cycle rejected");
          exec(c, "UPDATE initiatives SET version=version+1 WHERE id=?", id);
          audit(
              c,
              actor,
              "initiative.link",
              id + "/" + item + " -> " + String.join(",", dependencies));
          return snapshot(c, initiative(c, id));
        });
  }

  static List<String> dependencies(Connection c, String item) throws Exception {
    return rows(
            c, "SELECT predecessor FROM work_dependencies WHERE item=? ORDER BY predecessor", item)
        .stream()
        .map(r -> r.get("predecessor").toString())
        .toList();
  }

  static long reviewSlots(Connection c, String id) throws Exception {
    return num(
        one(
            c,
            "SELECT count(*) count FROM initiative_tasks t JOIN work_items w ON w.id=t.item WHERE"
                + " t.initiative=? AND (w.state IN ('review','integrating') OR EXISTS(SELECT 1 FROM"
                + " work_commands cmd WHERE cmd.item=w.id AND cmd.state='claimed' AND cmd.kind IN"
                + " ('implement','review_existing')))",
            id),
        "count");
  }

  static List<String> blockers(Connection c, String item, String kind) throws Exception {
    var row =
        one(
            c,
            "SELECT i.* FROM initiative_tasks t JOIN initiatives i ON i.id=t.initiative WHERE"
                + " t.item=?",
            item);
    if (row == null) return List.of();
    var reasons = new ArrayList<String>();
    if (num(row, "paused") == 1) reasons.add("initiative_paused");
    for (var dependency :
        rows(
            c,
            "SELECT w.id,w.title,w.state FROM work_dependencies d JOIN work_items w ON"
                + " w.id=d.predecessor WHERE d.item=? AND w.state!='integrated'",
            item))
      reasons.add(
          "dependency_not_integrated:" + dependency.get("id") + ":" + dependency.get("state"));
    if (Set.of("implement", "review_existing").contains(kind)
        && reviewSlots(c, row.get("id").toString()) >= num(row, "review_limit"))
      reasons.add("initiative_review_limit");
    return reasons;
  }

  public Object list() {
    return db.tx(
        c -> {
          var result = new ArrayList<>();
          for (var row : rows(c, "SELECT * FROM initiatives ORDER BY created,id"))
            result.add(snapshot(c, row));
          return result;
        });
  }

  public Object get(String id) {
    return db.tx(c -> snapshot(c, initiative(c, id)));
  }

  static Map<String, Object> snapshot(Connection c, Map<String, Object> initiative)
      throws Exception {
    String id = initiative.get("id").toString();
    var tasks =
        rows(
            c,
            "SELECT w.id,w.repo,w.title,w.state,w.issue,w.pr,t.milestone FROM initiative_tasks t"
                + " JOIN work_items w ON w.id=t.item WHERE t.initiative=? ORDER BY w.created,w.id",
            id);
    for (var task : tasks) {
      task.put("depends_on", dependencies(c, task.get("id").toString()));
      var command =
          one(
              c,
              "SELECT kind,state FROM work_commands WHERE item=? AND state IN ('queued','claimed')",
              task.get("id"));
      boolean active = command != null && command.get("state").equals("claimed");
      task.put("in_progress", active);
      String kind =
          command == null
              ? (task.get("state").equals("proposed") ? "implement" : "none")
              : command.get("kind").toString();
      task.put(
          "blockers",
          active || Set.of("integrated", "cancelled").contains(task.get("state"))
              ? List.of()
              : blockers(c, task.get("id").toString(), kind));
    }
    var milestones = new ArrayList<Map<String, Object>>();
    long done = 0;
    for (var entry : parse(initiative.get("milestones").toString())) {
      var members =
          tasks.stream().filter(t -> t.get("milestone").equals(entry.path("id").asText())).toList();
      long integrated = members.stream().filter(t -> t.get("state").equals("integrated")).count();
      done += integrated;
      milestones.add(
          obj(
              "id",
              entry.path("id").asText(),
              "title",
              entry.path("title").asText(),
              "total",
              members.size(),
              "integrated",
              integrated,
              "complete",
              !members.isEmpty() && members.size() == integrated));
    }
    return obj(
        "id",
        id,
        "title",
        initiative.get("title"),
        "goal",
        initiative.get("goal"),
        "version",
        initiative.get("version"),
        "paused",
        num(initiative, "paused") == 1,
        "review_limit",
        initiative.get("review_limit"),
        "review_slots",
        reviewSlots(c, id),
        "milestones",
        milestones,
        "tasks",
        tasks,
        "total",
        tasks.size(),
        "integrated",
        done,
        "complete",
        !tasks.isEmpty() && done == tasks.size());
  }
}
