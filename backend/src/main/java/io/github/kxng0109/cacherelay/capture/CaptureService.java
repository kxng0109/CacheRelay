package io.github.kxng0109.cacherelay.capture;

import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.atomic.AtomicLong;

import org.springframework.stereotype.Service;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * Hot-path capture gate: decides per request in nanoseconds and hands
 * accepted events to the background writer without blocking.
 *
 * <p>Decision order: master switch (single volatile read, zero work when
 * off), owner/key allowlist (full fidelity), deterministic request-id hash
 * sampling, then a fixed-window persist ceiling. Accepted events offer once
 * to a bounded queue; a full queue drops with a counter, never blocks the
 * Tomcat worker. Redaction, serialization, and file I/O all happen on the
 * writer thread.</p>
 */
@Service
public class CaptureService {

	private final CaptureProperties properties;

	private final BlockingQueue<CaptureEvent> queue;

	private final AtomicLong offered = new AtomicLong();

	private final AtomicLong droppedFull = new AtomicLong();

	private final AtomicLong droppedCeiling = new AtomicLong();

	private final AtomicLong windowStartNanos = new AtomicLong(System.nanoTime());

	private final AtomicLong windowCount = new AtomicLong();

	/**
	 * Creates the service.
	 *
	 * @param properties capture ceilings, never {@code null}
	 */
	@Autowired
	public CaptureService(CaptureProperties properties) {
		this(properties, new ArrayBlockingQueue<>(8192));
	}

	/**
	 * Creates the service with an explicit queue (tests).
	 *
	 * @param properties capture ceilings, never {@code null}
	 * @param queue      handoff queue, never {@code null}
	 */
	CaptureService(CaptureProperties properties, BlockingQueue<CaptureEvent> queue) {
		this.properties = properties;
		this.queue = queue;
	}

	/**
	 * Offers one completed request for capture. Never throws, never blocks.
	 *
	 * @param event completed request, never {@code null}
	 * @return {@code true} when the writer will persist it
	 */
	public boolean offer(CaptureEvent event) {
		if (!shouldCapture(event.ownerId(), event.keyHash(), event.requestId())) {
			return false;
		}
		return capture(event);
	}

	/**
	 * Decides capture without enqueuing: master switch, allowlist, then
	 * deterministic request-id hash sampling. Pure and allocation-free on
	 * the negative path.
	 *
	 * @param ownerId   owning tenant, or {@code null}
	 * @param keyHash   calling key hash, or {@code null}
	 * @param requestId stable request id, never {@code null}
	 * @return {@code true} when the request qualifies
	 */
	public boolean shouldCapture(String ownerId, String keyHash, UUID requestId) {
		if (!properties.enabled()) {
			return false;
		}
		Optional<CaptureProperties.CaptureRule> rule = properties.ruleFor(ownerId);
		if (rule.isEmpty()) {
			rule = properties.ruleFor(keyHash);
		}
		return rule.isPresent() || sampled(requestId);
	}

	/**
	 * Enqueues a qualified event under the persist ceiling. Never throws,
	 * never blocks.
	 *
	 * @param event qualified event, never {@code null}
	 * @return {@code true} when the writer will persist it
	 */
	public boolean capture(CaptureEvent event) {
		if (!withinCeiling()) {
			droppedCeiling.incrementAndGet();
			return false;
		}
		offered.incrementAndGet();
		if (!queue.offer(event)) {
			droppedFull.incrementAndGet();
			return false;
		}
		return true;
	}

	/**
	 * Deterministic per-mille sampling on the stable request id, so prompt,
	 * output, and ledger rows for one request agree without coordination.
	 *
	 * @param requestId stable request id, never {@code null}
	 * @return {@code true} when the request is sampled
	 */
	boolean sampled(UUID requestId) {
		if (properties.samplePerMille() <= 0) {
			return false;
		}
		if (properties.samplePerMille() >= 1000) {
			return true;
		}
		long hash = requestId.getLeastSignificantBits() ^ requestId.getMostSignificantBits();
		return Math.floorMod(hash, 1000L) < properties.samplePerMille();
	}

	private boolean withinCeiling() {
		long now = System.nanoTime();
		long start = windowStartNanos.get();
		if (now - start >= 1_000_000_000L
				&& windowStartNanos.compareAndSet(start, now)) {
			windowCount.set(0L);
		}
		return windowCount.incrementAndGet() <= properties.maxPersistsPerSecond();
	}

	/**
	 * Bounds one payload for queue memory: over-long fields cut with no marker
	 * (the writer flags truncation on its own copy).
	 *
	 * @param text payload, never {@code null}
	 * @return bounded payload
	 */
	public String truncate(String text) {
		if (text.length() <= properties.maxCharsPerField()) {
			return text;
		}
		return text.substring(0, properties.maxCharsPerField());
	}

	/**
	 * @return handoff queue for the writer thread and tests, never {@code null}
	 */
	public BlockingQueue<CaptureEvent> queue() {
		return queue;
	}

	/**
	 * @return accepted offers so far
	 */
	public long offered() {
		return offered.get();
	}

	/**
	 * @return drops on a full queue so far
	 */
	public long droppedFull() {
		return droppedFull.get();
	}

	/**
	 * @return drops on the persist ceiling so far
	 */
	public long droppedCeiling() {
		return droppedCeiling.get();
	}
}
