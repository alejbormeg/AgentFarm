package io.agentfarm.adapters;

import static io.agentfarm.core.Json.sha256;
import static io.agentfarm.core.Store.*;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.agentfarm.core.InitiativeService;
import io.agentfarm.core.Store;
import io.agentfarm.core.WorkflowService;
import java.sql.Connection;
import java.time.Clock;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.stereotype.Service;

/**
 * A change that affects several repositories of a group: the plan of the integrator's task lists
 * {@code parts}, one per repository, and approving it opens one task per part, each in its own
 * repository, under that repository's own profile, token and approvals. A part waits for the ones
 * it depends on to be integrated (the existing initiative gate). Nothing the agents write decides
 * which repositories take part: a part is accepted only in the integrator's repository or in a
 * member of the group the owner declared, and only in repositories the panel already knows.
 */
@Service
public final class ParentTasks implements WorkflowService.Parts {
  public static final int MAX_PARTS = 8, MAX_TITLE = 180, MAX_REQUEST = 4000;

  private static final ObjectMapper MAPPER = new ObjectMapper();
  private final Store db;
  private final Clock clock;
  private final Autopilot autopilot;

  /** {@code initiatives} is here so its tables exist before the first part is linked. */
  public ParentTasks(
      Store db,
      Clock clock,
      WorkflowService work,
      InitiativeService initiatives,
      Autopilot autopilot) {
    this.db = db;
    this.clock = clock;
    this.autopilot = autopilot;
    db.tx(c -> {
      statement(
          c,
          "CREATE TABLE IF NOT EXISTS work_parts(parent TEXT NOT NULL REFERENCES work_items(id),idx"
              + " INTEGER NOT NULL,child TEXT NOT NULL UNIQUE REFERENCES work_items(id),repo TEXT"
              + " NOT NULL,title TEXT NOT NULL,closes INTEGER NOT NULL DEFAULT 0,PRIMARY"
              + " KEY(parent,idx))");
      return null;
    });
    work.parts(this);
  }

  /** The parts that passed, or why none did. */
  record Checked(ArrayNode parts, String problem) {}

  private static String clean(String text, int max) {
    String t = text == null ? "" : text.replace("\u0000", "").replace("\r", "").strip();
    return t.length() > max ? t.substring(0, max).strip() : t;
  }

  /**
   * What an agent proposed, reduced to what the panel accepts. {@code repo} is the integrator's
   * repository and {@code members} the group the owner declared for it.
   */
  static Checked check(JsonNode raw, String repo, Set<String> members) {
    if (members.isEmpty())
      return new Checked(
          null,
          "Este repositorio no tiene un grupo: el cambio no se puede repartir. Crea el grupo en"
              + " Gobierno → Repositorios relacionados.");
    if (!raw.isArray() || raw.isEmpty() || raw.size() > MAX_PARTS)
      return new Checked(null, "Las partes deben ser una lista de 1 a " + MAX_PARTS);
    var out = MAPPER.createArrayNode();
    var deps = new ArrayList<Set<Integer>>();
    for (int i = 0; i < raw.size(); i++) {
      var part = raw.get(i);
      String where = "Parte " + (i + 1) + ": ";
      String partRepo = part.path("repo").asText("");
      try {
        RepoGroups.repo(partRepo);
      } catch (IllegalArgumentException e) {
        return new Checked(null, where + "repositorio no válido");
      }
      String known = null;
      if (partRepo.equalsIgnoreCase(repo)) known = repo;
      for (String m : members) if (m.equalsIgnoreCase(partRepo)) known = m;
      if (known == null) return new Checked(null, where + partRepo + " no es del grupo de " + repo);
      String title = clean(part.path("title").asText(""), MAX_TITLE).replace('\n', ' ');
      String request = clean(part.path("request").asText(""), MAX_REQUEST);
      if (title.isEmpty() || request.isEmpty())
        return new Checked(null, where + "falta el título o lo que se pide");
      var mine = new HashSet<Integer>();
      var depends = MAPPER.createArrayNode();
      if (part.has("depends_on") && !part.path("depends_on").isArray())
        return new Checked(null, where + "depends_on debe ser una lista de números");
      for (var d : part.path("depends_on")) {
        if (!d.isInt() || d.asInt() < 0 || d.asInt() >= i)
          return new Checked(
              null, where + "una parte solo puede depender de partes anteriores (desde 0)");
        if (mine.add(d.asInt())) depends.add(d.asInt());
      }
      deps.add(mine);
      var clean = out.addObject();
      clean.put("repo", known);
      clean.put("title", title);
      clean.put("request", request);
      clean.set("depends_on", depends);
    }
    // Its PR is the one that may close the integrator's issue: it comes after every other part.
    for (int i = out.size() - 1; i >= 0; i--) {
      if (!out.get(i).path("repo").asText().equalsIgnoreCase(repo)) continue;
      if (ancestors(deps, i).size() == out.size() - 1) {
        ((ObjectNode) out.get(i)).put("closes_parent", true);
        break;
      }
    }
    return new Checked(out, null);
  }

