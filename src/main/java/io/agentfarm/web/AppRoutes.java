package io.agentfarm.web;

import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;

/** Every view is the same page: it reads its address and draws itself. */
@Controller
public class AppRoutes {
  @GetMapping("/")
  public String home() {
    return "redirect:/overview";
  }

  @GetMapping({"/overview", "/tasks", "/agents", "/budgets", "/governance"})
  public String view() {
    return "forward:/app.html";
  }
}
