package io.agentfarm.adapters;

import static io.agentfarm.core.Store.*;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.agentfarm.core.Store;
import java.time.Clock;
import org.springframework.stereotype.Service;

/** The owner's per-repository agent profile, kept in the panel (never in the repository itself). */
@Service
public class AgentProfiles {
  private static final ObjectMapper MAPPER = new ObjectMapper();
  private final Store db;
  private final Clock clock;

  public AgentProfiles(Store db, Clock clock) {
    this.db = db;
    this.clock = clock;
    db.tx(c -> {
      statement(
          c,
          "CREATE TABLE IF NOT EXISTS agent_profiles(repo TEXT PRIMARY KEY,payload TEXT NOT"
              + " NULL,updated INTEGER NOT NULL,actor TEXT NOT NULL)");
      return null;
    });
  }

  static String repo(String repo) {
    if (repo == null || !repo.matches("[A-Za-z0-9_.-]{1,100}/[A-Za-z0-9_.-]{1,100}"))
      throw new IllegalArgumentException("Repositorio no válido: usa propietario/nombre");
    return repo;
  }

  /** What an executor receives with a claim; empty when the owner configured nothing. */
  public JsonNode stored(String repo) {
    return db.tx(c -> {
      var row = one(c, "SELECT payload FROM agent_profiles WHERE repo=?", repo(repo));
      return row == null
          ? MAPPER.createObjectNode()
          : MAPPER.readTree(row.get("payload").toString());
    });
  }

  public JsonNode forEditing(String repo) {
    return ProfilePrompt.forEditing(stored(repo));
  }

  public JsonNode save(String repo, JsonNode input, String actor) {
    if (actor.startsWith("node:"))
      throw new IllegalArgumentException("Solo el propietario configura a los agentes");
    var clean = ProfilePrompt.sanitize(input);
    db.tx(c -> {
      exec(
          c,
          "INSERT INTO agent_profiles(repo,payload,updated,actor) VALUES(?,?,?,?) ON CONFLICT(repo)"
              + " DO UPDATE SET payload=excluded.payload,updated=excluded.updated,actor=excluded.actor",
          repo(repo),
          clean.toString(),
          clock.instant().getEpochSecond(),
          actor);
      audit(c, actor, "agent-profile.save", repo);
      return null;
    });
    return ProfilePrompt.forEditing(clean);
  }

  public JsonNode reset(String repo, String actor) {
    if (actor.startsWith("node:"))
      throw new IllegalArgumentException("Solo el propietario configura a los agentes");
    db.tx(c -> {
      exec(c, "DELETE FROM agent_profiles WHERE repo=?", repo(repo));
      audit(c, actor, "agent-profile.reset", repo);
      return null;
    });
    return forEditing(repo);
  }
}
