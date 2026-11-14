package io.agentfarm.core;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * The GitHub permissions an executor token gets, by the kind of order it holds. Asking for a subset
 * matters: without it GitHub gives the token everything the App has, and contents:write alone is
 * enough to merge. Reading orders get read access only.
 */
public final class TokenScope {
  private TokenScope() {}

  /** Orders that only look: research of a request and review of work that already exists. */
  public static Map<String, String> permissionsFor(String kind) {
    var p = new LinkedHashMap<String, String>();
    p.put("metadata", "read");
    p.put("contents", "read");
    p.put("pull_requests", "read");
    p.put("issues", "read");
    switch (kind) {
      case "review_existing" -> {}
      // Every order opens by making sure the task has its issue (FarmDaemon.ensureIssue), and the
      // first one, the research, is the one that creates it. Writing issues is all it adds.
      case "research" -> p.put("issues", "write");
      // Pushes its branch, opens and edits its PR and, in a repository without CI, adds the
      // verification workflow. Merging is not here as a separate right: contents:write can merge,
      // which is why the branch rules of the repository matter (docs/SECURITY.md).
      case "implement" -> {
        // It writes the result into the task's issue (FarmDaemon.publish) once the PR exists.
        p.put("issues", "write");
        p.put("contents", "write");
        p.put("pull_requests", "write");
        p.put("workflows", "write");
      }
      default -> throw new IllegalArgumentException("No GitHub token for orders of kind " + kind);
    }
    return p;
  }
}
