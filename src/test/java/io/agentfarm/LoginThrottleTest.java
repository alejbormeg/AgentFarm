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
}
