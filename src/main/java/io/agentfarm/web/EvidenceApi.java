package io.agentfarm.web;

import com.fasterxml.jackson.databind.JsonNode;
import io.agentfarm.core.*;
import java.security.Principal;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/evidence")
public class EvidenceApi {
  private final EvidenceService evidence;

  public EvidenceApi(EvidenceService evidence) {
    this.evidence = evidence;
  }

  @GetMapping
  public Object inbox() {
    return evidence.inbox();
  }

  @GetMapping("/{id}")
  public ResponseEntity<Object> dossier(@PathVariable String id) {
    return ResponseEntity.ok()
        .header("Content-Disposition", "attachment; filename=\"agentfarm-dossier.json\"")
        .body(evidence.get(id));
  }

  @PostMapping("/{id}/decisions")
  public Object decide(@PathVariable String id, @RequestBody JsonNode n, Principal p) {
    return evidence.decide(id, n, p.getName());
  }

  @PostMapping("/{id}/import-scope")
  public Object importScope(@PathVariable String id, @RequestBody JsonNode n, Principal p) {
    return evidence.importLegacy(id, n, p.getName());
  }

  @ExceptionHandler(IllegalArgumentException.class)
  @ResponseStatus(HttpStatus.CONFLICT)
  public Object invalid(IllegalArgumentException e) {
    return java.util.Map.of("error", e.getMessage());
  }
}
