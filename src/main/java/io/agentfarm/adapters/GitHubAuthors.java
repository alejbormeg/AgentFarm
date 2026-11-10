package io.agentfarm.adapters;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.Set;

/**
 * Who wrote something on GitHub, read from its {@code author_association}. People with a role in
 * the repository can steer the agents; on a public repository anyone else can still open issues and
 * review pull requests, and that text is not an order.
 */
public final class GitHubAuthors {
  static final Set<String> TRUSTED = Set.of("OWNER", "MEMBER", "COLLABORATOR");

  private GitHubAuthors() {}

  /** True when the issue, review or comment comes from someone with a role in the repository. */
  public static boolean trusted(JsonNode written) {
    return TRUSTED.contains(written.path("author_association").asText());
  }

  /** "autor: login", and when they have no role, says so with GitHub's own word for it. */
  public static String describe(JsonNode written) {
    String login = written.path("user").path("login").asText("desconocido");
    return trusted(written)
        ? "autor: " + login
        : "autor: "
            + login
            + ", sin permisos en el repositorio ("
            + written.path("author_association").asText("NONE")
            + ")";
  }
}
