package io.github.kxng0109.cacherelay.ledger;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.util.concurrent.Executor;
import java.util.concurrent.ThreadPoolExecutor;

/**
 * Bounded executor for the pricing catalog sync.
 *
 * <p>A single thread serializes syncs; the queue absorbs bursts (startup plus cron overlap)
 * and {@code CallerRunsPolicy} degrades to inline execution rather than dropping a sync
 * when saturated.</p>
 */
@Configuration
public class PricingAsyncConfig {

	/**
	 * Creates the pricing sync executor.
	 *
	 * @return single-threaded bounded executor
	 */
	@Bean("pricingSyncExecutor")
	Executor pricingSyncExecutor() {
		ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
		executor.setCorePoolSize(1);
		executor.setMaxPoolSize(1);
		executor.setQueueCapacity(10);
		executor.setThreadNamePrefix("pricing-sync-");
		executor.setRejectedExecutionHandler(new ThreadPoolExecutor.CallerRunsPolicy());
		executor.initialize();
		return executor;
	}
}
