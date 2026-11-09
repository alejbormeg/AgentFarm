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
    db.tx(c -> {
      statement(
          c,
          "CREATE TABLE IF NOT EXISTS node_credentials(id TEXT PRIMARY KEY,node TEXT NOT"
              + " NULL,account TEXT NOT NULL,hash TEXT NOT NULL UNIQUE,created INTEGER NOT"
              + " NULL,revoked INTEGER NOT NULL DEFAULT 0)");
      return null;
    });
  }

  public Object issue(String node, String account, String actor) {
    return issue(node, account, null, actor);
  }

  /** {@code apiAccount}: optional Clover API (USD) account for metered providers. */
  public Object issue(String node, String account, String apiAccount, String actor) {
    ControlService.nodeIdentifier(node);
    ControlService.identifier(account);
    if (apiAccount != null) ControlService.identifier(apiAccount);
    byte[] bytes = new byte[32];
    new java.security.SecureRandom().nextBytes(bytes);
    String token = "afn_" + Base64.getUrlEncoder().withoutPadding().encodeToString(bytes),
        id = UUID.randomUUID().toString();
    return db.tx(c -> {
      if (one(c, "SELECT id FROM farm_nodes WHERE id=?", node) == null
          || one(c, "SELECT account FROM budget_accounts WHERE account=?", account) == null)
        throw new IllegalArgumentException(
            "Register the node and budget account before enrollment");
      if (apiAccount != null
          && one(c, "SELECT 1 FROM budget_accounts WHERE account=? AND mode='api'", apiAccount)
              == null)
        throw new IllegalArgumentException("The metered account must be a Clover api account");
      exec(
          c,
          "INSERT INTO node_credentials(id,node,account,hash,created,revoked,api_account)"
              + " VALUES(?,?,?,?,?,0,?)",
          id,
          node,
          account,
          sha256(token),
          clock.instant().getEpochSecond(),
          apiAccount);
      audit(c, actor, "node.credential.issue", id + " -> " + node);
      return obj(
          "id",
          id,
          "node",
          node,
          "account",
          account,
          "api_account",
          apiAccount == null ? "" : apiAccount,
          "token",
          token,
          "capabilities",
          List.of("execute", "observe"),
          "integration",
          false);
    });
  }

  /**
   * The live credential, with {@code enabled} from its node: revoked or unknown tokens give null.
   */
  public Map<String, Object> authenticate(String token) {
    return db.tx(c ->
        one(
            c,
            "SELECT k.id,k.node,k.account,COALESCE(k.api_account,'') api_account,"
                + "COALESCE(n.enabled,0) enabled FROM node_credentials k LEFT JOIN farm_nodes n"
                + " ON n.id=k.node WHERE k.hash=? AND k.revoked=0",
            sha256(token)));
  }

  /**
   * What a disabled node may still do with a valid token: read, report its signal and close what it
   * already holds (reports on a held order, settling reservations). It can't take new work, new
   * GitHub tokens, provider admission or new budget. Revoking the token cuts everything.
   */
  public static boolean allowedWhileDisabled(String method, String path) {
    if (method.equals("GET")) return true;
    if (!method.equals("POST")) return false;
    if (path.startsWith("/api/work/agent/commands/")) return !path.endsWith("/github-token");
    return path.startsWith("/api/heartbeat/")
        || Set.of(
                "/api/runtime/observations",
                "/api/budget/observe",
                "/api/budget/settle-observed",
                "/api/budget/settle")
            .contains(path);
  }

  public Object list() {
    return db.tx(c ->
        rows(
            c,
            "SELECT id,node,account,api_account,created,revoked FROM node_credentials ORDER BY created"
                + " DESC"));
  }

  public void revoke(String id, String actor) {
    db.tx(c -> {
      if (exec(c, "UPDATE node_credentials SET revoked=1 WHERE id=?", id) != 1)
        throw new IllegalArgumentException("Unknown credential");
      audit(c, actor, "node.credential.revoke", id);
      return null;
    });
  }

  public void account(String node, String account) {
    boolean match =
        db.tx(c ->
            one(
                    c,
                    "SELECT 1 FROM node_credentials WHERE node=? AND account=? AND revoked=0",
                    node,
                    account)
                != null);
    if (!match) throw new IllegalArgumentException("Account not granted to this executor");
  }
}
