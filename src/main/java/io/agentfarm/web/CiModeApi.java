package io.agentfarm.web;

import com.fasterxml.jackson.databind.JsonNode;
import io.agentfarm.adapters.CiModes;
import java.security.Principal;
import java.util.Map;
import org.springframework.web.bind.annotation.*;

/** Where a repository's CI runs. Writes fall under the owner-only rule for /api/**. */
@RestController
@RequestMapping("/api/repos/{owner}/{name}/ci")
public class CiModeApi {
  private final CiModes modes;

  public CiModeApi(CiModes modes) {
    this.modes = modes;
  }

  @GetMapping
  public Object get(@PathVariable String owner, @PathVariable String name) {
    return modes.describe(owner + "/" + name);
  }

  @PostMapping
  public Object set(
      @PathVariable String owner,
      @PathVariable String name,
      @RequestBody JsonNode body,
      Principal p) {
    if (!body.path("local").isBoolean())
      throw new IllegalArgumentException("Indica local: true o false.");
    return modes.set(owner + "/" + name, body.path("local").asBoolean(), p.getName());
  }

  @ExceptionHandler(IllegalArgumentException.class)
  @ResponseStatus(org.springframework.http.HttpStatus.CONFLICT)
  public Object invalid(IllegalArgumentException e) {
    return Map.of("error", e.getMessage());
  }
}
