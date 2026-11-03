package io.agentfarm;

import static org.junit.jupiter.api.Assertions.assertEquals;

import io.agentfarm.core.Backoff;
import org.junit.jupiter.api.Test;

class BackoffTest {
  @Test
  void growsUpToOneMinute() {
    assertEquals(2_000, Backoff.millis(0));
    assertEquals(4_000, Backoff.millis(1));
    assertEquals(32_000, Backoff.millis(4));
    assertEquals(60_000, Backoff.millis(5));
    assertEquals(60_000, Backoff.millis(1_000));
  }
}
