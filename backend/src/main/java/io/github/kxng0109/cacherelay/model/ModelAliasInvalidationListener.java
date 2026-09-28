package io.github.kxng0109.cacherelay.model;

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
 * Rebuilds the process-local alias registry on remote mutations. Holds one
 * dedicated Postgres connection running {@code LISTEN} on a daemon virtual
 * thread; any notification triggers exactly one registry refresh per batch —
 * the payload name is informational only, because a full rebuild is the only
 * refresh that cannot miss a concurrent second mutation.
 *
 * <p>Holds one pool slot for its lifetime (documented tradeoff; the pool sizes
 * for it). Reconnects with backoff on any failure; a dead listener degrades
 * to restart-time convergence, never to a wedged catalog.
 */
@Slf4j
@Component
public class ModelAliasInvalidationListener {

	private final DataSource dataSource;

	private final ModelAliasRegistry registry;

	private final AtomicBoolean running = new AtomicBoolean(false);

	private volatile Thread listenerThread;

	/**
	 * Creates the listener.
	 *
	 * @param dataSource source for the dedicated {@code LISTEN} connection, never {@code null}
	 * @param registry   local registry refreshed on remote mutations, never {@code null}
	 */
	public ModelAliasInvalidationListener(DataSource dataSource, ModelAliasRegistry registry) {
		this.dataSource = dataSource;
		this.registry = registry;
	}

	/**
	 * Starts the daemon listener once the application is ready.
	 */
	@EventListener(ApplicationReadyEvent.class)
	public void start() {
		if (running.compareAndSet(false, true)) {
			listenerThread = Thread.ofVirtual().name("alias-invalidation-listener").start(this::listen);
		}
	}

	/**
	 * Routes one batch of notifications to a single registry refresh.
	 *
	 * @param registry      local registry to refresh, never {@code null}
	 * @param notifications batch from {@code getNotifications}, ignored when {@code null}
	 */
	static void dispatch(ModelAliasRegistry registry, PGNotification[] notifications) {
		if (notifications == null || notifications.length == 0) {
			return;
		}
		registry.refreshFromDatabase();
	}

	private void listen() {
		while (running.get()) {
			try (Connection connection = dataSource.getConnection()) {
				PGConnection pg = connection.unwrap(PGConnection.class);
				try (Statement statement = connection.createStatement()) {
					statement.execute("LISTEN " + ModelAliasChangeNotifier.CHANNEL);
				}
				while (running.get()) {
					dispatch(registry, pg.getNotifications(10_000));
				}
			} catch (SQLException | RuntimeException ex) {
				if (!running.get()) {
					return;
				}
				log.warn("Alias invalidation listener reconnecting: {}", ex.getMessage());
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
