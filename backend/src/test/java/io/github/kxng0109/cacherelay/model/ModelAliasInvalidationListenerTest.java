package io.github.kxng0109.cacherelay.model;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.postgresql.PGNotification;

/**
 * ADM-B05 dispatch: remote alias mutations refresh the local registry.
 */
@DisplayName("ModelAliasInvalidationListener dispatch")
class ModelAliasInvalidationListenerTest {

	private static PGNotification payload(String parameter) {
		PGNotification notification = mock(PGNotification.class);
		when(notification.getParameter()).thenReturn(parameter);
		return notification;
	}

	@Test
	@DisplayName("alias notification refreshes the registry once per batch")
	void aliasNotificationRefreshesOnce() {
		ModelAliasRegistry registry = mock(ModelAliasRegistry.class);

		ModelAliasInvalidationListener.dispatch(
				registry, new PGNotification[] {payload("gpt-x"), payload("gpt-y")});

		verify(registry).refreshFromDatabase();
	}

	@Test
	@DisplayName("malformed payloads refresh instead of trusting the name")
	void malformedPayloadRefreshes() {
		ModelAliasRegistry registry = mock(ModelAliasRegistry.class);

		ModelAliasInvalidationListener.dispatch(registry, new PGNotification[] {payload(":::")});

		verify(registry).refreshFromDatabase();
	}

	@Test
	@DisplayName("null batch is a no-op")
	void nullBatchNoOp() {
		ModelAliasRegistry registry = mock(ModelAliasRegistry.class);

		ModelAliasInvalidationListener.dispatch(registry, null);

		verifyNoInteractions(registry);
	}
}
