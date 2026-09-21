package io.github.kxng0109.cacherelay.config;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("SchedulingConfig (PERF-10)")
class SchedulingConfigTest {

	@Test
	@DisplayName("provides a bounded pool scheduler instead of the single-threaded default")
	void boundedPoolScheduler() {
		SchedulingConfig config = new SchedulingConfig();

		TaskScheduler scheduler = config.taskScheduler(4, 20);

		assertThat(scheduler).isInstanceOf(ThreadPoolTaskScheduler.class);
		assertThat(((ThreadPoolTaskScheduler) scheduler).getPoolSize()).isEqualTo(4);
	}

	@Test
	@DisplayName("pool size floors at two threads")
	void poolSizeFloor() {
		SchedulingConfig config = new SchedulingConfig();

		TaskScheduler scheduler = config.taskScheduler(1, 20);

		assertThat(((ThreadPoolTaskScheduler) scheduler).getPoolSize()).isEqualTo(2);
	}

	@Test
	@DisplayName("delayed tasks never block shutdown (fork-hang guard)")
	void delayedTasksDoNotBlockShutdown() throws Exception {
		SchedulingConfig config = new SchedulingConfig();

		ThreadPoolTaskScheduler scheduler = (ThreadPoolTaskScheduler) config.taskScheduler(1, 5);
		scheduler.initialize();
		try {
			java.util.concurrent.CountDownLatch inFlight = new java.util.concurrent.CountDownLatch(1);
			java.util.concurrent.CountDownLatch release = new java.util.concurrent.CountDownLatch(1);
			scheduler.execute(() -> {
				inFlight.countDown();
				try {
					release.await(5, java.util.concurrent.TimeUnit.SECONDS);
				} catch (InterruptedException e) {
					Thread.currentThread().interrupt();
				}
			});
			assertThat(inFlight.await(5, java.util.concurrent.TimeUnit.SECONDS)).isTrue();
			scheduler.schedule(
					() -> {
					},
					new java.util.Date(System.currentTimeMillis() + 60_000));

			long startedAt = System.nanoTime();
			release.countDown();
			scheduler.shutdown();
			long elapsedMs = (System.nanoTime() - startedAt) / 1_000_000;

			assertThat(elapsedMs).isLessThan(5_000L);
		} finally {
			scheduler.shutdown();
		}
	}
}
