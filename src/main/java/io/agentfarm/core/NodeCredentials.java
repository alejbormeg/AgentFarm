package io.agentfarm.core;

import static io.agentfarm.core.Json.*;
import static io.agentfarm.core.Store.*;

import java.time.Clock;
import java.util.*;
import org.springframework.stereotype.Service;

@Service
public class NodeCredentials {
  private final Store db;
  private final Clock clock;

  public NodeCredentials(Store db, Clock clock) {
    this.db = db;
    this.clock = clock;
    db.tx(
        c -> {
          statement(
              c,
              "CREATE TABLE IF NOT EXISTS node_credentials(id TEXT PRIMARY KEY,node TEXT NOT"
                  + " NULL,account TEXT NOT NULL,hash TEXT NOT NULL UNIQUE,created INTEGER NOT"
                  + " NULL,revoked INTEGER NOT NULL DEFAULT 0)");
          return null;
        });
  }

  public Object issue(String node, String account, String actor) {
    ControlService.identifier(node);
    ControlService.identifier(account);
    byte[] bytes = new byte[32];
    new java.security.SecureRandom().nextBytes(bytes);
    String token = "afn_" + Base64.getUrlEncoder().withoutPadding().encodeToString(bytes),
        id = UUID.randomUUID().toString();
    return db.tx(
        c -> {
          if (one(c, "SELECT id FROM farm_nodes WHERE id=?", node) == null
              || one(c, "SELECT account FROM budget_accounts WHERE account=?", account) == null)
            throw new IllegalArgumentException(
                "Register the node and budget account before enrollment");
          exec(
              c,
              "INSERT INTO node_credentials VALUES(?,?,?,?,?,0)",
              id,
              node,
              account,
              sha256(token),
              clock.instant().getEpochSecond());
          audit(c, actor, "node.credential.issue", id + " -> " + node);
          return obj(
              "id",
              id,
              "node",
              node,
              "account",
              account,
              "token",
              token,
              "capabilities",
              List.of("execute", "observe"),
              "integration",
              false);
        });
  }

  public Map<String, Object> authenticate(String token) {
    return db.tx(
        c ->
            one(
                c,
                "SELECT id,node,account FROM node_credentials WHERE hash=? AND revoked=0",
                sha256(token)));
  }

  public Object list() {
    return db.tx(
        c ->
            rows(
                c,
                "SELECT id,node,account,created,revoked FROM node_credentials ORDER BY created"
                    + " DESC"));
  }

  public void revoke(String id, String actor) {
    db.tx(
        c -> {
          if (exec(c, "UPDATE node_credentials SET revoked=1 WHERE id=?", id) != 1)
            throw new IllegalArgumentException("Unknown credential");
          audit(c, actor, "node.credential.revoke", id);
          return null;
        });
  }

  public void account(String node, String account) {
    boolean match =
        db.tx(
            c ->
                one(
                        c,
                        "SELECT 1 FROM node_credentials WHERE node=? AND account=? AND revoked=0",
                        node,
                        account)
                    != null);
    if (!match) throw new IllegalArgumentException("Account not granted to this executor");
  }
}
