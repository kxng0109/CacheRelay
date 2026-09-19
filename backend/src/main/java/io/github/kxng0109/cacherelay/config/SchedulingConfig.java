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
	 * @param poolSize scheduler threads
	 * @return configured scheduler
	 */
	@Bean
	public TaskScheduler taskScheduler(
			@Value("${gateway.scheduling.pool-size:4}") int poolSize) {
		ThreadPoolTaskScheduler scheduler = new ThreadPoolTaskScheduler();
		scheduler.setPoolSize(Math.max(2, poolSize));
		scheduler.setThreadNamePrefix("cacherelay-scheduled-");
		scheduler.setAwaitTerminationSeconds(20);
		scheduler.setWaitForTasksToCompleteOnShutdown(true);
		scheduler.setRemoveOnCancelPolicy(true);
		return scheduler;
	}
}
