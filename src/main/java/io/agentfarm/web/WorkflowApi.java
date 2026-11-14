package io.agentfarm.web;

import com.fasterxml.jackson.databind.JsonNode;
import io.agentfarm.core.*;
import java.security.Principal;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/work")
public class WorkflowApi {
  private final WorkflowService work;
  private final IntegrationPublisher publisher;
  private final GitHubReader github;
  private final InsightsService insights;
  private final DigestService digest;

  private final io.agentfarm.core.TwoFactor twoFactor;
  private final io.agentfarm.core.GitHubCredentials credentials;
  private final io.agentfarm.adapters.AgentProfiles profiles;
  private final io.agentfarm.adapters.NodeProviders nodeProviders;

  public WorkflowApi(
      WorkflowService work,
      IntegrationPublisher publisher,
      GitHubReader github,
      InsightsService insights,
      DigestService digest,
      io.agentfarm.core.TwoFactor twoFactor,
      io.agentfarm.core.GitHubCredentials credentials,
      io.agentfarm.adapters.AgentProfiles profiles,
      io.agentfarm.adapters.NodeProviders nodeProviders) {
    this.nodeProviders = nodeProviders;
    this.profiles = profiles;
    this.twoFactor = twoFactor;
    this.credentials = credentials;
    this.work = work;
    this.publisher = publisher;
    this.github = github;
    this.insights = insights;
    this.digest = digest;
  }

  @GetMapping("/costs")
  public Object costs() {
    return insights.costs();
  }

  @GetMapping("/metrics")
  public Object metrics(@RequestParam(defaultValue = "8") int weeks) {
    return insights.metrics(weeks);
  }

  /** Files changed by the candidate under review (base...sha), read from GitHub. */
  @GetMapping("/{id}/diff")
  public Object diff(@PathVariable String id) throws Exception {
    var item = Json.MAPPER.valueToTree(work.get(id));
    String sha = item.path("sha").asText(),
        base = Json.parse(item.path("proposal").asText("{}")).path("base_sha").asText();
    if (!sha.matches("[a-f0-9]{40}") || !base.matches("[a-f0-9]{40}"))
      throw new IllegalArgumentException("No candidate to compare yet");
    if (!github.configured())
      throw new IllegalArgumentException("Configure AGENTFARM_GITHUB_TOKEN_FILE to view diffs");
    var compare = github.get(item.path("repo").asText(), "/compare/" + base + "..." + sha);
    var files = new java.util.ArrayList<Object>();
    for (var f : compare.path("files")) {
      if (files.size() >= 100) break;
      String patch = f.path("patch").asText("");
      files.add(
          Json.obj(
              "file", f.path("filename").asText(),
              "status", f.path("status").asText(),
              "additions", f.path("additions").asInt(),
              "deletions", f.path("deletions").asInt(),
              "patch", patch.length() > 20000 ? patch.substring(0, 20000) + "\n…" : patch));
    }
    return Json.obj(
        "base", base, "sha", sha, "files", files, "truncated", compare.path("files").size() > 100);
  }

  private void checkAgent(JsonNode n, Principal p) {
    // A node may run several executor slots, claiming as <node>-s<N>.
    if (p.getName().startsWith("node:")
        && !Json.text(n, "agent")
            .matches(java.util.regex.Pattern.quote(p.getName().substring(5)) + "(-s[0-9]{1,2})?"))
      throw new org.springframework.security.access.AccessDeniedException(
          "Executor identity mismatch");
  }

  @PostMapping("/import-candidate")
  public Object importCandidate(@RequestBody JsonNode n, Principal p) {
    return work.importCandidate(n, p.getName());
  }

  /** Summaries only; {@code GET /api/work/{id}} has the proposal, evidence and request. */
  @GetMapping
  public Object list(
      @RequestParam(defaultValue = "0") int limit, @RequestParam(defaultValue = "0") int offset) {
    return work.summaries(limit, offset);
  }

  @GetMapping("/{id}")
  public Object detail(@PathVariable String id) {
    return work.get(id);
  }

  @PostMapping
  public Object create(@RequestBody JsonNode n, Principal p) {
    return work.create(n, p.getName());
  }

  @GetMapping("/digest")
  public Object digest() {
    return digest.digest();
  }

  @PostMapping("/batch/merge")
  public Object approveBatch(@RequestBody JsonNode n, Principal p) {
    twoFactor.checkIntegration(p.getName());
    if (!publisher.configured())
      throw new IllegalArgumentException(
          "Falta configurar el publicador de integración del panel; sin él no se puede fusionar.");
    return digest.approve(n.path("items"), p.getName());
  }

  @PostMapping("/{id}/actions/{action}")
  public Object action(
      @PathVariable String id, @PathVariable String action, @RequestBody JsonNode n, Principal p) {
    if (action.equals("merge")) twoFactor.checkIntegration(p.getName());
    if (action.equals("merge") && !publisher.configured())
      throw new IllegalArgumentException(
          "Falta configurar el publicador de integración del panel; sin él no se puede fusionar.");
    return work.action(id, action, n, p.getName());
  }

  @PostMapping("/agent/claim")
  public Object claim(@RequestBody JsonNode n, Principal p) {
    checkAgent(n, p);
    var claimed = work.claim(Json.text(n, "agent"), !p.getName().startsWith("node:"));
    // The owner's per-repository instructions travel with the order, never inside the repository.
    if (claimed instanceof java.util.Map<?, ?> m
        && m.get("item") instanceof java.util.Map<?, ?> item) {
      var withProfile = new java.util.LinkedHashMap<Object, Object>(m);
      var copy = new java.util.LinkedHashMap<Object, Object>(item);
      try {
        copy.put("agent_profile", profiles.stored(String.valueOf(item.get("repo"))));
      } catch (IllegalArgumentException e) {
        // An old row with an odd repository name simply has no profile.
      }
      if (p.getName().startsWith("node:"))
        try {
          copy.put("node_providers", nodeProviders.roles(p.getName().substring(5)));
        } catch (IllegalArgumentException e) {
          // A node name the panel does not accept has no choice recorded.
        }
      withProfile.put("item", copy);
      return withProfile;
    }
    return claimed;
  }

  @PostMapping("/agent/commands/{id}/github-token")
  public Object githubToken(@PathVariable String id, @RequestBody JsonNode n, Principal p)
      throws Exception {
    if (!p.getName().startsWith("node:"))
      throw new org.springframework.security.access.AccessDeniedException("Only an executor");
    checkAgent(n, p);
    var order = work.heldRepository(id, Json.text(n, "agent"));
    String repository = order.get("repo").toString();
    try {
      var token = credentials.repositoryToken(repository, order.get("kind").toString());
      return token == null ? Json.obj("mode", credentials.mode()) : token;
    } catch (java.io.IOException | IllegalStateException e) {
      throw new IllegalArgumentException(
          "GitHub no ha dado un token para " + repository + ": " + e.getMessage());
    }
  }

  @PostMapping("/agent/commands/{id}")
  public Object report(@PathVariable String id, @RequestBody JsonNode n, Principal p) {
    checkAgent(n, p);
    return work.report(id, n, Json.text(n, "agent"));
  }
}
