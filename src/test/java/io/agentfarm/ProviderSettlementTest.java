package io.agentfarm;

import static io.agentfarm.core.Json.*;
import static org.junit.jupiter.api.Assertions.*;

import io.agentfarm.core.*;
import java.nio.file.*;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;

class ProviderSettlementTest {
  @TempDir Path dir;
  Store db;
  BudgetService b;
  Clock clock;

  @BeforeEach
  void setup() {
    clock = Clock.fixed(Instant.ofEpochSecond(1000000), ZoneOffset.UTC);
    db = new Store(dir.resolve("db").toString());
    b = new BudgetService(db, clock);
    run("configure", obj("mode", "subscription", "shares", obj("weekly", 9800)));
    run(
        "open-window",
        obj(
            "period",
            "weekly",
            "start",
            500000,
            "end",
            1104800,
            "total",
            10000,
            "used",
            1000,
            "observed_at",
            999999,
            "source",
            "provider",
            "max_age",
            60));
  }

  Map<String, Object> run(String op, Map<String, Object> n) {
    n.put("account", "test");
    return b.execute(op, MAPPER.valueToTree(n), "test");
  }

  Map<?, ?> window() {
    return ((List<Map<?, ?>>) b.status("test").get("windows")).getFirst();
  }

  @Test
  void cumulativeObservationIsNotCountedTwice() {
    run("reserve", obj("run_id", "r", "amounts", obj("weekly", 500)));
    run("observe", obj("period", "weekly", "start", 500000, "used", 1200, "observed_at", 1000000));
    run("settle-observed", obj("run_id", "r"));
    assertEquals(1200L, window().get("used"));
    assertEquals(0L, window().get("reserved"));
    assertEquals("already_reconciled", run("settle-observed", obj("run_id", "r")).get("state"));
  }

  @Test
  void staleUsageCannotReleaseReservation() {
    run("reserve", obj("run_id", "r", "amounts", obj("weekly", 500)));
    var later = new BudgetService(db, Clock.fixed(Instant.ofEpochSecond(1000061), ZoneOffset.UTC));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            later.execute(
                "settle-observed",
                MAPPER.valueToTree(obj("account", "test", "run_id", "r")),
                "owner"));
  }

  @Test
  void rolloverPreservesHistoryAndRejectsReservations() {
    run("reserve", obj("run_id", "r", "amounts", obj("weekly", 500)));
    var next = new BudgetService(db, Clock.fixed(Instant.ofEpochSecond(1000100), ZoneOffset.UTC));
    var n =
        MAPPER.valueToTree(
            obj(
                "account",
                "test",
                "period",
                "weekly",
                "start",
                1000050,
                "end",
                1604850,
                "total",
                10000,
                "used",
                50,
                "observed_at",
                1000100,
                "source",
                "provider",
                "max_age",
                60));
    assertThrows(IllegalArgumentException.class, () -> next.execute("rollover", n, "owner"));
    run("settle-observed", obj("run_id", "r"));
    assertEquals("window_opened", next.execute("rollover", n, "owner").get("state"));
    assertEquals(2, db.tx(c -> Store.rows(c, "SELECT * FROM budget_windows")).size());
  }
}
