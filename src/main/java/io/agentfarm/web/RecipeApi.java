package io.agentfarm.web;

import io.agentfarm.core.RecipeCatalog;
import io.agentfarm.core.SkillCatalog;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api")
public class RecipeApi {
  @GetMapping("/recipes")
  public Object list() {
    return RecipeCatalog.list();
  }

  @GetMapping("/skills")
  public Object skills() {
    return SkillCatalog.list();
  }
}
