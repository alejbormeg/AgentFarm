package io.agentfarm.web;

import com.fasterxml.jackson.databind.JsonNode;
import io.agentfarm.adapters.SkillCatalogSync;
import io.agentfarm.adapters.SkillIndex;
import io.agentfarm.adapters.SkillTopics;
import java.security.Principal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.web.bind.annotation.*;

/** Owner only (SecurityConfig): the catalogue of skills, its sources and its synchronisation. */
@RestController
@RequestMapping("/api/skill-catalog")
public class SkillCatalogApi {
  private final SkillIndex catalog;
  private final SkillCatalogSync sync;

  public SkillCatalogApi(SkillIndex catalog, SkillCatalogSync sync) {
    this.catalog = catalog;
    this.sync = sync;
  }

  @GetMapping("/search")
  public Object search(
      @RequestParam(defaultValue = "") String q,
      @RequestParam(required = false) Long source,
      @RequestParam(defaultValue = "20") int limit) {
    var result = catalog.search(q, source, limit);
    return Map.of(
        "understood", result.understood(), "results", result.results(), "size", catalog.size());
  }

  @GetMapping("/topics")
  public Object topics() {
    return SkillTopics.catalog();
  }

  @GetMapping("/sources")
  public Object sources() {
    var running = sync.running();
    var out = new ArrayList<Map<String, Object>>();
    for (var s : catalog.sources()) {
      var row = new LinkedHashMap<String, Object>(s);
      row.put("syncing", running.contains(((Number) s.get("id")).longValue()));
      out.add(row);
    }
    return Map.of("sources", out, "github_configured", sync.configured(), "size", catalog.size());
  }

  @PostMapping("/sources")
  public Object add(@RequestBody JsonNode n, Principal p) {
    var added =
        catalog.addSource(n.path("repo").asText(""), n.path("path").asText(""), p.getName());
    sync.start(((Number) added.get("id")).longValue());
    return added;
  }

  @PostMapping("/sources/{id}/remove")
  public Object remove(@PathVariable long id, Principal p) {
    catalog.removeSource(id, p.getName());
    return Map.of("state", "removed");
  }

  @PostMapping("/sync")
  public Object syncNow() {
    sync.startAll();
    return Map.of("state", "started");
  }

  @ExceptionHandler(IllegalArgumentException.class)
  @ResponseStatus(org.springframework.http.HttpStatus.BAD_REQUEST)
  public Object invalid(IllegalArgumentException e) {
    return Map.of("error", e.getMessage());
  }
}
