package io.agentfarm.core;

import static io.agentfarm.core.Json.obj;

import java.util.*;

/**
 * Tries the reads integration depends on, so a missing permission shows up before a task needs it.
 */
public final class GitHubAccess {
  private static final Map<String, String> READS =
      Map.of(
          "pull_requests", "/pulls?per_page=1",
          "check_runs", "/commits/HEAD/check-runs?per_page=1",
          "statuses", "/commits/HEAD/status");

  private GitHubAccess() {}

  /**
   * What GitHub says this credential may do, without writing anything. It is not proof that a merge
   * will work: branch protection can still refuse it. Not being told is no failure, only unknown.
   */
  private static Map<String, Object> writeAccess(String repo, GitHubReader.Source github) {
    try {
      var push = github.get(repo, "").path("permissions").path("push");
      if (push.isMissingNode())
        return obj(
            "id",
            "write",
            "state",
            "unknown",
            "detail",
            "GitHub no informa de los permisos de esta credencial.");
      return push.asBoolean()
          ? obj("id", "write", "state", "passed")
          : obj(
              "id",
              "write",
              "state",
              "failed",
              "detail",
              "Sin permiso de escritura: el panel no podrá integrar.");
    } catch (Exception e) {
      return obj("id", "write", "state", "failed", "detail", e.getMessage());
    }
  }

  public static Map<String, Object> check(String kind, String repo, GitHubReader.Source github) {
    var checks = new ArrayList<Map<String, Object>>();
    boolean passed = true;
    for (var read : new TreeMap<>(READS).entrySet()) {
      try {
        github.get(repo, read.getValue());
        checks.add(obj("id", read.getKey(), "state", "passed"));
      } catch (Exception e) {
        passed = false;
        checks.add(obj("id", read.getKey(), "state", "failed", "detail", e.getMessage()));
      }
    }
    var write = writeAccess(repo, github);
    checks.add(write);
    passed &= !write.get("state").equals("failed");
    var result =
        obj(
            "state",
            passed ? "passed" : "failed",
            "credential",
            kind,
            "repository",
            repo,
            "checks",
            checks);
    if (kind.equals("fine-grained-token"))
      result.put(
          "advice",
          "Los tokens de grano fino no pueden leer check runs (checks=read), y el panel no podrá"
              + " integrar. Usa una GitHub App: docs/TEAM.md.");
    else if (kind.equals("classic-token"))
      result.put(
          "advice",
          "Este token da acceso a todos tus repositorios. Es preferible una GitHub App: docs/TEAM.md.");
    return result;
  }
}
