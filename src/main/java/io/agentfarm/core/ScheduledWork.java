package io.agentfarm.core;

import java.time.Clock;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * What each scheduled job did last: when it started, how long it took, how late it started and how
 * often it failed. Each job runs on its own scheduler thread, so a slow one (GitHub, a webhook)
 * only delays itself; SQLite still serializes the writes through {@link Store}.
 *
 * <p>Thresholds: a job is {@code late} when it started more than {@link #LATE_MS} after it was due
 * or its last run took longer than {@link #SLOW_MS}; {@code failing} after a failed run.
 */
public final class ScheduledWork {
  public static final long LATE_MS = 30_000, SLOW_MS = 60_000;

  private final Clock clock;
  private final Map<String, Job> jobs = new ConcurrentHashMap<>();

  private static final class Job {
    long runs, failures, lastStart, lastEnd = -1, lastDuration, lastLag, maxLag, running = -1;
    String lastError = "";
  }

  public ScheduledWork(Clock clock) {
    this.clock = clock;
  }

  /** Runs one cycle of {@code name}, due {@code periodMs} after its previous end. */
  public void run(String name, long periodMs, Runnable body) {
    var job = jobs.computeIfAbsent(name, n -> new Job());
    long start = clock.millis();
    synchronized (job) {
      long lag = job.lastEnd < 0 ? 0 : Math.max(0, start - (job.lastEnd + periodMs));
      job.lastStart = start;
      job.running = start;
      job.lastLag = lag;
      job.maxLag = Math.max(job.maxLag, lag);
    }
    boolean failed = false;
    String error = "";
    try {
      body.run();
    } catch (RuntimeException e) {
      failed = true;
      error = TelemetryService.clean(String.valueOf(e.getMessage()));
      throw e;
    } finally {
      long end = clock.millis();
      synchronized (job) {
        job.runs++;
        job.lastEnd = end;
        job.lastDuration = end - start;
        job.running = -1;
        if (failed) {
          job.failures++;
          job.lastError = error.length() > 300 ? error.substring(0, 300) : error;
        } else job.lastError = "";
      }
    }
  }

  /** One row per job, for the owner's health view. */
  public List<Map<String, Object>> report() {
    long now = clock.millis();
    var out = new ArrayList<Map<String, Object>>();
    for (var e : new TreeMap<>(jobs).entrySet()) {
      var j = e.getValue();
      synchronized (j) {
        long busy = j.running < 0 ? 0 : now - j.running;
        String state =
            !j.lastError.isEmpty()
                ? "failing"
                : j.lastLag > LATE_MS || j.lastDuration > SLOW_MS || busy > SLOW_MS ? "late" : "ok";
        out.add(
            Json.obj(
                "job", e.getKey(),
                "state", state,
                "runs", j.runs,
                "last_start_ms", j.lastStart,
                "failures", j.failures,
                "last_duration_ms", j.lastDuration,
                "lag_ms", j.lastLag,
                "max_lag_ms", j.maxLag,
                "running_ms", busy,
                "last_error", j.lastError));
      }
    }
    return out;
  }

  /** A deadline for one cycle: past it, the job leaves the rest for its next run. */
  public static final class Budget {
    private final Clock clock;
    private final long until;

    public Budget(Clock clock, long millis) {
      this.clock = clock;
      this.until = clock.millis() + millis;
    }

    public boolean spent() {
      return clock.millis() >= until;
    }
  }
}
