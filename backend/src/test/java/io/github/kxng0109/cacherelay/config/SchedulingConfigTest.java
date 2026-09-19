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

		TaskScheduler scheduler = config.taskScheduler(4);

		assertThat(scheduler).isInstanceOf(ThreadPoolTaskScheduler.class);
		assertThat(((ThreadPoolTaskScheduler) scheduler).getPoolSize()).isEqualTo(4);
	}

	@Test
	@DisplayName("pool size floors at two threads")
	void poolSizeFloor() {
		SchedulingConfig config = new SchedulingConfig();

		TaskScheduler scheduler = config.taskScheduler(1);

		assertThat(((ThreadPoolTaskScheduler) scheduler).getPoolSize()).isEqualTo(2);
	}
}
