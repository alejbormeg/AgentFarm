package io.agentfarm.adapters;

import static io.agentfarm.core.Json.sha256;
import static io.agentfarm.core.Store.*;

import io.agentfarm.core.ControlService;
import io.agentfarm.core.NodeCredentials;
import io.agentfarm.core.Store;
import java.time.Clock;
import java.util.Base64;
import java.util.Map;
import org.springframework.stereotype.Service;

/**
 * Adds an executor node from the panel: the owner asks for a code, the new machine redeems it once
 * for the node credential. Only the code's hash is stored, it expires in minutes and works once, so
 * the command the owner copies never carries the long-lived token.
 */
@Service
public class NodeEnrollment {
  public static final String PREFIX = "afc_";
  public static final long TTL_SECONDS = 15 * 60;
  private final Store db;
  private final Clock clock;
  private final NodeCredentials credentials;

  public NodeEnrollment(Store db, Clock clock, NodeCredentials credentials) {
    this.db = db;
    this.clock = clock;
    this.credentials = credentials;
    db.tx(c -> {
      statement(
          c,
          "CREATE TABLE IF NOT EXISTS node_enrollments(hash TEXT PRIMARY KEY,node TEXT NOT"
              + " NULL,account TEXT NOT NULL,expires INTEGER NOT NULL,used INTEGER NOT NULL"
              + " DEFAULT 0,actor TEXT NOT NULL)");
      return null;
    });
  }

  /** Registers the node and its two workers if they are new, and returns a one-use code. */
  public Map<String, Object> create(String node, String account, String actor) {
    if (actor.startsWith("node:"))
      throw new IllegalArgumentException("Solo el propietario añade nodos");
    ControlService.nodeIdentifier(node);
    byte[] bytes = new byte[24];
    new java.security.SecureRandom().nextBytes(bytes);
    String code = PREFIX + Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    long now = clock.instant().getEpochSecond();
    String chosen =
        db.tx(c -> {
          String useAccount = account;
          if (useAccount == null || useAccount.isBlank()) {
            var first = one(c, "SELECT account FROM budget_accounts ORDER BY account LIMIT 1");
            if (first == null)
              throw new IllegalArgumentException(
                  "Configura primero una cuenta de cuota en Presupuesto.");
            useAccount = first.get("account").toString();
          }
          ControlService.identifier(useAccount);
          if (one(c, "SELECT 1 FROM budget_accounts WHERE account=?", useAccount) == null)
            throw new IllegalArgumentException("Esa cuenta de cuota no existe");
          exec(c, "INSERT OR IGNORE INTO farm_nodes(id,role) VALUES(?, 'napoleon')", node);
          exec(c, "INSERT OR IGNORE INTO farm_workers(id,node) VALUES(?,?)", node + "-boxer", node);
          exec(
              c,
              "INSERT OR IGNORE INTO farm_workers(id,node) VALUES(?,?)",
              node + "-reviewer",
              node);
          exec(c, "DELETE FROM node_enrollments WHERE expires<? OR used=1", now - 3600);
          exec(
              c,
              "INSERT INTO node_enrollments(hash,node,account,expires,used,actor)"
                  + " VALUES(?,?,?,?,0,?)",
              sha256(code),
              node,
              useAccount,
              now + TTL_SECONDS,
              actor);
          audit(c, actor, "node.enroll.code", node);
          return useAccount;
        });
    return Map.of("code", code, "node", node, "account", chosen, "expires_in", TTL_SECONDS);
  }

  /** Spends the code and issues the node credential, or says the code is not valid any more. */
  public Object redeem(String code) {
    if (code == null || !code.matches(PREFIX + "[A-Za-z0-9_-]{32}"))
      throw new IllegalArgumentException("Código no válido");
    long now = clock.instant().getEpochSecond();
    var row =
        db.tx(c -> {
          var found =
              one(
                  c,
                  "SELECT node,account FROM node_enrollments WHERE hash=? AND used=0 AND expires>=?",
                  sha256(code),
                  now);
          if (found == null
              || exec(c, "UPDATE node_enrollments SET used=1 WHERE hash=? AND used=0", sha256(code))
                  != 1) throw new IllegalArgumentException("Código no válido, caducado o ya usado");
          audit(
              c, "enroll:" + found.get("node"), "node.enroll.redeem", found.get("node").toString());
          return found;
        });
    return credentials.issue(row.get("node").toString(), row.get("account").toString(), "enroll");
  }
}
