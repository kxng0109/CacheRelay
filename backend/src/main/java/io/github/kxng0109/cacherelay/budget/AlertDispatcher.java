package io.github.kxng0109.cacherelay.budget;

import java.sql.Connection;
import java.sql.SQLException;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

import javax.sql.DataSource;

import io.github.kxng0109.cacherelay.notify.AlertDeliveredEvent;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Delivers {@code alert_events} outbox rows to Alertmanager. Claims due rows with {@code SELECT ... FOR UPDATE
 * SKIP LOCKED} (concurrent dispatchers partition work), POSTs each as a single-element v2 array batch, and
 * marks SENT. Retryable failures back off with Full Jitter; rows past the attempt ceiling are marked FAILED
 * (poison, kept for audit); terminal rejections die immediately. Single-flight per tick via advisory lock.
 */
@Component
public class AlertDispatcher {

	static final String LOCK_NAME = "cacherelay-alert-dispatcher";

	static final int MAX_ATTEMPTS = 10;

	private static final Logger log = LoggerFactory.getLogger(AlertDispatcher.class);

	private final AlertEventRepository outbox;

	private final AlertmanagerClient client;

	private final BudgetDetectionProperties properties;

	private final DataSource dataSource;

	private final ApplicationEventPublisher eventPublisher;

	private final AtomicLong lastTickMillis = new AtomicLong(0);

	private volatile MeterRegistry meterRegistry = new SimpleMeterRegistry();

	private volatile @Nullable TransactionTemplate claimTemplate;

	/**
	 * Upper bound for one tick's send phase: rows unprocessed when it lapses
	 * re-arm for the next tick instead of holding the batch open. Five minutes
	 * covers a full batch at worst-case per-send cost; the tick is fixed-delay
	 * so an overrun only delays the next tick, never overlaps it.
	 */
	static final long SEND_BATCH_DEADLINE_SECONDS = 300L;

	public AlertDispatcher(AlertEventRepository outbox, AlertmanagerClient client,
	                       BudgetDetectionProperties properties, DataSource dataSource,
	                       ApplicationEventPublisher eventPublisher) {
		this.outbox = outbox;
		this.client = client;
		this.properties = properties;
		this.dataSource = dataSource;
		this.eventPublisher = eventPublisher;
	}

	/**
	 * Wires the registry for the last-successful-tick gauge. Optional on
	 * purpose: without it the tick still runs, only unobserved.
	 *
	 * @param meterRegistry registry hosting the tick gauge, if available
	 */
	@Autowired
	public void setMeterRegistry(@Nullable MeterRegistry meterRegistry) {
		if (meterRegistry != null) {
			this.meterRegistry = meterRegistry;
			Gauge.builder("cacherelay.job.last_tick_seconds", lastTickMillis,
							value -> value.get() / 1000.0)
					.description("Last successful alert-dispatch tick (epoch seconds)")
					.tag("job", "alert-dispatcher")
					.register(meterRegistry);
		}
	}

	/**
	 * Wires the transaction template for outbox claim and outcome persistence.
	 * Forced to {@code REQUIRES_NEW}: the claim must hold row locks only for
	 * the claim itself (FIN-B16 — the old method-level {@code @Transactional}
	 * was dead by self-invocation), and outcome rows must persist even when
	 * the surrounding tick has no ambient transaction. Optional on purpose:
	 * without it (unit-test contexts) repository calls run directly.
	 *
	 * @param claimTemplate template for claim/outcome transactions, if available
	 */
	@Autowired
	public void setClaimTemplate(@Nullable TransactionTemplate claimTemplate) {
		if (claimTemplate != null) {
			claimTemplate.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
			this.claimTemplate = claimTemplate;
		}
	}

	@Scheduled(fixedDelayString = "${gateway.budget.detection.dispatch-interval:30s}")
	public void dispatch() {
		if (!properties.enabled()) {
			return;
		}
		Instant now = Instant.now();
		List<AlertEvent> due;
		try (Connection connection = dataSource.getConnection()) {
			connection.setAutoCommit(false);
			if (!AdvisoryLock.tryLockXact(connection, LOCK_NAME)) {
				connection.rollback();
				return;
			}
			try {
				due = claimDue(now);
				connection.commit();
			} catch (RuntimeException ex) {
				rollbackQuietly(connection);
				throw ex;
			}
		} catch (SQLException ex) {
			log.warn("Alert dispatch tick skipped (datasource unavailable)");
			return;
		} catch (RuntimeException ex) {
			log.warn("Alert dispatch tick failed; next interval retries");
			return;
		}
		// FIN-B18: the advisory lock is released before any HTTP leaves —
		// sends never hold fleet coordination, and one slow target cannot
		// wedge every pod behind the same lock.
		sendBatch(due, now.plusSeconds(SEND_BATCH_DEADLINE_SECONDS));
		lastTickMillis.set(System.currentTimeMillis());
	}

