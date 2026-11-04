package io.agentfarm;

import static io.agentfarm.core.Json.*;
import static org.junit.jupiter.api.Assertions.*;

import io.agentfarm.core.*;
import java.nio.file.Path;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;

class BudgetTest {
  @TempDir Path dir;
  Store store;
  BudgetService service;
  Clock clock = Clock.fixed(Instant.ofEpochSecond(110), ZoneOffset.UTC);

  @BeforeEach
  void setup() {
    store = new Store(dir.resolve("budget.db").toString());
    service = new BudgetService(store, clock);
    call(
        "configure",
        obj("account", "codex", "mode", "subscription", "shares", obj("weekly", 5000)));
    window("codex", "weekly", 100, 200, 10000, 0, 100);
  }

  Map<String, Object> call(String op, Object input) {
    return service.execute(op, MAPPER.valueToTree(input), "test");
  }

  void window(String a, String period, long start, long end, long total, Long used, Long at) {
    call(
        "open-window",
        obj(
            "account",
            a,
            "period",
            period,
            "start",
            start,
            "end",
            end,
            "total",
            total,
            "used",
            used,
            "observed_at",
            at));
  }

  void window(String a, String p, long s, long e, long total, int used, int at) {
    window(a, p, s, e, total, (long) used, (long) at);
  }

  Map<String, Object> reserve(String id, long amount) {
    return call("reserve", obj("account", "codex", "run_id", id, "amounts", obj("weekly", amount)));
  }

  Map<String, Object> settle(String id, Long amount) {
    return call(
        "settle",
        obj(
            "account",
            "codex",
            "run_id",
            id,
            "actual",
            amount == null ? null : obj("weekly", amount)));
  }

  @SuppressWarnings("unchecked")
  Map<String, Object> first() {
    return ((List<Map<String, Object>>) service.status("codex").get("windows")).getFirst();
  }

  @Test
  void halfWeeklyIsQuotaNotUsd() {
    assertEquals("basis_points", service.status("codex").get("unit"));
    assertEquals(5000L, first().get("cap"));
    assertEquals("reserved", reserve("one", 5000).get("state"));
    assertEquals("paused", reserve("two", 1).get("state"));
  }

  @Test
  void reservationIsSharedAndUnusedPartReleased() {
    reserve("one", 3000);
    assertEquals(2000L, first().get("remaining"));
    settle("one", 1200L);
    assertEquals(3800L, first().get("remaining"));
    assertEquals("paused", reserve("two", 3801).get("state"));
  }

  @Test
  void duplicateNeverAuthorizesExecution() {
    reserve("one", 1000);
    assertEquals("duplicate", reserve("one", 1000).get("state"));
    assertThrows(IllegalArgumentException.class, () -> reserve("one", 999));
    settle("one", 500L);
    assertEquals("settled", reserve("one", 1000).get("previous_state"));
  }

  @Test
  void settlementIsIdempotent() {
    reserve("one", 1000);
    settle("one", 500L);
    assertEquals("already_settled", settle("one", 500L).get("state"));
    assertEquals(500L, first().get("used"));
    assertThrows(IllegalArgumentException.class, () -> settle("one", 0L));
  }

  @Test
  void unknownCostHoldsAndPauses() {
    reserve("one", 1000);
    settle("one", null);
    assertEquals(1000L, first().get("reserved"));
    assertEquals("paused", reserve("two", 1000).get("state"));
    settle("one", 400L);
    assertEquals("reserved", reserve("two", 1000).get("state"));
  }

  @Test
  void overrunIsRecordedAndBlocksAccount() {
    reserve("one", 1000);
    assertEquals("overrun", settle("one", 1100L).get("state"));
    assertEquals(1100L, first().get("used"));
    assertEquals("paused", reserve("two", 1).get("state"));
  }

  @Test
  void cancelledWithoutUsageCanSettleZero() {
    reserve("one", 1000);
    settle("one", 0L);
    assertEquals(5000L, first().get("remaining"));
  }

  @Test
  void expiredWindowDoesNotResetImplicitly() {
    service = new BudgetService(store, Clock.fixed(Instant.ofEpochSecond(200), ZoneOffset.UTC));
    assertEquals("paused", reserve("one", 1).get("state"));
  }

  @Test
  void missingTelemetryPauses() {
    window("codex", "weekly", 200, 400, 10000, null, null);
    service = new BudgetService(store, Clock.fixed(Instant.ofEpochSecond(201), ZoneOffset.UTC));
    assertEquals("paused", reserve("one", 1).get("state"));
  }

  @Test
  void staleOrFutureTelemetryPauses() {
    call(
        "open-window",
        obj(
            "account", "codex", "period", "weekly", "start", 200, "end", 1000, "total", 10000,
            "max_age", 5));
    service = new BudgetService(store, Clock.fixed(Instant.ofEpochSecond(210), ZoneOffset.UTC));
    call(
        "observe",
        obj("account", "codex", "period", "weekly", "start", 200, "used", 0, "observed_at", 200));
    assertEquals("paused", reserve("one", 1).get("state"));
  }

  @Test
  void newWindowUsesProviderBoundary() {
    window("codex", "weekly", 200, 400, 10000, null, null);
    service = new BudgetService(store, Clock.fixed(Instant.ofEpochSecond(200), ZoneOffset.UTC));
    call(
        "observe",
        obj("account", "codex", "period", "weekly", "start", 200, "used", 0, "observed_at", 200));
    assertEquals("reserved", reserve("one", 100).get("state"));
  }

