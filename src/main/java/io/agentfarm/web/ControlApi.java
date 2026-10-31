package io.agentfarm.web;

import com.fasterxml.jackson.databind.JsonNode;
import io.agentfarm.core.*;
import java.security.Principal;
import java.util.*;
import org.springframework.http.HttpStatus;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api")
public class ControlApi {
  private final CoordinatorService tasks;
  private final ControlService control;
  private final BudgetService budgets;
  private final TelemetryService telemetry;

  public ControlApi(
      CoordinatorService t, ControlService c, BudgetService b, TelemetryService telemetry) {
    tasks = t;
    control = c;
    budgets = b;
    this.telemetry = telemetry;
  }

  @GetMapping("/csrf")
  public Map<String, String> csrf(CsrfToken token) {
    return Map.of("header", token.getHeaderName(), "token", token.getToken());
  }

  @GetMapping("/overview")
  public Map<String, Object> overview() {
    return Json.obj(
        "paused",
        control.paused(),
        "nodes",
        control.nodes(),
        "workers",
        control.workers(),
        "tasks",
        tasks.tasks(),
        "budget_accounts",
        budgets.accounts(),
        "provider_execution",
        "Consult /api/runtime for observed external executions");
  }

  @GetMapping("/runtime")
  public Object runtime() {
    return telemetry.snapshot();
  }

  @PostMapping("/runtime/observations")
  public Object observeRuntime(@RequestBody JsonNode n, Principal p) {
    if (p.getName().startsWith("node:")
        && !n.path("snapshot").path("node").asText().equals(p.getName().substring(5)))
      throw new org.springframework.security.access.AccessDeniedException("Node mismatch");
    telemetry.ingest(n);
    return Map.of("state", "recorded");
  }

  @GetMapping("/nodes")
  public Object nodes() {
    return control.nodes();
  }

  @GetMapping("/workers")
  public Object workers() {
    return control.workers();
  }

  @GetMapping("/tasks")
  public Object tasks() {
    return tasks.tasks();
  }

  @GetMapping("/runs")
  public Object runs() {
    return tasks.report();
  }

  @GetMapping("/audit")
  public Object audit() {
    return control.audit();
  }

  @PostMapping("/nodes")
  public Object node(@RequestBody JsonNode n, Principal p) {
    control.registerNode(Json.text(n, "id"), Json.text(n, "role"), p.getName());
    return Map.of("state", "registered");
  }

  @PostMapping("/workers")
  public Object worker(@RequestBody JsonNode n, Principal p) {
    control.registerWorker(Json.text(n, "id"), Json.text(n, "node"), p.getName());
    return Map.of("state", "registered");
  }

  @PostMapping("/entities/{kind}/{id}/enabled/{enabled}")
  public Object enable(
      @PathVariable String kind,
      @PathVariable String id,
      @PathVariable boolean enabled,
      Principal p) {
    control.enable(kind, id, enabled, p.getName());
    return Map.of("state", "updated");
  }

  @PostMapping("/heartbeat/{kind}/{id}")
  public Object heartbeat(
      @PathVariable String kind, @PathVariable String id, @RequestBody JsonNode n, Principal p) {
    if (p.getName().startsWith("node:")) {
      String node = p.getName().substring(5);
      boolean own =
          kind.equals("node")
              ? id.equals(node)
              : kind.equals("worker")
                  && control.workers().stream()
                      .anyMatch(w -> id.equals(w.get("id")) && node.equals(w.get("node")));
      if (!own)
        throw new org.springframework.security.access.AccessDeniedException(
            "Heartbeat identity mismatch");
    }
    control.heartbeat(kind, id, Json.text(n, "state"), p.getName());
    return Map.of("state", "recorded");
  }

  @PostMapping("/governance/paused/{paused}")
  public Object paused(@PathVariable boolean paused, Principal p) {
    control.pause(paused, p.getName());
    return Map.of("paused", paused);
  }

  @PostMapping("/tasks")
  public Object enqueue(@RequestBody JsonNode n, Principal p) {
    return tasks.enqueue(n, p.getName());
  }

  @PostMapping("/simulate")
  public Object simulate(Principal p) {
    return tasks.simulate(2, 5, p.getName());
  }

  @PostMapping("/assign")
  public Object assign(@RequestBody JsonNode n, Principal p) {
    return control.assign(n, p.getName());
  }

  @PostMapping("/tasks/{project}/{id}/cancel")
  public Object cancel(@PathVariable String project, @PathVariable String id, Principal p) {
    control.cancel(project, id, p.getName());
    return Map.of("state", "cancellation_recorded");
  }

  @PostMapping("/tasks/{project}/{id}/progress")
  public Object progress(
      @PathVariable String project, @PathVariable String id, @RequestBody JsonNode n, Principal p) {
    control.progress(
        project,
        id,
        Json.text(n, "worker"),
        Math.toIntExact(Json.number(n, "progress")),
        p.getName());
    return Map.of("state", "reported");
  }

  @PostMapping("/tasks/{project}/{id}/candidate")
  public Object candidate(
      @PathVariable String project, @PathVariable String id, @RequestBody JsonNode n, Principal p) {
    control.finish(project, id, Json.text(n, "worker"), Json.text(n, "sha"), p.getName());
    return Map.of("state", "review", "implementation_verified", false);
  }

  @GetMapping("/budget/{account}/reservations")
  public Object reservations(@PathVariable String account) {
    return budgets.reservations(account);
  }

  @GetMapping("/budget/{account}")
  public Object budget(@PathVariable String account) {
    return budgets.status(account);
  }

  @PostMapping("/budget/{operation}")
  public Object budget(
      @PathVariable String operation,
      @RequestBody JsonNode n,
      Principal p,
      jakarta.servlet.http.HttpServletRequest request) {
    if (p.getName().startsWith("node:")
        && !n.path("account").asText().equals(request.getAttribute("agentfarm.account")))
      throw new org.springframework.security.access.AccessDeniedException("Account mismatch");
    return budgets.execute(operation, n, p.getName());
  }

  @ExceptionHandler(IllegalArgumentException.class)
  @ResponseStatus(HttpStatus.BAD_REQUEST)
  public Object invalid(IllegalArgumentException e) {
    return Map.of("error", e.getMessage());
  }

  @ExceptionHandler(IllegalStateException.class)
  @ResponseStatus(HttpStatus.CONFLICT)
  public Object conflict() {
    return Map.of("error", "Operation rejected; inspect server logs and current state");
  }
}
