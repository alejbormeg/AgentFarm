package io.agentfarm.core;

import java.time.Clock;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Slows down guessing a password: after a few failures in a row, one key waits, longer each time.
 * Kept in memory: a restart forgets it, which only gives an attacker what restarting the panel
 * would already require access to. The key is an account and an address, so someone guessing from
 * elsewhere doesn't lock its owner out.
 */
public final class LoginThrottle {
  static final int FREE_FAILURES = 5;
  static final long FIRST_WAIT_SECONDS = 60, LONGEST_WAIT_SECONDS = 900;
  private static final int MAX_KEYS = 10_000;

  private record Failures(int count, long lastAt, long blockedUntil) {}

  private final Clock clock;
  private final ConcurrentHashMap<String, Failures> keys = new ConcurrentHashMap<>();

  public LoginThrottle(Clock clock) {
    this.clock = clock;
  }

  /** Seconds this key must still wait; 0 if it may try. */
  public long waitSeconds(String key) {
    var failures = keys.get(key);
    return failures == null ? 0 : Math.max(0, failures.blockedUntil() - now());
  }

  public void failed(String key) {
    long now = now();
    // Random account names must not grow this without limit: forget what has already expired.
    if (keys.size() >= MAX_KEYS)
      keys.values().removeIf(f -> now - f.lastAt() > LONGEST_WAIT_SECONDS);
    if (keys.size() >= MAX_KEYS && !keys.containsKey(key)) return;
    keys.compute(
        key,
        (k, old) -> {
          // A long quiet spell starts the count again, so a typo a week doesn't add up.
          int count =
              old == null || now - old.lastAt() > 2 * LONGEST_WAIT_SECONDS ? 1 : old.count() + 1;
          return new Failures(count, now, now + wait(count));
        });
  }

  public void succeeded(String key) {
    keys.remove(key);
  }

  private static long wait(int count) {
    if (count < FREE_FAILURES) return 0;
    int doublings = Math.min(count - FREE_FAILURES, 10);
    return Math.min(FIRST_WAIT_SECONDS << doublings, LONGEST_WAIT_SECONDS);
  }

  private long now() {
    return clock.instant().getEpochSecond();
  }
}
