package io.agentfarm.web;

import io.agentfarm.adapters.SkillSources;
import java.util.Map;
import org.springframework.web.bind.annotation.*;

/** Owner only (SecurityConfig): the panel reaches out to GitHub on their behalf. */
@RestController
@RequestMapping("/api/skill-sources")
public class SkillSourcesApi {
  private final SkillSources sources;

  public SkillSourcesApi(SkillSources sources) {
    this.sources = sources;
  }

  @GetMapping
  public Object official() {
    return SkillSources.OFFICIAL;
  }

  @GetMapping("/browse")
  public Object browse(@RequestParam String repo, @RequestParam(defaultValue = "") String path)
      throws Exception {
    return sources.browse(repo, path);
  }

  @GetMapping("/skill")
  public Object skill(@RequestParam String repo, @RequestParam String path) throws Exception {
    return sources.fetchSkill(repo, path);
  }

  @GetMapping("/search")
  public Object search(@RequestParam String q) throws Exception {
    return sources.searchRepos(q);
  }

  @ExceptionHandler({IllegalArgumentException.class, IllegalStateException.class})
  @ResponseStatus(org.springframework.http.HttpStatus.BAD_REQUEST)
  public Object invalid(RuntimeException e) {
    return Map.of("error", e.getMessage());
  }
}
