package io.agentfarm;

import static org.junit.jupiter.api.Assertions.*;

import io.agentfarm.core.*;
import io.agentfarm.web.SchedulingConfig;
import java.nio.file.Path;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ScheduledWorkTest {
  @TempDir Path dir;

  /** A clock the test moves by hand. */
  static final class Manual extends Clock {
    long millis = 1_000_000;

    public ZoneId getZone() {
      return ZoneOffset.UTC;
    }

    public Clock withZone(ZoneId zone) {
      return this;
    }

    public Instant instant() {
      return Instant.ofEpochMilli(millis);
    }
  }

  @Test
  void eachRunRecordsLagDurationAndFailures() {
    var clock = new Manual();
    var work = new ScheduledWork(clock);
    work.run("EvidenceVerifier.tick", 30_000, () -> clock.millis += 70_000);
    var row = work.report().get(0);
    assertEquals("late", row.get("state"), "took longer than " + ScheduledWork.SLOW_MS);
    assertEquals(70_000L, row.get("last_duration_ms"));

    clock.millis += 30_000 + 45_000; // due 30 s after it ended, started 45 s late
    work.run("EvidenceVerifier.tick", 30_000, () -> clock.millis += 10);
    row = work.report().get(0);
    assertEquals(45_000L, row.get("lag_ms"));
    assertEquals("late", row.get("state"));

    clock.millis += 30_000;
    assertThrows(
        IllegalStateException.class,
        () ->
            work.run(
                "EvidenceVerifier.tick",
                30_000,
                () -> {
                  throw new IllegalStateException("GitHub caído con ghp_" + "a".repeat(36));
                }));
    row = work.report().get(0);
    assertEquals("failing", row.get("state"));
    assertEquals(1L, row.get("failures"));
    assertFalse(row.get("last_error").toString().contains("a".repeat(36)));

    clock.millis += 30_000;
    work.run("EvidenceVerifier.tick", 30_000, () -> {});
    assertEquals("ok", work.report().get(0).get("state"));
    assertEquals(4L, work.report().get(0).get("runs"));
  }

  /** The real scheduler: one job stuck on a slow call doesn't stop another. */
  @Test
  void aStuckJobDoesNotDelayTheOthers() throws Exception {
    var work = new ScheduledWork(Clock.systemUTC());
    var scheduler = SchedulingConfig.scheduler(work);
    var release = new CountDownLatch(1);
    var fast = new AtomicInteger();
    try {
      scheduler.scheduleWithFixedDelay(
          () -> {
            try {
              release.await(10, TimeUnit.SECONDS); // a webhook that never answers
            } catch (InterruptedException e) {
              Thread.currentThread().interrupt();
            }
          },
          Duration.ofMillis(10));
      scheduler.scheduleWithFixedDelay(fast::incrementAndGet, Duration.ofMillis(20));
      long until = System.currentTimeMillis() + 5_000;
      while (fast.get() < 10 && System.currentTimeMillis() < until) Thread.sleep(20);
      assertTrue(fast.get() >= 10, "integration kept running: " + fast.get());
      assertTrue(
          work.report().stream().anyMatch(r -> ((Number) r.get("running_ms")).longValue() > 0),
          "the stuck job shows as running");
    } finally {
      release.countDown();
      scheduler.shutdown();
    }
    assertEquals(
        SchedulingConfig.POOL, scheduler.getScheduledThreadPoolExecutor().getCorePoolSize());
  }

  /** GitHub answering slowly: each cycle stops at its budget and the next goes on from there. */
  @Test
  void slowGitHubSpreadsTheVerificationOverCyclesWithoutStarvingAnyTask() {
    var store = new Store(dir.resolve("db").toString());
    new WorkflowService(store, Clock.systemUTC());
    for (int i = 1; i <= 4; i++) {
      int pr = i;
      store.tx(c ->
          Store.exec(
              c,
              "INSERT INTO work_items(id,repo,title,request,state,pr,sha,created)"
                  + " VALUES(?,'o/r','t','r','review',?,?,0)",
              "task-" + pr,
              pr,
              "a".repeat(40)));
    }
    var asked = new ArrayList<String>();
    var verifier =
        new EvidenceVerifier(
            store,
            Clock.systemUTC(),
            (repo, path) -> {
              asked.add(path);
              Thread.sleep(30);
              throw new java.io.IOException("GitHub lento");
            },
            () -> true);
    for (int cycle = 0; cycle < 4; cycle++) verifier.tick(1);
    assertEquals(List.of("/pulls/1", "/pulls/2", "/pulls/3", "/pulls/4"), asked);
    verifier.tick(1);
    assertEquals("/pulls/1", asked.get(4), "wraps around");
  }
}
