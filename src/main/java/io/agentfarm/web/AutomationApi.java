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
  private final SentryReader sentry;

  public AutomationApi(
      TriggerService triggers, WorkflowService work, GitHubReader github, SentryReader sentry) {
    this.triggers = triggers;
    this.work = work;
    this.github = github;
    this.sentry = sentry;
  }

  @GetMapping
  public Object state() {
    return Map.of(
        "triggers",
        triggers.policies(),
        "autonomy",
        work.autonomy(),
        "github_configured",
        github.configured(),
        "sentry_configured",
        sentry.configured());
  }

  @PostMapping("/triggers")
  public Object configure(@RequestBody JsonNode n, Principal p) {
    return triggers.configure(n, p.getName());
  }

  @PostMapping("/triggers/run")
  public Object run() {
    if (!github.configured() && !sentry.configured())
      throw new IllegalArgumentException("Configure GitHub or Sentry credentials first");
    return Map.of("touched", triggers.tick(github::get, sentry::get));
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
