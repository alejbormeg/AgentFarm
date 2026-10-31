package io.agentfarm.web;

import com.fasterxml.jackson.databind.JsonNode;
import io.agentfarm.core.*;
import java.security.Principal;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/work")
public class WorkflowApi {
  private final WorkflowService work;

  public WorkflowApi(WorkflowService work) {
    this.work = work;
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
    return work.action(id, action, n, p.getName());
  }

  @PostMapping("/agent/claim")
  public Object claim(@RequestBody JsonNode n) {
    return work.claim(Json.text(n, "agent"));
  }

  @PostMapping("/agent/commands/{id}")
  public Object report(@PathVariable String id, @RequestBody JsonNode n) {
    return work.report(id, n, Json.text(n, "agent"));
  }
}
