package io.agentfarm.web;

import io.agentfarm.core.RecipeCatalog;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/recipes")
public class RecipeApi {
  @GetMapping
  public Object list() {
    return RecipeCatalog.list();
  }
}