  private static Set<Integer> ancestors(List<Set<Integer>> deps, int part) {
    var seen = new HashSet<Integer>();
    var todo = new ArrayList<>(deps.get(part));
    while (!todo.isEmpty()) {
      int d = todo.remove(todo.size() - 1);
      if (seen.add(d)) todo.addAll(deps.get(d));
    }
    return seen;
  }

  // ---- what the workflow asks ----

  @Override
  public String shape(Object tx, String item, String repo, String proposalJson) {
    ObjectNode proposal = (ObjectNode) io.agentfarm.core.Json.parse(proposalJson);
    shape(tx, repo, proposal);
    return io.agentfarm.core.Json.encode(proposal);
  }

  private void shape(Object tx, String repo, ObjectNode proposal) {
    proposal.remove("parts_problem");
    var raw = proposal.get("parts");
    if (raw == null || raw.isNull() || (raw.isArray() && raw.isEmpty())) {
      proposal.remove("parts");
      return;
    }
    Checked checked;
    try {
      checked = check(raw, repo, RepoGroups.membersIn((Connection) tx, repo));
    } catch (Exception e) {
      checked = new Checked(null, "No se pudo comprobar el grupo del repositorio");
    }
    if (checked.problem() != null) {
      // The proposal is kept (a paid research turn is not lost): the owner sees why it does not
      // split and can send it back.
      proposal.remove("parts");
      proposal.put("parts_problem", checked.problem());
    } else proposal.set("parts", checked.parts());
  }

  @Override
  public boolean hasParts(String proposalJson) {
    try {
      var parts = io.agentfarm.core.Json.parse(proposalJson).path("parts");
      return parts.isArray() && !parts.isEmpty();
    } catch (Exception e) {
      return false;
    }
  }

