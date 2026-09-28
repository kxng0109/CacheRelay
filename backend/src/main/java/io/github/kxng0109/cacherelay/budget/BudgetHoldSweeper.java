package io.github.kxng0109.cacherelay.budget;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.concurrent.atomic.AtomicLong;

import javax.sql.DataSource;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Expires due settlement holds: crashed streams, lapsed abort grace, and records already gone. Single-flight
 * across pods via a transaction-scoped PostgreSQL advisory lock ({@code pg_try_advisory_xact_lock}): commit or
 * rollback releases it implicitly, so a failed unlock can never leak the lock onto a pooled connection and wedge
 * the sweep (FIN-B19).
 *
 * <p>Idempotent by construction: expiry runs the settle script's expire branch (settled-flag claim makes a racing
 * late settle a replay), and gap rows are insert-only. A tick that fails partway re-arms entries for the next
 * tick; nothing is ever dropped.</p>
 */
@Component
public class BudgetHoldSweeper {

	private static final Logger log = LoggerFactory.getLogger(BudgetHoldSweeper.class);

	static final String LOCK_NAME = "cacherelay-budget-hold-sweeper";

	private final DataSource dataSource;

	private final BudgetSettlement settlement;

	private final BudgetSettlementProperties properties;

	private final AtomicLong lastTickMillis = new AtomicLong(0);

	private volatile MeterRegistry meterRegistry = new SimpleMeterRegistry();

	public BudgetHoldSweeper(DataSource dataSource, BudgetSettlement settlement,
	                         BudgetSettlementProperties properties) {
		this.dataSource = dataSource;
		this.settlement = settlement;
		this.properties = properties;
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
					.description("Last successful hold-sweeper tick (epoch seconds)")
					.tag("job", "budget-hold-sweeper")
					.register(meterRegistry);
		}
	}

	@Scheduled(fixedDelayString = "${gateway.budget.settlement.sweep-interval:30s}")
	public void sweep() {
		if (!properties.enabled()) {
			return;
		}
		try (Connection connection = dataSource.getConnection()) {
			connection.setAutoCommit(false);
			if (!AdvisoryLock.tryLockXact(connection, LOCK_NAME)) {
				connection.rollback();
				return;
			}
			try {
				int budget = properties.sweeperBatch();
				for (String holdHashKey : settlement.dueHoldKeys(budget)) {
					try {
						settlement.expireDueHold(holdHashKey);
					} catch (RuntimeException ex) {
						log.warn("Hold expiry failed for {}; continuing sweep", holdHashKey);
					}
					if (--budget <= 0) {
						break;
					}
				}
				connection.commit();
				lastTickMillis.set(System.currentTimeMillis());
			} catch (RuntimeException ex) {
				rollbackQuietly(connection);
				throw ex;
			}
		} catch (SQLException ex) {
			log.warn("Hold sweeper tick skipped (datasource unavailable)");
		}
	}

	private static void rollbackQuietly(Connection connection) {
		try {
			connection.rollback();
		} catch (SQLException ignored) {
		}
	}
}
