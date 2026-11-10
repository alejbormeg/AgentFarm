package io.agentfarm.adapters;

import static io.agentfarm.core.Json.*;
import static io.agentfarm.core.Store.*;

import io.agentfarm.core.Store;
import java.time.Clock;
import java.util.*;
import org.springframework.stereotype.Service;

/**
 * Credentials for a client that only reads tasks and files requests, such as the MCP server inside
 * Claude Code. A model can read every file its client can; the owner's password never goes there.
 * The token is shown once and stored as a hash; the owner revokes it from Gobierno.
 */
@Service
public class RequesterCredentials {
  public static final String PREFIX = "afr_";
  private final Store db;
  private final Clock clock;

  public RequesterCredentials(Store db, Clock clock) {
    this.db = db;
    this.clock = clock;
    db.tx(c -> {
      statement(
          c,
          "CREATE TABLE IF NOT EXISTS requester_credentials(id TEXT PRIMARY KEY,label TEXT NOT"
              + " NULL,hash TEXT NOT NULL UNIQUE,created INTEGER NOT NULL,revoked INTEGER NOT NULL"
              + " DEFAULT 0,last_used INTEGER)");
      return null;
    });
  }

  /** A new token for {@code label}; the only time it exists outside its hash. */
  public Map<String, Object> issue(String label, String actor) {
    if (label == null || !label.matches("[a-z0-9][a-z0-9._-]{0,39}"))
      throw new IllegalArgumentException(
          "El nombre de la credencial va en minúsculas, cifras, punto, guion o guion bajo (hasta"
              + " 40).");
    byte[] bytes = new byte[32];
    new java.security.SecureRandom().nextBytes(bytes);
    String token = PREFIX + Base64.getUrlEncoder().withoutPadding().encodeToString(bytes),
        id = UUID.randomUUID().toString();
    return db.tx(c -> {
      exec(
          c,
          "INSERT INTO requester_credentials(id,label,hash,created) VALUES(?,?,?,?)",
          id,
          label,
          sha256(token),
          clock.instant().getEpochSecond());
      audit(c, actor, "requester.credential.issue", id + " " + label);
      return obj("id", id, "label", label, "token", token);
    });
  }

  /** Every credential without its hash, newest first. */
  public List<Map<String, Object>> list() {
    return db.tx(c ->
        rows(
            c,
            "SELECT id,label,created,revoked,last_used FROM requester_credentials ORDER BY"
                + " created DESC,rowid DESC"));
  }

  public Map<String, Object> revoke(String id, String actor) {
    return db.tx(c -> {
      if (exec(c, "UPDATE requester_credentials SET revoked=1 WHERE id=? AND revoked=0", id) == 0)
        throw new IllegalArgumentException("No hay ninguna credencial activa con ese id.");
      audit(c, actor, "requester.credential.revoke", id);
      return obj("id", id, "revoked", true);
    });
  }

  /** The label of a live credential, or null for a revoked or unknown token. */
  public String authenticate(String token) {
    if (token == null || !token.startsWith(PREFIX)) return null;
    long now = clock.instant().getEpochSecond();
    return db.tx(c -> {
      var row =
          one(
              c,
              "SELECT id,label,last_used FROM requester_credentials WHERE hash=? AND revoked=0",
              sha256(token));
      if (row == null) return null;
      // A use per minute is enough to tell a live credential from a forgotten one.
      if (row.get("last_used") == null || now - num(row, "last_used") >= 60)
        exec(c, "UPDATE requester_credentials SET last_used=? WHERE id=?", now, row.get("id"));
      return row.get("label").toString();
    });
  }
}
