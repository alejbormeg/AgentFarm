package io.agentfarm.web;

import com.fasterxml.jackson.databind.JsonNode;
import io.agentfarm.core.*;
import java.security.Principal;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/executors")
public class ExecutorApi {
  private final NodeCredentials credentials;

  public ExecutorApi(NodeCredentials credentials) {
    this.credentials = credentials;
  }

  @GetMapping
  public Object list() {
    return credentials.list();
  }

  @PostMapping
  public Object issue(@RequestBody JsonNode n, Principal p) {
    return credentials.issue(Json.text(n, "node"), Json.text(n, "account"), p.getName());
  }

  @PostMapping("/{id}/revoke")
  public Object revoke(@PathVariable String id, Principal p) {
    credentials.revoke(id, p.getName());
    return java.util.Map.of("state", "revoked");
  }

  @ExceptionHandler(IllegalArgumentException.class)
  @ResponseStatus(org.springframework.http.HttpStatus.BAD_REQUEST)
  public Object invalid(IllegalArgumentException e) {
    return java.util.Map.of("error", e.getMessage());
  }
}
