package io.agentfarm.web;

import com.fasterxml.jackson.databind.JsonNode;
import io.agentfarm.core.*;
import java.security.Principal;
import java.util.Map;
import org.springframework.web.bind.annotation.*;

/** Owner-only automation: triggers that open proposals and per-recipe autonomy levels. */
@RestController
@RequestMapping("/api/automation")
public class AutomationApi {
  private final TriggerService triggers;
  private final WorkflowService work;
  private final GitHubReader github;

  public AutomationApi(TriggerService triggers, WorkflowService work, GitHubReader github) {
    this.triggers = triggers;
    this.work = work;
    this.github = github;
  }

  @GetMapping
  public Object state() {
    return Map.of(
        "triggers",
        triggers.policies(),
        "autonomy",
        work.autonomy(),
        "github_configured",
        github.configured());
  }

  @PostMapping("/triggers")
  public Object configure(@RequestBody JsonNode n, Principal p) {
    return triggers.configure(n, p.getName());
  }

  @PostMapping("/triggers/run")
  public Object run() {
    if (!github.configured())
      throw new IllegalArgumentException("Configure AGENTFARM_GITHUB_TOKEN_FILE first");
    return Map.of("touched", triggers.tick(github::get));
  }

  @PostMapping("/autonomy")
  public Object autonomy(@RequestBody JsonNode n, Principal p) {
    return work.setAutonomy(Json.text(n, "recipe"), Json.text(n, "level"), p.getName());
  }

  @ExceptionHandler(IllegalArgumentException.class)
  @ResponseStatus(org.springframework.http.HttpStatus.BAD_REQUEST)
  public Object invalid(IllegalArgumentException e) {
    return Map.of("error", e.getMessage());
  }
}
