package io.agentfarm.core;

import java.time.Clock;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Slows down guessing a password: after a few failures in a row, one key waits, longer each time.
 * Kept in memory: a restart forgets it, which only gives an attacker what restarting the panel
 * would already require access to.
 *
 * <p>Three limits add up. The key is an account and an address, so someone guessing from elsewhere
 * doesn't lock its owner out. The address alone has a looser limit, so trying many account names
 * from one place is slowed down too. And when random names fill the memory, new keys share one
 * overflow limit instead of getting free attempts. An account and address that logged in before
 * skip that overflow, so an attack from elsewhere cannot keep the owner out.
 */
public final class LoginThrottle {
  public static final int FREE_FAILURES = 5,
      ADDRESS_FREE_FAILURES = 20,
      OVERFLOW_FREE_FAILURES = 100;
  public static final long FIRST_WAIT_SECONDS = 60, LONGEST_WAIT_SECONDS = 900;
  static final int MAX_KEYS = 10_000, MAX_KNOWN = 1_000;
  private static final String ADDRESS = "@";

  private record Failures(int count, long lastAt, long blockedUntil) {}

  private final Clock clock;
  private final ConcurrentHashMap<String, Failures> keys = new ConcurrentHashMap<>();
  private Failures overflow;

  /** Keys that logged in, most recent last; they never wait for the overflow limit. */
  private final Map<String, Boolean> known =
      Collections.synchronizedMap(
          new LinkedHashMap<>(16, 0.75f, true) {
            @Override
            protected boolean removeEldestEntry(Map.Entry<String, Boolean> eldest) {
              return size() > MAX_KNOWN;
            }
          });

  public LoginThrottle(Clock clock) {
    this.clock = clock;
  }

  /** The address part of an "account|address" key. */
  static String address(String key) {
    return ADDRESS + key.substring(key.lastIndexOf('|') + 1);
  }

  /** Seconds this key must still wait; 0 if it may try. */
  public long waitSeconds(String key) {
    long now = now(), wait = Math.max(left(keys.get(key), now), left(keys.get(address(key)), now));
    if (!keys.containsKey(key) && !known.containsKey(key) && keys.size() >= MAX_KEYS)
      wait = Math.max(wait, left(overflow(), now));
    return wait;
  }

  public void failed(String key) {
    long now = now();
    // Random account names must not grow this without limit: forget what has already expired.
    if (keys.size() >= MAX_KEYS)
      keys.values().removeIf(f -> now - f.lastAt() > LONGEST_WAIT_SECONDS);
    for (String k : List.of(key, address(key))) {
      // Full: a new key's failure still counts, against a limit every new key shares. A key that
      // logged in is always kept: at most MAX_KNOWN of them, so memory stays bounded.
      if (keys.size() >= MAX_KEYS && !keys.containsKey(k) && !known.containsKey(key)) {
        countOverflow(now);
        continue;
      }
      keys.compute(
          k,
          (name, old) ->
              next(old, now, k.startsWith(ADDRESS) ? ADDRESS_FREE_FAILURES : FREE_FAILURES));
    }
  }

  public void succeeded(String key) {
    keys.remove(key);
    known.put(key, true);
  }

  private synchronized void countOverflow(long now) {
    overflow = next(overflow, now, OVERFLOW_FREE_FAILURES);
  }

  private synchronized Failures overflow() {
    return overflow;
  }

  private static Failures next(Failures old, long now, int free) {
    // A long quiet spell starts the count again, so a typo a week doesn't add up.
    int count = old == null || now - old.lastAt() > 2 * LONGEST_WAIT_SECONDS ? 1 : old.count() + 1;
    return new Failures(count, now, now + wait(count, free));
  }

  private static long left(Failures failures, long now) {
    return failures == null ? 0 : Math.max(0, failures.blockedUntil() - now);
  }

  private static long wait(int count, int free) {
    if (count < free) return 0;
    int doublings = Math.min(count - free, 10);
    return Math.min(FIRST_WAIT_SECONDS << doublings, LONGEST_WAIT_SECONDS);
  }

  private long now() {
    return clock.instant().getEpochSecond();
  }
}
