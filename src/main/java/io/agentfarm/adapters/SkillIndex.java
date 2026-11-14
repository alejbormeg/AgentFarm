package io.agentfarm.adapters;

import static io.agentfarm.core.Store.*;

import io.agentfarm.core.Store;
import java.time.Clock;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.stereotype.Service;

/**
 * The panel's own catalogue of skills: metadata from the sources the owner follows (the official
 * repositories and any others), searchable in free text through SQLite FTS5. Only names,
 * descriptions and tags are kept; the text of a skill is fetched when the owner opens it.
 */
@Service
public class SkillIndex {
  public record Entry(
      String repo,
      String path,
      String name,
      String description,
      String tags,
      String sha,
      int bytes) {}

  public static final int MAX_SOURCES = 30;
  private final Store db;
  private final Clock clock;

  public SkillIndex(Store db, Clock clock) {
    this.db = db;
    this.clock = clock;
    db.tx(c -> {
      statement(
          c,
          "CREATE TABLE IF NOT EXISTS skill_sources(id INTEGER PRIMARY KEY AUTOINCREMENT,repo TEXT"
              + " NOT NULL,path TEXT NOT NULL,kind TEXT NOT NULL,enabled INTEGER NOT NULL DEFAULT"
              + " 1,last_sync INTEGER,last_error TEXT,skills INTEGER NOT NULL DEFAULT 0,"
              + "UNIQUE(repo,path))");
      statement(
          c,
          "CREATE TABLE IF NOT EXISTS skill_catalog(id TEXT PRIMARY KEY,source INTEGER NOT NULL,repo"
              + " TEXT NOT NULL,path TEXT NOT NULL,name TEXT NOT NULL,description TEXT NOT NULL,tags"
              + " TEXT NOT NULL,stars INTEGER NOT NULL DEFAULT 0,sha TEXT NOT NULL,bytes INTEGER NOT"
              + " NULL,updated INTEGER NOT NULL)");
      statement(
          c,
          "CREATE VIRTUAL TABLE IF NOT EXISTS skill_fts USING fts5(name,description,tags,repo,id"
              + " UNINDEXED,tokenize='unicode61 remove_diacritics 2')");
      if (one(c, "SELECT 1 FROM skill_sources LIMIT 1") == null) {
        exec(
            c,
            "INSERT INTO skill_sources(repo,path,kind) VALUES('anthropics/skills','skills','official')");
        exec(c, "INSERT INTO skill_sources(repo,path,kind) VALUES('openai/skills','','official')");
      }
      return null;
    });
  }

  public List<Map<String, Object>> sources() {
    return db.tx(c -> rows(c, "SELECT * FROM skill_sources ORDER BY kind DESC,repo,path"));
  }

  public Map<String, Object> source(long id) {
    var row = db.tx(c -> one(c, "SELECT * FROM skill_sources WHERE id=?", id));
    if (row == null) throw new IllegalArgumentException("Fuente desconocida");
    return row;
  }

  public Map<String, Object> addSource(String repo, String path, String actor) {
    if (actor.startsWith("node:"))
      throw new IllegalArgumentException("Solo el propietario añade fuentes");
    String r = SkillSources.repo(repo),
        p = path == null ? "" : SkillSources.path(path).replace("%20", " ");
    return db.tx(c -> {
      if (num(one(c, "SELECT COUNT(*) n FROM skill_sources"), "n") >= MAX_SOURCES)
        throw new IllegalArgumentException("Máximo " + MAX_SOURCES + " fuentes");
      if (one(c, "SELECT 1 FROM skill_sources WHERE repo=? AND path=?", r, p) != null)
        throw new IllegalArgumentException("Esa fuente ya está en el catálogo");
      exec(c, "INSERT INTO skill_sources(repo,path,kind) VALUES(?,?,'custom')", r, p);
      audit(c, actor, "skill-source.add", r + "/" + p);
      return one(c, "SELECT * FROM skill_sources WHERE repo=? AND path=?", r, p);
    });
  }

  public void removeSource(long id, String actor) {
    if (actor.startsWith("node:"))
      throw new IllegalArgumentException("Solo el propietario quita fuentes");
    db.tx(c -> {
      var row = one(c, "SELECT * FROM skill_sources WHERE id=?", id);
      if (row == null) throw new IllegalArgumentException("Fuente desconocida");
      if ("official".equals(row.get("kind")))
        throw new IllegalArgumentException("Las fuentes oficiales no se quitan");
      exec(
          c, "DELETE FROM skill_fts WHERE id IN (SELECT id FROM skill_catalog WHERE source=?)", id);
      exec(c, "DELETE FROM skill_catalog WHERE source=?", id);
      exec(c, "DELETE FROM skill_sources WHERE id=?", id);
      audit(c, actor, "skill-source.remove", row.get("repo") + "/" + row.get("path"));
      return null;
    });
  }

  /** What is stored for a source: path to blob SHA. */
  public Map<String, String> shas(long source) {
    return db.tx(c -> {
      var out = new HashMap<String, String>();
      for (var r : rows(c, "SELECT path,sha FROM skill_catalog WHERE source=?", source))
        out.put(r.get("path").toString(), r.get("sha").toString());
      return out;
    });
  }

