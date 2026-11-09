package io.agentfarm.web;

import io.agentfarm.core.ScheduledWork;
import java.time.*;
import java.util.concurrent.ScheduledFuture;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.*;
import org.springframework.scheduling.annotation.SchedulingConfigurer;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;
import org.springframework.scheduling.config.ScheduledTaskRegistrar;

/**
 * One scheduler thread per job: integration, evidence, notifications, triggers and the digest each
 * wait only on themselves. Every job is a fixed delay, so it never overlaps itself and the pool
 * never queues. Each run is measured in {@link ScheduledWork}.
 */
@Configuration
public class SchedulingConfig implements SchedulingConfigurer {
  /** The scheduled jobs in the panel, plus one spare. */
  public static final int POOL = 6;

  private final ScheduledWork work;

  public SchedulingConfig(ScheduledWork work) {
    this.work = work;
  }

  @Bean
  static ScheduledWork scheduledWork(Clock clock) {
    return new ScheduledWork(clock);
  }

  @Override
  public void configureTasks(ScheduledTaskRegistrar registrar) {
    registrar.setTaskScheduler(scheduler(work));
  }

  /** {@code Class.method} of a scheduled method, from Spring's description of the task. */
  static String name(Runnable task) {
    var m =
        java.util.regex.Pattern.compile("([A-Za-z0-9_$]+)\\.([A-Za-z0-9_]+)\\s*$")
            .matcher(task.toString());
    return m.find() ? m.group(1) + "." + m.group(2) : task.getClass().getSimpleName();
  }

  public static ThreadPoolTaskScheduler scheduler(ScheduledWork work) {
    var scheduler =
        new ThreadPoolTaskScheduler() {
          @Override
          public ScheduledFuture<?> scheduleWithFixedDelay(Runnable task, Duration delay) {
            return super.scheduleWithFixedDelay(measured(task, delay), delay);
          }

          @Override
          public ScheduledFuture<?> scheduleWithFixedDelay(
              Runnable task, Instant start, Duration delay) {
            return super.scheduleWithFixedDelay(measured(task, delay), start, delay);
          }

          private Runnable measured(Runnable task, Duration delay) {
            String name = name(task);
            return () -> work.run(name, delay.toMillis(), task);
          }
        };
    scheduler.setPoolSize(POOL);
    scheduler.setThreadNamePrefix("agentfarm-scheduled-");
    scheduler.setErrorHandler(
        e -> LoggerFactory.getLogger(SchedulingConfig.class).warn("Scheduled job failed", e));
    scheduler.initialize();
    return scheduler;
  }
}
