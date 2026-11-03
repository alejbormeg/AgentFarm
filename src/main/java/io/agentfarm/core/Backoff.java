package io.agentfarm.core;

/** Growing wait between attempts, so a stopped panel is not hit every few seconds forever. */
public final class Backoff {
  public static final long MAX_MILLIS = 60_000;

  private Backoff() {}

  /** Attempt 0 waits 2 s and each next one doubles it, up to one minute. */
  public static long millis(int attempt) {
    return attempt >= 5 ? MAX_MILLIS : 2_000L << Math.max(attempt, 0);
  }
}
