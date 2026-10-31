package io.agentfarm.web;

import com.fasterxml.jackson.databind.JsonNode;
import io.agentfarm.core.*;
import jakarta.servlet.http.HttpServletRequest;
import java.security.Principal;
import java.time.Clock;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/providers")
public class ProviderApi {
  private final BudgetService budgets;
  private final TelemetryService telemetry;
  private final Clock clock;

  public ProviderApi(BudgetService budgets, TelemetryService telemetry, Clock clock) {
    this.budgets = budgets;
    this.telemetry = telemetry;
    this.clock = clock;
  }

  @GetMapping
  public Object catalog() {
    return Json.obj(
        "contract_version",
        1,
        "providers",
        AgentAdapters.catalog(),
        "profiles",
        java.util.List.of("economic", "balanced", "reinforced"),
        "automatic_routing",
        false);
  }

  @PostMapping("/admission")
  public Object admission(@RequestBody JsonNode n, Principal principal, HttpServletRequest req) {
    String account = Json.text(n, "account");
    if (principal.getName().startsWith("node:")
        && !account.equals(req.getAttribute("agentfarm.account")))
      throw new org.springframework.security.access.AccessDeniedException(
          "Account not granted to executor");
    return ProviderAdmission.check(
        n,
        Json.MAPPER.valueToTree(budgets.status(account)),
        Json.MAPPER.valueToTree(telemetry.snapshot()),
        clock.instant());
  }

  @ExceptionHandler(IllegalArgumentException.class)
  @ResponseStatus(org.springframework.http.HttpStatus.BAD_REQUEST)
  public Object invalid(IllegalArgumentException e) {
    return java.util.Map.of("error", e.getMessage());
  }
}
