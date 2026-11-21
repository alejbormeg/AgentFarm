package io.agentfarm.adapters;

import static io.agentfarm.core.Store.*;

import com.fasterxml.jackson.databind.JsonNode;
import io.agentfarm.core.Store;
import java.time.Clock;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.stereotype.Service;

/**
 * A repository that integrates others (the "integrator") and the repositories it integrates. Only
 * the owner declares it, here in the panel: it is what says which repositories may take part in one
 * change, so nothing written in a repository (a profile, a proposal, an issue) can widen it. It
 * grants no permission by itself: every member keeps its own profile, token and approvals.
 */
@Service
public final class RepoGroups {
  public static final int MAX_MEMBERS = 20, MAX_ROLE = 160;

  private final Store db;
  private final Clock clock;

  public RepoGroups(Store db, Clock clock) {
    this.db = db;
    this.clock = clock;
    db.tx(c -> {
      statement(
          c,
          "CREATE TABLE IF NOT EXISTS repo_groups(integrator TEXT PRIMARY KEY,updated INTEGER NOT"
              + " NULL,actor TEXT NOT NULL)");
      statement(
          c,
          "CREATE TABLE IF NOT EXISTS repo_group_members(integrator TEXT NOT NULL REFERENCES"
              + " repo_groups(integrator) ON DELETE CASCADE,repo TEXT NOT NULL,role TEXT NOT"
              + " NULL,PRIMARY KEY(integrator,repo))");
      return null;
    });
  }

  /** A repository as {@code owner/name}; the same rule as every other repository entry point. */
  public static String repo(String repo) {
    if (repo == null || !repo.matches("[A-Za-z0-9_.-]{1,100}/[A-Za-z0-9_.-]{1,100}"))
      throw new IllegalArgumentException("Repositorio no válido: usa propietario/nombre");
    for (String part : repo.split("/"))
      if (part.equals(".") || part.equals(".."))
        throw new IllegalArgumentException("Repositorio no válido: usa propietario/nombre");
    return repo;
  }

  private static void human(String actor) {
    if (actor.startsWith("node:") || actor.startsWith("requester:") || actor.startsWith("trigger:"))
      throw new IllegalArgumentException("Solo el propietario decide los grupos de repositorios");
  }

  private static String clean(String text) {
    String t = text == null ? "" : text.replaceAll("[\\p{Cntrl}]", " ").strip();
    return t.length() > MAX_ROLE ? t.substring(0, MAX_ROLE).strip() : t;
  }

  /** Every group, with whether the panel already knows each member (it has had a task there). */
  public List<Map<String, Object>> all() {
    return db.tx(c -> {
      var out = new ArrayList<Map<String, Object>>();
      for (var g : rows(c, "SELECT integrator FROM repo_groups ORDER BY integrator"))
        out.add(group(c, g.get("integrator").toString()));
      return out;
    });
  }

  /** The group of {@code integrator}, or null when there is none. */
  public Map<String, Object> get(String integrator) {
    String repo = repo(integrator);
    return db.tx(c ->
        one(c, "SELECT 1 FROM repo_groups WHERE integrator=?", repo) == null
            ? null
            : group(c, repo));
  }

  /** The members of the group {@code integrator} leads, as repository names; empty if none. */
  public Set<String> members(String integrator) {
    var out = new LinkedHashSet<String>();
    var g = get(integrator);
    if (g != null)
      for (var m : (List<?>) g.get("members")) out.add(((Map<?, ?>) m).get("repo").toString());
    return out;
  }

  /** Same as {@link #members(String)} inside a transaction that is already open. */
  static Set<String> membersIn(java.sql.Connection c, String integrator) throws Exception {
    var out = new LinkedHashSet<String>();
    for (var m :
        rows(c, "SELECT repo FROM repo_group_members WHERE integrator=? ORDER BY repo", integrator))
      out.add(m.get("repo").toString());
    return out;
  }

  /** Every group a repository belongs to, as a member or as integrator. */
  public List<Map<String, Object>> of(String repoName) {
    String repo = repo(repoName);
    return db.tx(c -> {
      var out = new ArrayList<Map<String, Object>>();
      for (var g :
          rows(
              c,
              "SELECT integrator FROM repo_groups WHERE integrator=? UNION SELECT integrator FROM"
                  + " repo_group_members WHERE repo=? ORDER BY 1",
              repo,
              repo)) out.add(group(c, g.get("integrator").toString()));
      return out;
    });
  }

  private Map<String, Object> group(java.sql.Connection c, String integrator) throws Exception {
    var out = new LinkedHashMap<String, Object>();
    out.put("integrator", integrator);
    out.put("integrator_known", known(c, integrator));
    var members = new ArrayList<Map<String, Object>>();
    for (var m :
        rows(
            c,
            "SELECT repo,role FROM repo_group_members WHERE integrator=? ORDER BY repo",
            integrator)) {
      var member = new LinkedHashMap<String, Object>();
      member.put("repo", m.get("repo"));
      member.put("role", m.get("role"));
      member.put("known", known(c, m.get("repo").toString()));
      members.add(member);
    }
    out.put("members", members);
    return out;
  }

  private static boolean known(java.sql.Connection c, String repo) throws Exception {
    return one(c, "SELECT 1 FROM work_items WHERE repo=? LIMIT 1", repo) != null;
  }

  /** Defines the group of {@code integrator}: this list is the whole group, never a patch. */
  public Map<String, Object> define(String integratorName, JsonNode members, String actor) {
    human(actor);
    String integrator = repo(integratorName);
    if (!members.isArray() || members.isEmpty())
      throw new IllegalArgumentException("Un grupo necesita al menos un repositorio miembro");
    if (members.size() > MAX_MEMBERS)
      throw new IllegalArgumentException("Máximo " + MAX_MEMBERS + " repositorios por grupo");
    var seen = new LinkedHashMap<String, String>();
    for (var m : members) {
      String repo = repo(m.path("repo").asText(""));
      if (repo.equalsIgnoreCase(integrator))
        throw new IllegalArgumentException("El repositorio integrador no es miembro de su grupo");
      for (String other : seen.keySet())
        if (other.equalsIgnoreCase(repo))
          throw new IllegalArgumentException("Repositorio repetido: " + repo);
      seen.put(repo, clean(m.path("role").asText("")));
    }
    return db.tx(c -> {
      exec(
          c,
          "INSERT INTO repo_groups(integrator,updated,actor) VALUES(?,?,?) ON CONFLICT(integrator)"
              + " DO UPDATE SET updated=excluded.updated,actor=excluded.actor",
          integrator,
          clock.instant().getEpochSecond(),
          actor);
      exec(c, "DELETE FROM repo_group_members WHERE integrator=?", integrator);
      for (var e : seen.entrySet())
        exec(
            c,
            "INSERT INTO repo_group_members(integrator,repo,role) VALUES(?,?,?)",
            integrator,
            e.getKey(),
            e.getValue());
      audit(c, actor, "repo-group.define", integrator + " <- " + String.join(",", seen.keySet()));
      return group(c, integrator);
    });
  }

  public void delete(String integratorName, String actor) {
    human(actor);
    String integrator = repo(integratorName);
    db.tx(c -> {
      exec(c, "DELETE FROM repo_group_members WHERE integrator=?", integrator);
      exec(c, "DELETE FROM repo_groups WHERE integrator=?", integrator);
      audit(c, actor, "repo-group.delete", integrator);
      return null;
    });
  }
}
