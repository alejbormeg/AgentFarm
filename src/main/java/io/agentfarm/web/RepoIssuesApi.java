package io.agentfarm.web;

import com.fasterxml.jackson.databind.JsonNode;
import io.agentfarm.adapters.RepoIssues;
import java.security.Principal;
import java.util.ArrayList;
import java.util.Map;
import org.springframework.web.bind.annotation.*;

/** Owner only (SecurityConfig): the panel reads GitHub and opens tasks on the owner's behalf. */
@RestController
@RequestMapping("/api/repos/{owner}/{name}/issues")
public class RepoIssuesApi {
  private final RepoIssues issues;

  public RepoIssuesApi(RepoIssues issues) {
    this.issues = issues;
  }

  @GetMapping
  public Object list(@PathVariable String owner, @PathVariable String name) throws Exception {
    return Map.of("issues", issues.list(owner + "/" + name));
  }

  @PostMapping("/start")
  public Object start(
      @PathVariable String owner,
      @PathVariable String name,
      @RequestBody JsonNode body,
      Principal p)
      throws Exception {
    var numbers = new ArrayList<Integer>();
    for (var n : body.path("numbers")) numbers.add(n.asInt(0));
    return Map.of(
        "results",
        issues.start(
            owner + "/" + name,
            numbers,
            p.getName(),
            body.has("autonomous") ? body.path("autonomous").asBoolean() : null));
  }

  @ExceptionHandler({IllegalArgumentException.class, IllegalStateException.class})
  @ResponseStatus(org.springframework.http.HttpStatus.BAD_REQUEST)
  public Object invalid(RuntimeException e) {
    return Map.of("error", e.getMessage());
  }
}
