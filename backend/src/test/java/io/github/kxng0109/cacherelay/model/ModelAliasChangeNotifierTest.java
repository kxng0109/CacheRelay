package io.github.kxng0109.cacherelay.model;

import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * ADM-B05 fan-out: local alias mutations notify every pod.
 */
@DisplayName("ModelAliasChangeNotifier")
class ModelAliasChangeNotifierTest {

	@Test
	@DisplayName("mutation notifies the alias channel with the name")
	void mutationNotifiesChannel() {
		JdbcTemplate jdbc = mock(JdbcTemplate.class);
		ModelAliasChangeNotifier notifier = new ModelAliasChangeNotifier(jdbc);

		notifier.notifyChanged("gpt-x");

		verify(jdbc).execute(eq("SELECT pg_notify('model_alias_changed', 'gpt-x')"));
	}

	@Test
	@DisplayName("fan-out failure never breaks the mutation")
	void fanOutFailureDegrades() {
		JdbcTemplate jdbc = mock(JdbcTemplate.class);
		doThrow(new RuntimeException("db down")).when(jdbc).execute(eq("SELECT pg_notify('model_alias_changed', 'gpt-x')"));
		ModelAliasChangeNotifier notifier = new ModelAliasChangeNotifier(jdbc);

		notifier.notifyChanged("gpt-x");

		verify(jdbc).execute(eq("SELECT pg_notify('model_alias_changed', 'gpt-x')"));
	}
}
