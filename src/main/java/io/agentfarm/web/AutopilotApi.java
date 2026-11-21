package io.agentfarm.web;

import com.fasterxml.jackson.databind.JsonNode;
import io.agentfarm.adapters.Autopilot;
import java.security.Principal;
import java.util.Map;
import org.springframework.web.bind.annotation.*;

/** The owner's choices for running on their own until the PR; reading is open to every person. */
@RestController
@RequestMapping("/api/autopilot")
public class AutopilotApi {
  private final Autopilot autopilot;

  public AutopilotApi(Autopilot autopilot) {
    this.autopilot = autopilot;
  }

  /** Tasks running on their own, with the correction rounds each has used. */
  @GetMapping("/tasks")
  public Object tasks() {
    return autopilot.marked();
  }

  @GetMapping("/repos")
  public Object all() {
    return autopilot.all();
  }

  @GetMapping("/repos/{owner}/{name}")
  public Object get(@PathVariable String owner, @PathVariable String name) {
    return autopilot.settings(owner + "/" + name);
  }

  @PostMapping("/repos/{owner}/{name}")
  public Object configure(
      @PathVariable String owner,
      @PathVariable String name,
      @RequestBody JsonNode body,
      Principal p) {
    return autopilot.configure(
        owner + "/" + name,
        body.path("enabled").asBoolean(false),
        body.path("max_rounds").asInt(Autopilot.DEFAULT_ROUNDS),
        body.path("daily_cap").asInt(Autopilot.DEFAULT_DAILY),
        p.getName());
  }

  @ExceptionHandler(IllegalArgumentException.class)
  @ResponseStatus(org.springframework.http.HttpStatus.BAD_REQUEST)
  public Object invalid(IllegalArgumentException e) {
    return Map.of("error", e.getMessage());
  }
}
