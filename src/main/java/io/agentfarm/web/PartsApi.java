package io.agentfarm.web;

import io.agentfarm.adapters.ParentTasks;
import java.util.Map;
import org.springframework.web.bind.annotation.*;

/** The parts of every task that was split among repositories. Reading is open to every person. */
@RestController
@RequestMapping("/api/parts")
public class PartsApi {
  private final ParentTasks parts;

  public PartsApi(ParentTasks parts) {
    this.parts = parts;
  }

  @GetMapping
  public Object all() {
    return Map.of("parts", parts.all());
  }
}