  /** Stores a finished synchronisation: new and changed entries in, vanished ones out. */
  public void apply(long source, int stars, List<Entry> fresh, Set<String> seen) {
    long now = clock.instant().getEpochSecond();
    db.tx(c -> {
      for (Entry e : fresh) {
        String id = e.repo() + ":" + e.path();
        exec(c, "DELETE FROM skill_fts WHERE id=?", id);
        exec(
            c,
            "INSERT INTO skill_catalog(id,source,repo,path,name,description,tags,stars,sha,bytes,updated)"
                + " VALUES(?,?,?,?,?,?,?,?,?,?,?) ON CONFLICT(id) DO UPDATE SET"
                + " name=excluded.name,description=excluded.description,tags=excluded.tags,"
                + "sha=excluded.sha,bytes=excluded.bytes,updated=excluded.updated",
            id,
            source,
            e.repo(),
            e.path(),
            e.name(),
            e.description(),
            e.tags(),
            stars,
            e.sha(),
            e.bytes(),
            now);
        exec(
            c,
            "INSERT INTO skill_fts(name,description,tags,repo,id) VALUES(?,?,?,?,?)",
            e.name(),
            e.description(),
            e.tags(),
            e.repo(),
            id);
      }
      for (var r : rows(c, "SELECT id,path FROM skill_catalog WHERE source=?", source))
        if (!seen.contains(r.get("path").toString())) {
          exec(c, "DELETE FROM skill_fts WHERE id=?", r.get("id"));
          exec(c, "DELETE FROM skill_catalog WHERE id=?", r.get("id"));
        }
      exec(c, "UPDATE skill_catalog SET stars=? WHERE source=?", stars, source);
      exec(
          c,
          "UPDATE skill_sources SET last_sync=?,last_error=NULL,skills=(SELECT COUNT(*) FROM"
              + " skill_catalog WHERE source=?) WHERE id=?",
          now,
          source,
          source);
      return null;
    });
  }

  /** A synchronisation that failed keeps what was there and says why. */
  public void fail(long source, String error) {
    String text = SkillMeta.clean(error, 200);
    db.tx(c -> {
      exec(c, "UPDATE skill_sources SET last_error=? WHERE id=?", text, source);
      return null;
    });
  }

  public record Result(List<Map<String, String>> understood, List<Map<String, Object>> results) {}

  /**
   * Free-text search: Spanish or English, ranked by FTS5 with a nudge for official sources and
   * stars.
   */
  public Result search(String text, Long source, int limit) {
    String q = text == null ? "" : text.strip();
    if (q.length() > 200) throw new IllegalArgumentException("La búsqueda pasa de 200 caracteres");
    var tokens = SkillTopics.tokens(q);
    var topics = SkillTopics.detect(q);
    var terms = new LinkedHashSet<String>(tokens);
    terms.addAll(SkillTopics.expansion(topics));
    var understood =
        topics.stream().limit(5).map(t -> Map.of("id", t.id(), "label", t.label())).toList();
    if (terms.isEmpty()) return new Result(understood, List.of());
    // Every term is letters and digits only (SkillTopics.normalize), quoted: no FTS syntax gets in.
    var match = new ArrayList<String>();
    for (String t : terms) {
      if (match.size() >= 40) break;
      match.add("\"" + t + "\"");
    }
    var rows =
        db.tx(c ->
            rows(
                c,
                "SELECT c.*,s.kind AS kind,bm25(skill_fts,10.0,2.0,5.0,1.0,0.0) AS rank FROM skill_fts"
                    + " JOIN skill_catalog c ON c.id=skill_fts.id JOIN skill_sources s ON s.id=c.source"
                    + " WHERE skill_fts MATCH ? AND s.enabled=1"
                    + (source == null ? "" : " AND c.source=?")
                    + " ORDER BY rank LIMIT 80",
                source == null
                    ? new Object[] {String.join(" OR ", match)}
                    : new Object[] {String.join(" OR ", match), source}));
    var scored = new ArrayList<Map.Entry<Double, Map<String, Object>>>();
    for (var r : rows) {
      double score = ((Number) r.get("rank")).doubleValue();
      String name = SkillTopics.normalize(r.get("name") + " " + r.get("path"));
      String hay = SkillTopics.normalize(r.get("description") + " " + r.get("tags"));
      // A word of the request, or an English word of its topic, in the name says the most; in the
      // description, less. This keeps "security" skills ahead of a skill that merely mentions it.
      boolean inName =
          terms.stream()
              .anyMatch(t ->
                  (" " + name + " ").contains(" " + t + " ")
                      || name.contains(t) && t.length() >= 5);
      long inDescription =
          terms.stream().filter(t -> (" " + hay + " ").contains(" " + t + " ")).count();
      if (inName) score -= 6;
      score -= Math.min(1.5, 0.5 * inDescription);
      if (tokens.stream().anyMatch(t -> (" " + name + " " + hay + " ").contains(" " + t + " ")))
        score -= 1;
      boolean official = "official".equals(r.get("kind"));
      if (official) score -= 1;
      score -= 0.3 * Math.log10(1 + ((Number) r.get("stars")).doubleValue());
      var item = new LinkedHashMap<String, Object>();
      item.put("repo", r.get("repo"));
      item.put("path", r.get("path"));
      item.put("name", r.get("name"));
      item.put("description", r.get("description"));
      item.put("stars", r.get("stars"));
      item.put("official", official);
      item.put("url", "https://github.com/" + r.get("repo") + "/blob/HEAD/" + r.get("path"));
      scored.add(Map.entry(score, item));
    }
    scored.sort(Comparator.comparingDouble(Map.Entry::getKey));
    return new Result(
        understood,
        scored.stream().limit(Math.max(1, Math.min(limit, 50))).map(Map.Entry::getValue).toList());
  }

  public int size() {
    return ((Number) db.tx(c -> one(c, "SELECT COUNT(*) n FROM skill_catalog")).get("n"))
        .intValue();
  }
}
