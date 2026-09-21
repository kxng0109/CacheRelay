package io.github.kxng0109.cacherelay.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;

/**
 * Dedicated scheduler for the thirteen {@code @Scheduled} jobs (PERF-10): Spring's
 * default single-threaded scheduler serialized retention sweeps, pricing syncs,
 * keep-warm heartbeats, and ledger drains behind each other — one slow job stalled
 * all twelve others. The pool keeps logging, maintenance, and heartbeat work off
 * each other's critical paths with graceful shutdown.
 *
 * @since 1.7.0
 */
@Configuration
public class SchedulingConfig {

	/**
	 * Creates the shared task scheduler backing every {@code @Scheduled} job.
	 *
	 * <p>Delayed tasks never block shutdown: {@code setExecuteExistingDelayedTasksAfterShutdownPolicy(false)}
	 * cancels not-yet-started triggers on close, so {@code shutdown()} only waits for in-flight work up to
	 * {@code awaitTerminationSeconds}. Without this, a queued future fire time blocks container close for the
	 * full await window (Spring issue #26719) — which hung the surefire fork past its exit timeout in tests.</p>
	 *
	 * @param poolSize scheduler threads
	 * @param awaitTerminationSeconds cap on waiting for in-flight tasks at shutdown
	 * @return configured scheduler
	 */
	@Bean
	public TaskScheduler taskScheduler(
			@Value("${gateway.scheduling.pool-size:4}") int poolSize,
			@Value("${gateway.scheduling.await-termination-seconds:20}") int awaitTerminationSeconds) {
		ThreadPoolTaskScheduler scheduler = new ThreadPoolTaskScheduler();
		scheduler.setPoolSize(Math.max(2, poolSize));
		scheduler.setThreadNamePrefix("cacherelay-scheduled-");
		scheduler.setAwaitTerminationSeconds(Math.max(0, awaitTerminationSeconds));
		scheduler.setWaitForTasksToCompleteOnShutdown(true);
		scheduler.setExecuteExistingDelayedTasksAfterShutdownPolicy(false);
		scheduler.setRemoveOnCancelPolicy(true);
		return scheduler;
	}
}
