package io.agentfarm;

import static io.agentfarm.core.Json.*;
import static org.junit.jupiter.api.Assertions.*;

import io.agentfarm.core.*;
import java.nio.file.*;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class TelemetryTest {
  @TempDir Path dir;
  final Clock clock = Clock.fixed(Instant.ofEpochSecond(100), ZoneOffset.UTC);

  @Test
  void emptyDoesNotClaimConnection() {
    var t = new TelemetryService(new Store(dir.resolve("db").toString()), clock);
    assertEquals(false, t.snapshot().get("connected"));
    assertEquals(false, t.snapshot().get("fresh"));
  }

  @Test
  void deduplicatesAndExpiresObservationsAndDropsUnknownFields() {
    var store = new Store(dir.resolve("db").toString());
    var t = new TelemetryService(store, clock);
    var batch =
        MAPPER.valueToTree(
            obj(
                "snapshot",
                obj("node", "n", "password", "private", "codex_pid", 42),
                "events",
                List.of(
                    obj(
                        "external_id",
                        "e1",
                        "at",
                        "1970-01-01T00:01:40Z",
                        "worker",
                        "boxer",
                        "kind",
                        "output",
                        "text",
                        "Bearer very.secret.token"))));
    t.ingest(batch);
    t.ingest(batch);
    var snap = t.snapshot();
    assertEquals(true, snap.get("fresh"));
    assertEquals(1, ((List<?>) snap.get("events")).size());
    assertFalse(encode(snap).contains("very.secret.token"));
    assertFalse(encode(snap).contains("private"));
    var later =
        new TelemetryService(store, Clock.fixed(Instant.ofEpochSecond(113), ZoneOffset.UTC));
    assertEquals(false, later.snapshot().get("fresh"));
    assertEquals(true, later.snapshot().get("connected"));
  }

  @Test
  void staleQuotaCannotBecomeFreshByRepostingSnapshot() {
    var store = new Store(dir.resolve("db").toString());
    var service = new TelemetryService(store, clock);
    var batch =
        MAPPER.valueToTree(
            obj(
                "snapshot",
                obj("quota", obj("weekly_used_percent", 91, "observed_at", "1970-01-01T00:00:30Z")),
                "events",
                List.of()));
    service.ingest(batch);
    assertEquals(false, service.snapshot().get("quota_fresh"));
    ((com.fasterxml.jackson.databind.node.ObjectNode) batch.path("snapshot").path("quota"))
        .put("observed_at", "1970-01-01T00:01:40Z");
    service.ingest(batch);
    assertEquals(true, service.snapshot().get("quota_fresh"));
    assertEquals(
        false,
        new TelemetryService(store, Clock.fixed(Instant.ofEpochSecond(113), ZoneOffset.UTC))
            .snapshot()
            .get("quota_fresh"));
  }

  @Test
  void rejectsOversizedAndUnknownEventsBeforeMutation() {
    var t = new TelemetryService(new Store(dir.resolve("db").toString()), clock);
    var event =
        obj(
            "external_id",
            "x",
            "at",
            "1970-01-01T00:01:40Z",
            "worker",
            "boxer",
            "kind",
            "reasoning",
            "text",
            "not for UI");
    assertThrows(
        IllegalArgumentException.class,
        () -> t.ingest(MAPPER.valueToTree(obj("snapshot", Map.of(), "events", List.of(event)))));
    event.put("kind", "output");
    event.put("text", "x".repeat(6001));
    assertThrows(
        IllegalArgumentException.class,
        () -> t.ingest(MAPPER.valueToTree(obj("snapshot", Map.of(), "events", List.of(event)))));
    assertEquals(false, t.snapshot().get("connected"));
  }
}
