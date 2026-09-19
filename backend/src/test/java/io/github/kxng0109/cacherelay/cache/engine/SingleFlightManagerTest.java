package io.github.kxng0109.cacherelay.cache.engine;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("SingleFlightManager")
class SingleFlightManagerTest {

	@Test
	@DisplayName("execute invokes loader only once across multiple concurrent threads for same key")
	void executeDeduplication() throws Exception {
		SingleFlightManager manager = new SingleFlightManager();
		AtomicInteger executionCounter = new AtomicInteger(0);
		int threadCount = 20;

		CountDownLatch readyLatch = new CountDownLatch(threadCount);
		CountDownLatch startLatch = new CountDownLatch(1);
		CountDownLatch doneLatch = new CountDownLatch(threadCount);

		try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
			for (int i = 0; i < threadCount; i++) {
				executor.submit(() -> {
					readyLatch.countDown();
					try {
						startLatch.await();
						String res = manager.execute(
								"key1", () -> {
									executionCounter.incrementAndGet();
									Thread.sleep(50);
									return "computed-result";
								}
						);
						assertThat(res).isEqualTo("computed-result");
					} catch (Exception e) {
						throw new RuntimeException(e);
					} finally {
						doneLatch.countDown();
					}
				});
			}

			readyLatch.await();
			startLatch.countDown();
			doneLatch.await();
		}

		assertThat(executionCounter.get()).isEqualTo(1);
	}

	@Test
	@DisplayName("followers time out instead of blocking forever on a hung leader")
	void followerTimeout() throws Exception {
		SingleFlightManager manager = new SingleFlightManager();
		CountDownLatch leaderStarted = new CountDownLatch(1);
		CountDownLatch releaseLeader = new CountDownLatch(1);
		CountDownLatch doneLatch = new CountDownLatch(2);

		try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
			executor.submit(() -> {
				try {
					manager.execute(
							"slowKey",
							() -> {
								leaderStarted.countDown();
								if (!releaseLeader.await(10, TimeUnit.SECONDS)) {
									throw new IllegalStateException("leader not released");
								}
								return "late-result";
							},
							Duration.ofMillis(5_000)
					);
				} catch (Exception ignored) {
				} finally {
					doneLatch.countDown();
				}
			});
			executor.submit(() -> {
				try {
					assertThat(leaderStarted.await(10, TimeUnit.SECONDS)).isTrue();
					assertThatThrownBy(() -> manager.execute(
							"slowKey",
							() -> "follower-result",
							Duration.ofMillis(100)
					)).isInstanceOf(TimeoutException.class);
				} catch (InterruptedException e) {
					Thread.currentThread().interrupt();
				} finally {
					releaseLeader.countDown();
					doneLatch.countDown();
				}
			});
			assertThat(doneLatch.await(15, TimeUnit.SECONDS)).isTrue();
		}
	}

	@Test
	@DisplayName("execute propagates exceptions to all joined threads")
	void executeExceptionHandling() {
		SingleFlightManager manager = new SingleFlightManager();
		assertThatThrownBy(() -> manager.execute(
				"errKey", () -> {
					throw new IllegalStateException("Failed calculation");
				}
		)).isInstanceOf(IllegalStateException.class).hasMessage("Failed calculation");

		// Error/Throwable branch
		assertThatThrownBy(() -> manager.execute(
				"errThrowable", () -> {
					throw new AssertionError("Assertion error in loader");
				}
		)).isInstanceOf(RuntimeException.class).hasCauseInstanceOf(AssertionError.class);
	}
}
