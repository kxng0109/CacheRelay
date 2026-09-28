package io.github.kxng0109.cacherelay.model;

import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * Fans model-alias mutations out to every gateway instance via Postgres
 * {@code NOTIFY}. Each pod's {@link ModelAliasInvalidationListener} rebuilds
 * its registry on receipt, so a remotely created/changed/deleted alias takes
 * effect fleet-wide within milliseconds instead of waiting for a restart.
 *
 * <p>Best-effort by design: the mutating pod always rebuilds locally first,
 * so its own reads are correct even when fan-out fails; remotes without a
 * listener still converge on restart. Failures log and continue. The channel
 * payload is the alias name, which is a validated lowercase slug
 * ({@code [a-z0-9][a-z0-9._-]{0,63}}) by the time it reaches here, so no
 * quote characters can reach the statement.</p>
 */
@Slf4j
@Component
public class ModelAliasChangeNotifier {

	static final String CHANNEL = "model_alias_changed";

	private final JdbcTemplate jdbc;

	/**
	 * Creates the notifier.
	 *
	 * @param jdbc template for the {@code pg_notify} statement, never {@code null}
	 */
	public ModelAliasChangeNotifier(JdbcTemplate jdbc) {
		this.jdbc = jdbc;
	}

	/**
	 * Notifies every pod that the named alias changed.
	 *
	 * @param name validated alias slug that was created, updated, or deleted
	 */
	public void notifyChanged(String name) {
		try {
			jdbc.execute("SELECT pg_notify('" + CHANNEL + "', '" + name + "')");
		} catch (RuntimeException ex) {
			log.warn("Model alias fan-out failed, remotes heal on restart: {}", ex.getMessage());
		}
	}
}
