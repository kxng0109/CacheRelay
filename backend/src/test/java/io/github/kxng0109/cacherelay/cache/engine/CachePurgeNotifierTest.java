package io.github.kxng0109.cacherelay.cache.engine;

import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * ADM-B07 fan-out: local purges notify every pod.
 */
@DisplayName("CachePurgeNotifier")
class CachePurgeNotifierTest {

	@Test
	@DisplayName("purge notifies the purge channel with the scope")
	void purgeNotifiesChannel() {
		JdbcTemplate jdbc = mock(JdbcTemplate.class);
		CachePurgeNotifier notifier = new CachePurgeNotifier(jdbc);

		notifier.notifyPurge("ALL");

		verify(jdbc).execute(eq("SELECT pg_notify('cache_purge_all', 'ALL')"));
	}

	@Test
	@DisplayName("fan-out failure never breaks the purge")
	void fanOutFailureDegrades() {
		JdbcTemplate jdbc = mock(JdbcTemplate.class);
		doThrow(new RuntimeException("db down")).when(jdbc).execute(eq("SELECT pg_notify('cache_purge_all', 'ALL')"));
		CachePurgeNotifier notifier = new CachePurgeNotifier(jdbc);

		notifier.notifyPurge("ALL");

		verify(jdbc).execute(eq("SELECT pg_notify('cache_purge_all', 'ALL')"));
	}
}
