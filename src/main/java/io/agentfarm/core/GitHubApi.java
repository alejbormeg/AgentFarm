package io.agentfarm.core;

/** Where the panel reaches GitHub. Only the operator's environment can change it. */
final class GitHubApi {
  private GitHubApi() {}

  static String base() {
    String configured = System.getenv("AGENTFARM_GITHUB_API");
    return configured == null || configured.isBlank() ? "https://api.github.com" : configured;
  }
}
