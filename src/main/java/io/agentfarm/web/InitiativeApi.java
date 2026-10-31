package io.agentfarm.web;

import com.fasterxml.jackson.databind.JsonNode;
import io.agentfarm.core.*;
import java.security.Principal;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/initiatives")
public class InitiativeApi {
  private final InitiativeService service;

  public InitiativeApi(InitiativeService service) {
    this.service = service;
  }

  @GetMapping
  public Object list() {
    return service.list();
  }

  @GetMapping("/{id}")
  public Object get(@PathVariable String id) {
    return service.get(id);
  }

  @PostMapping
  public Object create(@RequestBody JsonNode n, Principal p) {
    return service.create(n, p.getName());
  }

  @PostMapping("/{id}/tasks")
  public Object link(@PathVariable String id, @RequestBody JsonNode n, Principal p) {
    return service.link(id, n, p.getName());
  }

  @PostMapping("/{id}/pause")
  public Object pause(@PathVariable String id, @RequestBody JsonNode n, Principal p) {
    return service.pause(id, n, p.getName());
  }

  @ExceptionHandler(IllegalArgumentException.class)
  @ResponseStatus(org.springframework.http.HttpStatus.CONFLICT)
  public Object invalid(IllegalArgumentException e) {
    return java.util.Map.of("error", e.getMessage());
  }
}
