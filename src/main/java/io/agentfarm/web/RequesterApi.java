package io.agentfarm.web;

import io.agentfarm.adapters.RequesterCredentials;
import java.security.Principal;
import org.springframework.web.bind.annotation.*;

/**
 * The owner sees and revokes the credentials of MCP clients. They are created from the terminal
 * with {@code ./agentfarm mcp-token}, which writes the token to a file: it never reaches a screen.
 */
@RestController
@RequestMapping("/api/requesters")
public class RequesterApi {
  private final RequesterCredentials requesters;

  public RequesterApi(RequesterCredentials requesters) {
    this.requesters = requesters;
  }

  @GetMapping
  public Object list() {
    return requesters.list();
  }

  @PostMapping("/{id}/revoke")
  public Object revoke(@PathVariable String id, Principal p) {
    return requesters.revoke(id, p.getName());
  }
}
