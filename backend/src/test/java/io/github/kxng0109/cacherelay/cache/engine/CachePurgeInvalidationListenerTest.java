package io.github.kxng0109.cacherelay.cache.engine;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.postgresql.PGNotification;

/**
 * ADM-B07 dispatch: remote purges flush the local L0.
 */
@DisplayName("CachePurgeInvalidationListener dispatch")
class CachePurgeInvalidationListenerTest {

	private static PGNotification payload(String parameter) {
		PGNotification notification = mock(PGNotification.class);
		when(notification.getParameter()).thenReturn(parameter);
		return notification;
	}

	@Test
	@DisplayName("purge notification flushes the local cache")
	void purgeNotificationFlushesLocal() {
		CacheRelayCacheService cacheService = mock(CacheRelayCacheService.class);

		CachePurgeInvalidationListener.dispatch(
				cacheService, new PGNotification[] {payload("ALL")});

		verify(cacheService).purgeLocalCache();
	}

	@Test
	@DisplayName("tenant purge still flushes the local window")
	void tenantPurgeFlushesLocal() {
		CacheRelayCacheService cacheService = mock(CacheRelayCacheService.class);

		CachePurgeInvalidationListener.dispatch(
				cacheService, new PGNotification[] {payload("tenant:tenant-corp")});

		verify(cacheService).purgeLocalCache();
	}

	@Test
	@DisplayName("null batch is a no-op")
	void nullBatchNoOp() {
		CacheRelayCacheService cacheService = mock(CacheRelayCacheService.class);

		CachePurgeInvalidationListener.dispatch(cacheService, null);

		verifyNoInteractions(cacheService);
	}
}
