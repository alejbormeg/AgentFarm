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
    return credentials.issue(
        Json.text(n, "node"),
        Json.text(n, "account"),
        n.hasNonNull("api_account") && !n.path("api_account").asText().isBlank()
            ? Json.text(n, "api_account")
            : null,
        p.getName());
  }

  /**
   * Who this node credential belongs to, so the installer can write the executor's configuration
   * without the user copying identifiers. Workers follow the names «Preparar este equipo» gives.
   */
  @GetMapping("/me")
  public Object me(jakarta.servlet.http.HttpServletRequest request, Principal p) {
    if (!p.getName().startsWith("node:"))
      throw new org.springframework.security.access.AccessDeniedException("Only an executor");
    String node = request.getAttribute("agentfarm.node").toString();
    return java.util.Map.of(
        "node",
        node,
        "account",
        request.getAttribute("agentfarm.account").toString(),
        "api_account",
        String.valueOf(request.getAttribute("agentfarm.api-account")).replace("null", ""),
        "implementer",
        node + "-boxer",
        "reviewer",
        node + "-reviewer");
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
