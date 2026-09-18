package io.github.kxng0109.cacherelay.auth;

import java.util.Map;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit tests for auth hygiene scheduling.
 */
@DisplayName("AuthJanitor")
class AuthJanitorTest {

	@Test
	@DisplayName("purge clears expired rows and retention-aged events")
	void purgeClears() {
		RefreshTokenRepository refresh = mock(RefreshTokenRepository.class);
		InviteTokenRepository invites = mock(InviteTokenRepository.class);
		AuthAuditRepository audit = mock(AuthAuditRepository.class);
		when(refresh.deleteExpired(any(), any())).thenReturn(3);
		when(invites.deleteExpiredUnconsumed(any())).thenReturn(1);
		when(audit.deleteBefore(any())).thenReturn(7);
		AuthJanitor janitor = new AuthJanitor(refresh, invites, audit,
				AuthProperties.defaults());

		janitor.purge();

		verify(refresh).deleteExpired(any(), any());
		verify(invites).deleteExpiredUnconsumed(any());
		verify(audit).deleteBefore(any());
	}

	@Test
	@DisplayName("jurisdictional overrides extend retention instead of replacing it")
	void overridesExtendRetention() {
		RefreshTokenRepository refresh = mock(RefreshTokenRepository.class);
		InviteTokenRepository invites = mock(InviteTokenRepository.class);
		AuthAuditRepository audit = mock(AuthAuditRepository.class);
		AuthProperties properties = new AuthProperties(null, null, null, null, null, null,
				null, null, 				null, null, null, 180, Map.of("IN", 365), 5, null,
				null);
		AuthJanitor janitor = new AuthJanitor(refresh, invites, audit, properties);

		janitor.purge();

		verify(audit).deleteBefore(any());
	}
}