	private static void rollbackQuietly(Connection connection) {
		try {
			connection.rollback();
		} catch (SQLException ignored) {
		}
	}

	/**
	 * Claims due rows in a short dedicated transaction (row locks held only
	 * for the claim, never across the send phase).
	 *
	 * @param now claim horizon
	 * @return due rows, oldest first
	 */
	private List<AlertEvent> claimDue(Instant now) {
		TransactionTemplate template = this.claimTemplate;
		if (template != null) {
			List<AlertEvent> claimed =
					template.execute(status -> outbox.claimDue(now, properties.dispatchBatch()));
			return claimed == null ? List.of() : claimed;
		}
		return outbox.claimDue(now, properties.dispatchBatch());
	}

	/**
	 * Sends one claimed batch outside the advisory lock. Rows unprocessed when
	 * the deadline lapses re-arm for the next tick instead of holding the
	 * batch open. Visible for tests.
	 *
	 * @param due      claimed rows in dispatch order
	 * @param deadline batch horizon; rows past it back off without sending
	 */
	void sendBatch(List<AlertEvent> due, Instant deadline) {
		for (AlertEvent event : due) {
			if (Instant.now().isAfter(deadline)) {
				event.backoff(deadline);
				persistOutcome(event, false);
				continue;
			}
			try {
				Map<String, Object> payload = Map.of(
						"labels", Map.of(
								"alertname", "CacheRelayBudget" + capitalize(event.getDetector()),
								"scope", event.getScope(),
								"detector", event.getDetector(),
								"severity", event.getSeverity()),
						"annotations", Map.of("summary", event.getPayload()),
						"startsAt", event.getStartsAt().toString());
				AlertmanagerClient.PostResult result = client.post(List.of(payload));
				if (result.sent()) {
					event.markSent();
				} else if (result.skipped()) {
					event.markSkipped();
				} else if (!result.retryable() || event.getAttempts() + 1 >= MAX_ATTEMPTS) {
					event.markDead();
				} else {
					event.backoff(Instant.now().plusMillis(
							AlertmanagerClient.backoffDelayMillis(event.getAttempts() + 1)));
				}
				persistOutcome(event, true);
			} catch (RuntimeException ex) {
				log.warn("Alert delivery failed for {}; leaving for retry", event.getDedupeSha());
			}
		}
	}

	/**
	 * Persists one send outcome (and publishes the delivered event inside the
	 * same transaction, so the AFTER_COMMIT fanout only fires for rows that
	 * actually committed).
	 *
	 * @param event   row to persist
	 * @param publish whether a SENT row may publish its delivered event
	 */
	private void persistOutcome(AlertEvent event, boolean publish) {
		TransactionTemplate template = this.claimTemplate;
		if (template != null) {
			template.executeWithoutResult(status -> saveAndMaybePublish(event, publish));
		} else {
			saveAndMaybePublish(event, publish);
		}
	}

	private void saveAndMaybePublish(AlertEvent event, boolean publish) {
		outbox.save(event);
		if (publish && "SENT".equals(event.getStatus())) {
			// Delivered after this transaction commits (listener phase), so a rolled-back send
			// never notifies.
			eventPublisher.publishEvent(new AlertDeliveredEvent(
					event.getDedupeSha(), event.getScope(), event.getDetector(),
					event.getSeverity(), event.getStartsAt(),
					event.getValueText() == null ? "" : event.getValueText(),
					event.getMonth() == null ? "" : event.getMonth()));
		}
	}

	private static String capitalize(String detector) {
		if (detector == null || detector.isEmpty()) {
			return "Unknown";
		}
		StringBuilder name = new StringBuilder();
		boolean upper = true;
		for (char c : detector.toCharArray()) {
			if (c == '_') {
				upper = true;
			} else if (upper) {
				name.append(Character.toUpperCase(c));
				upper = false;
			} else {
				name.append(c);
			}
		}
		return name.toString();
	}
}
