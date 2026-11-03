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

  public WorkflowApi(
      WorkflowService work,
      IntegrationPublisher publisher,
      GitHubReader github,
      InsightsService insights,
      DigestService digest,
      io.agentfarm.core.TwoFactor twoFactor) {
    this.twoFactor = twoFactor;
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
    return Json.obj("base", base, "sha", sha, "files", files, "truncated", compare.path("files").size() > 100);
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

  @GetMapping
  public Object list() {
    return work.items();
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
    return work.claim(Json.text(n, "agent"), !p.getName().startsWith("node:"));
  }

  @PostMapping("/agent/commands/{id}")
  public Object report(@PathVariable String id, @RequestBody JsonNode n, Principal p) {
    checkAgent(n, p);
    return work.report(id, n, Json.text(n, "agent"));
  }
}
