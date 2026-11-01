package io.agentfarm.web;

import io.agentfarm.core.*;
import java.util.Map;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

/**
 * Liveness for Compose, systemd or an uptime monitor. Anonymous callers only learn UP/DOWN;
 * the owner also sees executors and queue counts.
 */
@RestController
public class HealthApi {
  private final Store store;
  private final TelemetryService telemetry;

  public HealthApi(Store store, TelemetryService telemetry) {
    this.store = store;
    this.telemetry = telemetry;
  }

  /** Who is signed in and with which role, so the panel can hide actions it cannot perform. */
  @GetMapping("/api/me")
  public Object me(org.springframework.security.core.Authentication auth) {
    String role =
        auth.getAuthorities().stream()
            .map(g -> g.getAuthority())
            .filter(g -> g.startsWith("ROLE_"))
            .map(g -> g.substring(5).toLowerCase(java.util.Locale.ROOT))
            .findFirst()
            .orElse("observer");
    return Map.of("user", auth.getName(), "role", role);
  }

  @GetMapping("/api/health")
  public ResponseEntity<Object> health(java.security.Principal p) {
    Map<String, Object> counts;
    try {
      counts =
          store.tx(
              c ->
                  Store.one(
                      c,
                      "SELECT (SELECT COUNT(*) FROM work_items WHERE state IN"
                          + " ('proposed','review','blocked')) waiting,(SELECT COUNT(*) FROM"
                          + " work_commands WHERE state='queued') queued,(SELECT paused FROM"
                          + " governance WHERE id=1) paused"));
    } catch (Exception e) {
      return ResponseEntity.status(503).body(Map.of("status", "DOWN"));
    }
    boolean owner =
        p instanceof org.springframework.security.core.Authentication auth
            && auth.getAuthorities().stream().anyMatch(g -> g.getAuthority().equals("ROLE_OWNER"));
    if (!owner) return ResponseEntity.ok(Map.of("status", "UP"));
    long fresh =
        ((java.util.List<?>) telemetry.snapshot().get("executors"))
            .stream()
                .filter(x -> Boolean.TRUE.equals(((Map<?, ?>) x).get("fresh")))
                .count();
    return ResponseEntity.ok(
        Json.obj(
            "status", "UP",
            "executors_fresh", fresh,
            "waiting_for_owner", counts.get("waiting"),
            "queued_commands", counts.get("queued"),
            "paused", Long.valueOf(1).equals(((Number) counts.get("paused")).longValue())));
  }
}