  @Override
  public boolean split(
      Object tx, Map<String, Object> item, String actor, WorkflowService.Spawn spawn)
      throws Exception {
    var c = (Connection) tx;
    String parent = item.get("id").toString(), repo = item.get("repo").toString();
    var proposal = io.agentfarm.core.Json.parse(item.get("proposal").toString());
    if (!proposal.path("parts").isArray() || proposal.path("parts").isEmpty()) return false;
    if (one(c, "SELECT 1 FROM work_parts WHERE parent=? LIMIT 1", parent) != null)
      throw new IllegalArgumentException("Este cambio ya se ha repartido");
    var checked = check(proposal.path("parts"), repo, RepoGroups.membersIn(c, repo));
    if (checked.problem() != null)
      throw new IllegalArgumentException("Las partes ya no valen: " + checked.problem());
    var parts = checked.parts();
    for (var part : parts) {
      String partRepo = part.path("repo").asText();
      if (!partRepo.equalsIgnoreCase(repo)
          && one(c, "SELECT 1 FROM work_items WHERE repo=? LIMIT 1", partRepo) == null)
        throw new IllegalArgumentException(
            "Adopta primero " + partRepo + ": el panel todavía no trabaja en él");
    }
    String issue = item.get("issue") == null ? null : item.get("issue").toString();
    String base = "parte-" + sha256(parent).substring(0, 8);
    long now = clock.instant().getEpochSecond();
    exec(
        c,
        "INSERT INTO initiatives(id,title,goal,review_limit,paused,version,milestones,request_hash,created,actor)"
            + " VALUES(?,?,?,?,0,1,?,?,?,?)",
        base,
        clip(item.get("title").toString(), 160),
        clip("Partes de " + item.get("title") + " (tarea " + parent + ")", 1000),
        10,
        "[{\"id\":\"partes\",\"title\":\"Partes\"}]",
        sha256(base),
        now,
        actor);
    var children = new ArrayList<String>();
    for (int i = 0; i < parts.size(); i++) {
      var part = parts.get(i);
      String child = base + "-" + (i + 1), partRepo = part.path("repo").asText();
      boolean closes = part.path("closes_parent").asBoolean(false);
      String header =
          issue == null
              ? "Parte de la tarea " + parent + "\n"
              : "Parte de: https://github.com/"
                  + repo
                  + "/issues/"
                  + issue
                  + (closes ? " · cierra" : "")
                  + "\n";
      String request =
          header
              + "Es una parte de un cambio que afecta a varios repositorios: haz solo lo que pide"
              + " esta parte, en este repositorio.\n\n"
              + part.path("request").asText();
      spawn.create(
          io.agentfarm.core.Json.encode(
              Map.of(
                  "request_id",
                  child,
                  "repository",
                  partRepo,
                  "title",
                  part.path("title").asText(),
                  "request",
                  request)));
      exec(
          c,
          "INSERT INTO work_parts(parent,idx,child,repo,title,closes) VALUES(?,?,?,?,?,?)",
          parent,
          i,
          child,
          partRepo,
          part.path("title").asText(),
          closes ? 1 : 0);
      exec(
          c,
          "INSERT INTO initiative_tasks(item,initiative,milestone) VALUES(?,?,'partes')",
          child,
          base);
      for (var d : part.path("depends_on"))
        exec(
            c,
            "INSERT INTO work_dependencies(item,predecessor) VALUES(?,?)",
            child,
            base + "-" + (d.asInt() + 1));
      autopilot.markIn(c, child, partRepo, actor);
      children.add(child);
    }
    exec(c, "UPDATE work_items SET state='coordinating',error=NULL WHERE id=?", parent);
    audit(c, actor, "work.split", parent + " -> " + String.join(",", children));
    return true;
  }

  @Override
  public void childIntegrated(Object tx, String child) throws Exception {
    var c = (Connection) tx;
    var link = one(c, "SELECT parent FROM work_parts WHERE child=?", child);
    if (link == null) return;
    String parent = link.get("parent").toString();
    long open =
        num(
            one(
                c,
                "SELECT COUNT(*) n FROM work_parts p JOIN work_items w ON w.id=p.child WHERE"
                    + " p.parent=? AND w.state!='integrated'",
                parent),
            "n");
    if (open > 0) return;
    exec(c, "UPDATE work_items SET state='integrated' WHERE id=? AND state='coordinating'", parent);
    audit(c, "policy:parts", "work.coordinated", parent);
  }

  @Override
  public void cancelChildren(Object tx, String parent) throws Exception {
    var c = (Connection) tx;
    long at = clock.instant().getEpochSecond();
    for (var p : rows(c, "SELECT child FROM work_parts WHERE parent=?", parent)) {
      String child = p.get("child").toString();
      // An integrated part is already in its repository, and one that is integrating cannot be cut
      // halfway: only what has not reached its branch is cancelled.
      var w = one(c, "SELECT state FROM work_items WHERE id=?", child);
      if (!Set.of("queued", "approved", "proposed", "review", "blocked")
          .contains(w.get("state").toString())) continue;
      exec(
          c,
          "UPDATE work_commands SET state='cancelled',updated=? WHERE item=? AND state IN"
              + " ('queued','claimed')",
          at,
          child);
      exec(c, "UPDATE work_items SET state='cancelled' WHERE id=?", child);
      audit(c, "policy:parts", "work.cancel", child + " con su tarea padre " + parent);
    }
  }

  private static String clip(String text, int max) {
    String t = text == null ? "" : text.strip();
    return t.length() > max ? t.substring(0, max) : t;
  }

  /** Every part of every split task, with the state of its task, for the board. */
  public List<Map<String, Object>> all() {
    return db.tx(c ->
        rows(
            c,
            "SELECT p.parent,p.idx,p.child,p.repo,p.title,p.closes,w.state child_state FROM"
                + " work_parts p JOIN work_items w ON w.id=p.child ORDER BY p.parent,p.idx"));
  }
}
