package io.agentfarm;

import static org.junit.jupiter.api.Assertions.*;

import io.agentfarm.core.LoginThrottle;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;

class LoginThrottleTest {
  final AtomicLong now = new AtomicLong(1000);
  final Clock clock =
      new Clock() {
        @Override
        public Instant instant() {
          return Instant.ofEpochSecond(now.get());
        }

        @Override
        public java.time.ZoneId getZone() {
          return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(java.time.ZoneId zone) {
          return this;
        }
      };
  final LoginThrottle throttle = new LoginThrottle(clock);

  void fail(String key, int times) {
    for (int i = 0; i < times; i++) throttle.failed(key);
  }

  @Test
  void aFewMistakesCostNothing() {
    fail("ana|1", 4);
    assertEquals(0, throttle.waitSeconds("ana|1"));
  }

  @Test
  void theWaitStartsAtAMinuteAndDoublesUpToALimit() {
    fail("ana|1", 5);
    assertEquals(60, throttle.waitSeconds("ana|1"));
    fail("ana|1", 1);
    assertEquals(120, throttle.waitSeconds("ana|1"));
    fail("ana|1", 30);
    assertEquals(900, throttle.waitSeconds("ana|1"), "never more than 15 minutes");
  }

  @Test
  void theWaitEnds() {
    fail("ana|1", 5);
    now.addAndGet(61);
    assertEquals(0, throttle.waitSeconds("ana|1"));
  }

  @Test
  void aKeyDoesNotBlockAnother() {
    fail("ana|1", 5);
    assertEquals(0, throttle.waitSeconds("ana|2"), "the owner elsewhere can still enter");
    assertEquals(0, throttle.waitSeconds("eva|1"));
  }

  @Test
  void aSuccessForgetsAndALongQuietStartsAgain() {
    fail("ana|1", 4);
    throttle.succeeded("ana|1");
    fail("ana|1", 4);
    assertEquals(0, throttle.waitSeconds("ana|1"));
    now.addAndGet(1801);
    throttle.failed("ana|1");
    assertEquals(0, throttle.waitSeconds("ana|1"), "old mistakes no longer count");
  }

  @Test
  void randomNamesCannotGrowItWithoutLimit() {
    for (int i = 0; i < 10_050; i++) throttle.failed("n" + i + "|1");
    now.addAndGet(901);
    throttle.failed("otra|1");
    fail("ana|1", 5);
    assertEquals(60, throttle.waitSeconds("ana|1"), "expired keys made room again");
  }

  @Test
  void manyNamesFromOneAddressAreSlowedDown() {
    for (int i = 0; i < LoginThrottle.ADDRESS_FREE_FAILURES; i++) throttle.failed("n" + i + "|1");
    assertEquals(60, throttle.waitSeconds("otra|1"), "a new name from the same address waits");
    assertEquals(0, throttle.waitSeconds("otra|2"), "another address does not");
  }

  @Test
  void aFullMemoryStillLimitsANewCombination() {
    // 10,000 recent keys from as many addresses: nothing has expired to make room.
    for (int i = 0; i < 10_000; i++) throttle.failed("n" + i + "|10." + i);
    assertTrue(throttle.waitSeconds("nueva|9.9.9.9") > 0, "no free attempts by cardinality");
    now.addAndGet(LoginThrottle.LONGEST_WAIT_SECONDS + 1);
    assertEquals(0, throttle.waitSeconds("nueva|9.9.9.9"), "the shared wait ends too");
  }

  @Test
  void theOwnerIsNotLockedOutByAnAttackFromElsewhere() {
    throttle.succeeded("admin|1.1.1.1");
    for (int i = 0; i < 10_000; i++) throttle.failed("n" + i + "|10." + i);
    assertEquals(0, throttle.waitSeconds("admin|1.1.1.1"));
    fail("admin|1.1.1.1", 5);
    assertEquals(60, throttle.waitSeconds("admin|1.1.1.1"), "but its own mistakes still count");
  }

  @Test
  void concurrentFailuresAllCount() throws Exception {
    var pool = java.util.concurrent.Executors.newFixedThreadPool(8);
    var done = new java.util.concurrent.CountDownLatch(8);
    for (int t = 0; t < 8; t++) {
      int thread = t;
      pool.execute(() -> {
        for (int i = 0; i < 2_000; i++) throttle.failed("n" + thread + "-" + i + "|10." + i);
        for (int i = 0; i < 10; i++) throttle.failed("ana|1");
        done.countDown();
      });
    }
    assertTrue(done.await(30, java.util.concurrent.TimeUnit.SECONDS));
    pool.shutdown();
    assertEquals(900, throttle.waitSeconds("ana|1"), "80 failures, none lost");
    assertTrue(throttle.waitSeconds("nueva|9.9.9.9") > 0);
  }
}
