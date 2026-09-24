package io.github.kxng0109.cacherelay.capture;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.ArrayBlockingQueue;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("CaptureService")
class CaptureServiceTest {

	private CaptureEvent event(UUID requestId, String ownerId, String keyHash) {
		return new CaptureEvent(requestId, ownerId, keyHash, "gpt-4o", "openai",
				"{\"prompt\":\"hi\"}", "{\"output\":\"hello\"}", 10L, 5L, false, Instant.now());
	}

	private CaptureProperties enabled(int perMille, int ceiling,
			List<CaptureProperties.CaptureRule> allowlist) {
		return new CaptureProperties(true, perMille, ceiling, "./data/capture", 32768,
				268435456L, 90, 7, allowlist);
	}

	@Test
	@DisplayName("disabled capture accepts nothing")
	void disabledAcceptsNothing() {
		CaptureService service = new CaptureService(CaptureProperties.DEFAULTS);

		assertThat(service.offer(event(UUID.randomUUID(), "owner-1", "key-1"))).isFalse();
		assertThat(service.offered()).isZero();
		assertThat(service.droppedFull()).isZero();
		assertThat(service.droppedCeiling()).isZero();
	}

	@Test
	@DisplayName("allowlist matches by owner or key hash with full fidelity")
	void allowlistMatches() {
		CaptureProperties.CaptureRule rule =
				new CaptureProperties.CaptureRule("owner-1", "default", null, false);
		CaptureService service = new CaptureService(enabled(0, 100, List.of(rule)));

		assertThat(service.offer(event(UUID.randomUUID(), "owner-1", "other-key"))).isTrue();
		assertThat(service.offer(event(UUID.randomUUID(), "other-owner", "owner-1"))).isTrue();
		assertThat(service.offer(event(UUID.randomUUID(), "other-owner", "other-key"))).isFalse();
		assertThat(service.offered()).isEqualTo(2L);
	}

	@Test
	@DisplayName("sampling edges accept all or nothing deterministically")
	void samplingEdges() {
		CaptureService all = new CaptureService(enabled(1000, 100_000, List.of()));
		CaptureService none = new CaptureService(enabled(0, 100_000, List.of()));
		UUID id = UUID.randomUUID();

		assertThat(all.offer(event(id, null, null))).isTrue();
		assertThat(none.offer(event(id, null, null))).isFalse();
		assertThat(all.sampled(id)).isTrue();
		assertThat(all.sampled(id)).isTrue();
		assertThat(none.sampled(id)).isFalse();
	}

	@Test
	@DisplayName("half sampling keeps roughly half over many requests")
	void samplingDistribution() {
		CaptureService service = new CaptureService(enabled(500, 100_000, List.of()));
		int kept = 0;
		int total = 2000;
		for (int index = 0; index < total; index++) {
			if (service.sampled(UUID.randomUUID())) {
				kept++;
			}
		}

		assertThat(kept).isBetween(800, 1200);
	}

	@Test
	@DisplayName("persist ceiling drops the excess with a counter")
	void ceilingDropsExcess() {
		CaptureService service = new CaptureService(enabled(1000, 2, List.of()));

		assertThat(service.offer(event(UUID.randomUUID(), null, null))).isTrue();
		assertThat(service.offer(event(UUID.randomUUID(), null, null))).isTrue();
		assertThat(service.offer(event(UUID.randomUUID(), null, null))).isFalse();
		assertThat(service.droppedCeiling()).isEqualTo(1L);
	}

	@Test
	@DisplayName("full queues drop with a counter instead of blocking")
	void fullQueueDrops() {
		CaptureService service = new CaptureService(enabled(1000, 100_000, List.of()),
				new ArrayBlockingQueue<>(1));
		service.offer(event(UUID.randomUUID(), null, null));

		assertThat(service.offer(event(UUID.randomUUID(), null, null))).isFalse();
		assertThat(service.droppedFull()).isEqualTo(1L);
	}

	@Test
	@DisplayName("hot-path offers stay far below a generous bound")
	void offerStaysCheap() {		CaptureProperties.CaptureRule rule =
				new CaptureProperties.CaptureRule("owner-1", "default", null, false);
		CaptureService service = new CaptureService(
				enabled(1000, 100_000, List.of(rule)),
				new ArrayBlockingQueue<>(20_000));
		int iterations = 10_000;
		long start = System.nanoTime();
		for (int index = 0; index < iterations; index++) {
			service.offer(event(UUID.randomUUID(), "owner-1", "key-1"));
		}
		long meanNanos = (System.nanoTime() - start) / iterations;

		assertThat(service.offered()).isEqualTo(iterations);
		assertThat(meanNanos).isLessThan(100_000L);
	}

	@Test
	@DisplayName("truncate cuts over-long payloads and passes short ones through")
	void truncateBounds() {
		CaptureService service = new CaptureService(enabled(1000, 100_000, List.of()));

		assertThat(service.truncate("short")).isEqualTo("short");
		assertThat(service.truncate("x".repeat(40000))).hasSize(32768);
	}

	@Test
	@DisplayName("ceilings reset on window rollover")
	void ceilingResets() throws Exception {		CaptureService service = new CaptureService(enabled(1000, 1, List.of()));

		for (int index = 0; index < 5 && service.droppedCeiling() == 0; index++) {
			service.offer(event(UUID.randomUUID(), null, null));
		}
		assertThat(service.droppedCeiling()).isEqualTo(1L);
		Thread.sleep(1100L);
		assertThat(service.offer(event(UUID.randomUUID(), null, null))).isTrue();
	}

	@Test
	@DisplayName("concurrent window resets keep exactly one winner")
	void concurrentResetSingleWinner() throws Exception {
		CaptureService service = new CaptureService(enabled(1000, 100_000, List.of()));
		service.offer(event(UUID.randomUUID(), null, null));
		Thread.sleep(1100L);
		CountDownLatch gate = new CountDownLatch(1);
		ExecutorService pool = Executors.newVirtualThreadPerTaskExecutor();
		try {
			var first = pool.submit(() -> {
				try {
					gate.await(10L, TimeUnit.SECONDS);
				} catch (InterruptedException interrupted) {
					Thread.currentThread().interrupt();
				}
				return service.offer(event(UUID.randomUUID(), null, null));
			});
			var second = pool.submit(() -> {
				try {
					gate.await(10L, TimeUnit.SECONDS);
				} catch (InterruptedException interrupted) {
					Thread.currentThread().interrupt();
				}
				return service.offer(event(UUID.randomUUID(), null, null));
			});
			gate.countDown();
			assertThat(first.get(10L, TimeUnit.SECONDS)).isTrue();
			assertThat(second.get(10L, TimeUnit.SECONDS)).isTrue();
		} finally {
			pool.shutdownNow();
		}
		assertThat(service.offered()).isEqualTo(3L);
	}
}
