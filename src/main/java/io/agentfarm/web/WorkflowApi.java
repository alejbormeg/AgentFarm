package io.agentfarm.web;

import com.fasterxml.jackson.databind.JsonNode;
import io.agentfarm.core.*;
import java.security.Principal;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/work")
public class WorkflowApi {
  private final WorkflowService work;
  private final IntegrationPublisher publisher;

  public WorkflowApi(WorkflowService work, IntegrationPublisher publisher) {
    this.work = work;
    this.publisher = publisher;
  }

  private void checkAgent(JsonNode n, Principal p) {
    if (p.getName().startsWith("node:") && !p.getName().substring(5).equals(Json.text(n, "agent")))
      throw new org.springframework.security.access.AccessDeniedException(
          "Executor identity mismatch");
  }

  @PostMapping("/import-candidate")
  public Object importCandidate(@RequestBody JsonNode n, Principal p) {
    return work.importCandidate(n, p.getName());
  }

  @GetMapping
  public Object list() {
    return work.items();
  }

  @PostMapping
  public Object create(@RequestBody JsonNode n, Principal p) {
    return work.create(n, p.getName());
  }

  @PostMapping("/{id}/actions/{action}")
  public Object action(
      @PathVariable String id, @PathVariable String action, @RequestBody JsonNode n, Principal p) {
    if (action.equals("merge") && !publisher.configured())
      throw new org.springframework.web.server.ResponseStatusException(
          org.springframework.http.HttpStatus.CONFLICT,
          "Configure the control-plane integration publisher first");
    return work.action(id, action, n, p.getName());
  }

  @PostMapping("/agent/claim")
  public Object claim(@RequestBody JsonNode n, Principal p) {
    checkAgent(n, p);
    return work.claim(Json.text(n, "agent"), !p.getName().startsWith("node:"));
  }

  @PostMapping("/agent/commands/{id}")
  public Object report(@PathVariable String id, @RequestBody JsonNode n, Principal p) {
    checkAgent(n, p);
    return work.report(id, n, Json.text(n, "agent"));
  }
}
