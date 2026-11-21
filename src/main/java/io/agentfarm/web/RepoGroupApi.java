package io.agentfarm.web;

import com.fasterxml.jackson.databind.JsonNode;
import io.agentfarm.adapters.RepoGroups;
import java.security.Principal;
import java.util.Map;
import org.springframework.web.bind.annotation.*;

/** Groups of repositories that take part in one change. Reading is open to every person. */
@RestController
@RequestMapping("/api/repo-groups")
public class RepoGroupApi {
  private final RepoGroups groups;

  public RepoGroupApi(RepoGroups groups) {
    this.groups = groups;
  }

  @GetMapping
  public Object all() {
    return Map.of("groups", groups.all());
  }

  /**
   * The owner replaces the whole member list of the group the repository leads (POST: the panel's
   * client only sends POST with its CSRF token).
   */
  @PostMapping("/{owner}/{name}")
  public Object define(
      @PathVariable String owner,
      @PathVariable String name,
      @RequestBody JsonNode body,
      Principal p) {
    return groups.define(owner + "/" + name, body.path("members"), p.getName());
  }

  @PostMapping("/{owner}/{name}/delete")
  public Object delete(@PathVariable String owner, @PathVariable String name, Principal p) {
    groups.delete(owner + "/" + name, p.getName());
    return Map.of("deleted", owner + "/" + name);
  }

  @ExceptionHandler(IllegalArgumentException.class)
  @ResponseStatus(org.springframework.http.HttpStatus.BAD_REQUEST)
  public Object invalid(IllegalArgumentException e) {
    return Map.of("error", e.getMessage());
  }
}
