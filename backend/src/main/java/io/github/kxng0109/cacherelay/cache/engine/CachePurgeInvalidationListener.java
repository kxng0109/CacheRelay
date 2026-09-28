package io.github.kxng0109.cacherelay.cache.engine;

import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.concurrent.atomic.AtomicBoolean;

import javax.sql.DataSource;

import lombok.extern.slf4j.Slf4j;
import org.postgresql.PGConnection;
import org.postgresql.PGNotification;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/**
 * Flushes the process-local L0 on remote administrative purges. Holds one
 * dedicated Postgres connection running {@code LISTEN} on a daemon virtual
 * thread; any notification triggers exactly one local flush per batch.
 *
 * <p>Tenant-scoped purges flush the whole local window: L0 carries no tenant
 * index, and over-invalidation only costs a bounded refill from L1 while
 * under-invalidation keeps serving purged content. Holds one pool slot for
 * its lifetime (documented tradeoff; the pool sizes for it). Reconnects with
 * backoff on any failure; a dead listener degrades to L0-TTL staleness, never
 * to unbounded divergence.
 */
@Slf4j
@Component
public class CachePurgeInvalidationListener {

	private final DataSource dataSource;

	private final CacheRelayCacheService cacheService;

	private final AtomicBoolean running = new AtomicBoolean(false);

	private volatile Thread listenerThread;

	/**
	 * Creates the listener.
	 *
	 * @param dataSource   source for the dedicated {@code LISTEN} connection, never {@code null}
	 * @param cacheService local cache service flushed on remote purges, never {@code null}
	 */
	public CachePurgeInvalidationListener(DataSource dataSource, CacheRelayCacheService cacheService) {
		this.dataSource = dataSource;
		this.cacheService = cacheService;
	}

	/**
	 * Starts the daemon listener once the application is ready.
	 */
	@EventListener(ApplicationReadyEvent.class)
	public void start() {
		if (running.compareAndSet(false, true)) {
			listenerThread = Thread.ofVirtual().name("purge-invalidation-listener").start(this::listen);
		}
	}

	/**
	 * Routes one batch of notifications to a single local flush.
	 *
	 * @param cacheService  local cache service to flush, never {@code null}
	 * @param notifications batch from {@code getNotifications}, ignored when {@code null}
	 */
	static void dispatch(CacheRelayCacheService cacheService, PGNotification[] notifications) {
		if (notifications == null || notifications.length == 0) {
			return;
		}
		cacheService.purgeLocalCache();
	}

	private void listen() {
		while (running.get()) {
			try (Connection connection = dataSource.getConnection()) {
				PGConnection pg = connection.unwrap(PGConnection.class);
				try (Statement statement = connection.createStatement()) {
					statement.execute("LISTEN " + CachePurgeNotifier.CHANNEL);
				}
				while (running.get()) {
					dispatch(cacheService, pg.getNotifications(10_000));
				}
			} catch (SQLException | RuntimeException ex) {
				if (!running.get()) {
					return;
				}
				log.warn("Purge invalidation listener reconnecting: {}", ex.getMessage());
				try {
					Thread.sleep(5_000);
				} catch (InterruptedException interrupted) {
					Thread.currentThread().interrupt();
					return;
				}
			}
		}
	}

	/**
	 * Stops the loop; visible for tests (the blocked connection unwinds on close).
	 */
	void stop() {
		running.set(false);
		Thread thread = listenerThread;
		if (thread != null) {
			thread.interrupt();
		}
	}
}
