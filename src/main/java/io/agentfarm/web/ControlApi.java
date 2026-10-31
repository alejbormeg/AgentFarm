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

  public ControlApi(CoordinatorService t, ControlService c, BudgetService b) {
    tasks = t;
    control = c;
    budgets = b;
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
        false);
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

  @GetMapping("/budget/{account}")
  public Object budget(@PathVariable String account) {
    return budgets.status(account);
  }

  @PostMapping("/budget/{operation}")
  public Object budget(@PathVariable String operation, @RequestBody JsonNode n, Principal p) {
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
