package io.agentfarm.web;

import com.fasterxml.jackson.databind.JsonNode;
import io.agentfarm.adapters.LocalVerifications;
import io.agentfarm.core.Json;
import java.security.Principal;
import java.util.Map;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.web.bind.annotation.*;

/** Local CI (#241): the executor's results, and what the panel did with them. */
@RestController
@RequestMapping("/api/work")
public class LocalVerificationApi {
  private final LocalVerifications verifications;

  public LocalVerificationApi(LocalVerifications verifications) {
    this.verifications = verifications;
  }

  /** Only an executor answers its own order: not even the owner can write a verification. */
  @PostMapping("/agent/commands/{id}/verification")
  public Object finish(@PathVariable String id, @RequestBody JsonNode n, Principal p) {
    if (!p.getName().startsWith("node:")) throw new AccessDeniedException("Only an executor");
    String agent = Json.text(n, "agent");
    if (!agent.matches(java.util.regex.Pattern.quote(p.getName().substring(5)) + "(-s[0-9]{1,2})?"))
      throw new AccessDeniedException("Executor identity mismatch");
    return verifications.finish(id, agent, n);
  }

  @GetMapping("/{id}/verifications")
  public Object of(@PathVariable String id) {
    return Map.of("verifications", verifications.of(id));
  }

  @ExceptionHandler(IllegalArgumentException.class)
  @ResponseStatus(org.springframework.http.HttpStatus.CONFLICT)
  public Object invalid(IllegalArgumentException e) {
    return Map.of("error", e.getMessage());
  }
}