  @Test
  void crossResetReservationCannotDisappear() {
    reserve("one", 1000);
    window("codex", "weekly", 200, 400, 10000, null, null);
    service = new BudgetService(store, Clock.fixed(Instant.ofEpochSecond(201), ZoneOffset.UTC));
    call(
        "observe",
        obj("account", "codex", "period", "weekly", "start", 200, "used", 0, "observed_at", 200));
    assertEquals("paused", reserve("two", 1).get("state"));
    assertEquals("crossed_reset", settle("one", 200L).get("state"));
    assertEquals("paused", reserve("two", 1).get("state"));
  }

  @Test
  void externalObservationCannotRefundAccountedUsage() {
    reserve("one", 1000);
    settle("one", 500L);
    call(
        "observe",
        obj(
            "account",
            "codex",
            "period",
            "weekly",
            "start",
            100,
            "used",
            3000,
            "observed_at",
            110));
    reserve("two", 1000);
    settle("two", 800L);
    service = new BudgetService(store, Clock.fixed(Instant.ofEpochSecond(111), ZoneOffset.UTC));
    call(
        "observe",
        obj(
            "account",
            "codex",
            "period",
            "weekly",
            "start",
            100,
            "used",
            3500,
            "observed_at",
            111));
    assertEquals(3800L, first().get("used"));
  }

  @Test
  void snapshotsDoNotReleaseHolds() {
    reserve("one", 3000);
    call(
        "observe",
        obj(
            "account",
            "codex",
            "period",
            "weekly",
            "start",
            100,
            "used",
            2000,
            "observed_at",
            110));
    assertEquals(0L, first().get("remaining"));
  }

  @Test
  void rejectsFutureDecreasingOrOldObservations() {
    assertThrows(
        IllegalArgumentException.class,
        () ->
            call(
                "observe",
                obj(
                    "account",
                    "codex",
                    "period",
                    "weekly",
                    "start",
                    100,
                    "used",
                    0,
                    "observed_at",
                    111)));
    call(
        "observe",
        obj("account", "codex", "period", "weekly", "start", 100, "used", 100, "observed_at", 110));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            call(
                "observe",
                obj(
                    "account",
                    "codex",
                    "period",
                    "weekly",
                    "start",
                    100,
                    "used",
                    99,
                    "observed_at",
                    110)));
  }

  @Test
  void immutablePolicyAndNoOverlap() {
    assertThrows(
        IllegalArgumentException.class,
        () ->
            call(
                "configure",
                obj("account", "codex", "mode", "api", "shares", obj("weekly", 10000))));
    assertThrows(
        IllegalArgumentException.class, () -> window("codex", "weekly", 199, 300, 10000, 0, 100));
    assertThrows(
        IllegalArgumentException.class, () -> window("codex", "weekly", 200, 300, 20, null, null));
  }

  @Test
  void everyWindowIsMandatory() {
    call(
        "configure",
        obj(
            "account",
            "multi",
            "mode",
            "subscription",
            "shares",
            obj("weekly", 5000, "five_hour", 8000)));
    window("multi", "weekly", 100, 300, 10000, 0, 100);
    assertEquals(
        "paused",
        call(
                "reserve",
                obj(
                    "account",
                    "multi",
                    "run_id",
                    "one",
                    "amounts",
                    obj("weekly", 1, "five_hour", 1)))
            .get("state"));
  }

  @Test
  void apiCostHitsBothMonthAndWeek() {
    call(
        "configure",
        obj("account", "api", "mode", "api", "shares", obj("monthly", 10000, "weekly", 5000)));
    window("api", "monthly", 100, 200, 20_000_000, 0, 100);
    window("api", "weekly", 100, 200, 5_000_000, 0, 100);
    assertThrows(
        IllegalArgumentException.class,
        () ->
            call(
                "reserve",
                obj("account", "api", "run_id", "bad", "amounts", obj("monthly", 1, "weekly", 2))));
    assertEquals(
        "paused",
        call(
                "reserve",
                obj(
                    "account",
                    "api",
                    "run_id",
                    "one",
                    "amounts",
                    obj("monthly", 2_500_001, "weekly", 2_500_001)))
            .get("state"));
  }

  @Test
  void validatesAmountsAndClock() {
    for (Object value : List.of(-1, 0, true, 1.5, "10"))
      assertThrows(
          IllegalArgumentException.class,
          () ->
              call(
                  "reserve",
                  obj("account", "codex", "run_id", "bad", "amounts", obj("weekly", value))));
    assertThrows(
        IllegalArgumentException.class, () -> call("status", obj("account", "codex", "now", 110)));
  }

  @Test
  void zeroShareDisablesWork() {
    call(
        "configure",
        obj("account", "disabled", "mode", "subscription", "shares", obj("weekly", 0)));
    window("disabled", "weekly", 100, 200, 10000, 0, 100);
    assertEquals(
        "paused",
        call("reserve", obj("account", "disabled", "run_id", "one", "amounts", obj("weekly", 1)))
            .get("state"));
  }

  @Test
  void concurrentWorkersCannotOversubscribeAndRestartPreservesHolds() throws Exception {
    var barrier = new CyclicBarrier(2);
    try (var pool = Executors.newFixedThreadPool(2)) {
      var a =
          pool.submit(() -> {
            barrier.await(10, TimeUnit.SECONDS);
            return reserve("a", 3000).get("state");
          });
      var b =
          pool.submit(() -> {
            barrier.await(10, TimeUnit.SECONDS);
            return reserve("b", 3000).get("state");
          });
      assertEquals(Set.of("reserved", "paused"), Set.of(a.get(), b.get()));
    }
    service = new BudgetService(new Store(dir.resolve("budget.db").toString()), clock);
    assertEquals(3000L, first().get("reserved"));
  }
}
