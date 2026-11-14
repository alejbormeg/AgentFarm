package io.agentfarm.web;

import com.fasterxml.jackson.databind.JsonNode;
import io.agentfarm.adapters.AgentProfiles;
import io.agentfarm.adapters.ProfilePrompt;
import java.security.Principal;
import java.util.Map;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/repos/{owner}/{name}/agents")
public class AgentProfileApi {
  private final AgentProfiles profiles;

  public AgentProfileApi(AgentProfiles profiles) {
    this.profiles = profiles;
  }

  @GetMapping
  public Object get(@PathVariable String owner, @PathVariable String name) {
    return profiles.forEditing(owner + "/" + name);
  }

  @PostMapping
  public Object save(
      @PathVariable String owner,
      @PathVariable String name,
      @RequestBody JsonNode body,
      Principal p) {
    return profiles.save(owner + "/" + name, body, p.getName());
  }

  @PostMapping("/reset")
  public Object reset(@PathVariable String owner, @PathVariable String name, Principal p) {
    return profiles.reset(owner + "/" + name, p.getName());
  }

  /**
   * The profile as the file Claude (CLAUDE.md), ChatGPT/Codex (AGENTS.md) or Gemini (GEMINI.md)
   * reads.
   */
  @GetMapping("/export")
  public ResponseEntity<String> export(
      @PathVariable String owner, @PathVariable String name, @RequestParam String format) {
    String file =
        Map.of("claude", "CLAUDE.md", "codex", "AGENTS.md", "gemini", "GEMINI.md").get(format);
    if (file == null) throw new IllegalArgumentException("Formato desconocido: " + format);
    return ResponseEntity.ok()
        .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + file + "\"")
        .contentType(MediaType.parseMediaType("text/markdown;charset=UTF-8"))
        .body(ProfilePrompt.export(profiles.stored(owner + "/" + name), format));
  }

  @ExceptionHandler(IllegalArgumentException.class)
  @ResponseStatus(org.springframework.http.HttpStatus.CONFLICT)
  public Object invalid(IllegalArgumentException e) {
    return Map.of("error", e.getMessage());
  }
}
