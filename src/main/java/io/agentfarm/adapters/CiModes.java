package io.agentfarm.adapters;

import static io.agentfarm.core.Store.*;

import io.agentfarm.core.EvidenceVerifier;
import io.agentfarm.core.GitHubCredentials;
import io.agentfarm.core.IntegrationPublisher;
import io.agentfarm.core.SchemaMigrations;
import io.agentfarm.core.Store;
import io.agentfarm.core.TriggerService;
import java.time.Clock;
import java.util.Map;
import org.springframework.stereotype.Service;

/**
 * Where a repository's CI runs (#241). By default GitHub runs it. The owner may choose local CI for
 * a repository, so the panel runs the approved tests through an executor and publishes the checks
 * with its own App. It is never switched on by itself: a slow CI must not be bypassed.
 */
@Service
public final class CiModes {
  private final Store db;
  private final Clock clock;
  private final GitHubCredentials credentials;

  public CiModes(
      Store db,
      Clock clock,
      GitHubCredentials credentials,
      SchemaMigrations schema,
      EvidenceVerifier verifier,
      IntegrationPublisher publisher,
      TriggerService triggers) {
    this.db = db;
    this.clock = clock;
    this.credentials = credentials;
    verifier.useCiPolicy(this::local, credentials::appId);
    publisher.useCiPolicy(this::local, credentials::appId);
    triggers.usePanelApp(credentials::appId);
  }

  public boolean local(String repo) {
    return db.tx(c -> one(c, "SELECT 1 FROM repo_ci_modes WHERE repo=?", repo) != null);
  }

  public Map<String, Object> describe(String repo) {
    check(repo);
    return Map.of(
        "repo", repo,
        "local", local(repo),
        "available", credentials.mode().equals("github-app"),
        "checks", CiPolicy.localChecks());
  }

  public Map<String, Object> set(String repo, boolean local, String actor) {
    check(repo);
    if (local && !credentials.mode().equals("github-app"))
      throw new IllegalArgumentException(
          "La CI local publica sus resultados con la GitHub App del panel: conéctala en Gobierno.");
    db.tx(c -> {
      if (local)
        exec(
            c,
            "INSERT OR REPLACE INTO repo_ci_modes(repo,actor,at) VALUES(?,?,?)",
            repo,
            actor,
            clock.instant().getEpochSecond());
      else exec(c, "DELETE FROM repo_ci_modes WHERE repo=?", repo);
      Store.audit(c, actor, local ? "ci.local-on" : "ci.local-off", repo);
      return null;
    });
    return describe(repo);
  }

  static void check(String repo) {
    if (!repo.matches("[A-Za-z0-9_-][A-Za-z0-9_.-]*/[A-Za-z0-9_-][A-Za-z0-9_.-]*"))
      throw new IllegalArgumentException("Repositorio no válido: " + repo);
  }
}
