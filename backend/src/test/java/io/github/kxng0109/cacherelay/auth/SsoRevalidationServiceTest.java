package io.github.kxng0109.cacherelay.auth;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import io.github.kxng0109.cacherelay.auth.backfill.BackfillResult;
import io.github.kxng0109.cacherelay.auth.backfill.SsoBackfillOrchestrator;
import io.github.kxng0109.cacherelay.security.ratelimit.KeyManagementService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@DisplayName("SsoRevalidationService")
class SsoRevalidationServiceTest {

	private static final Instant FIXED_NOW = Instant.parse("2026-09-23T12:00:00Z");

	private SsoRevalidationRepository watermarks;

	private SsoLinkRepository links;

	private UserAccountRepository users;

	private KeyManagementService keys;

	private RefreshService refresh;

	private SsoBackfillProperties backfillProperties;

	private SsoBackfillOrchestrator orchestrator;

	private AuthAuditService audit;

	private SsoRevalidationService service;

	private final UUID userId = UUID.randomUUID();

	@BeforeEach
	void setUp() {
		watermarks = mock(SsoRevalidationRepository.class);
		links = mock(SsoLinkRepository.class);
		users = mock(UserAccountRepository.class);
		keys = mock(KeyManagementService.class);
		refresh = mock(RefreshService.class);
		backfillProperties = mock(SsoBackfillProperties.class);
		orchestrator = mock(SsoBackfillOrchestrator.class);
		audit = mock(AuthAuditService.class);
		service = new SsoRevalidationService(watermarks, links, users, keys, refresh,
				backfillProperties, orchestrator, audit, SsoRevalidationProperties.DEFAULTS,
				Clock.fixed(FIXED_NOW, ZoneOffset.UTC));
		when(backfillProperties.forRegistration("azure")).thenReturn(Optional.of(
				new SsoBackfillProperties.RegistrationBackfill("azure", BackfillMode.ENTRA_GRAPH,
						"tenant-1")));
	}

	@Test
	@DisplayName("disabled switches revoke access and mark inactive")
	void disabledRevokes() {
		SsoRevalidation watermark = new SsoRevalidation(userId, FIXED_NOW.minusSeconds(3600),
				RevalidationStatus.ACTIVE);
		UserAccount account = new UserAccount("op", null, null, false);
		when(watermarks.findUserIdsMissingWatermark(any())).thenReturn(List.of());
		when(watermarks.findDueForRevalidation(any(), any())).thenReturn(List.of(watermark));
		when(links.findByUserId(userId)).thenReturn(List.of(
				new SsoLink(userId, "https://login.example.com/tid-1", "sub-1", "azure")));
		when(orchestrator.revalidate("azure", "sub-1")).thenReturn(
				Optional.of(BackfillResult.forDisabledAccount()));
		when(users.findById(userId)).thenReturn(Optional.of(account));

		service.revalidateBatch(FIXED_NOW.minusSeconds(900), 200);

		assertThat(account.isDisabled()).isTrue();
		assertThat(watermark.getLastStatus()).isEqualTo(RevalidationStatus.INACTIVE);
		verify(users).save(account);
		verify(keys).revokeUserKeys(userId);
		verify(refresh).revokeAll(userId);
		verify(audit).record(AuthAuditService.ACTION_SSO_REVOKE,
				AuthAuditService.SEVERITY_WARN, "op", "/sso/revalidation",
				AuthAuditService.OUTCOME_SUCCESS, null, null);
		verify(watermarks, times(1)).save(watermark);
	}

	@Test
	@DisplayName("enabled accounts mark active without touching access")
	void enabledMarksActive() {
		SsoRevalidation watermark = new SsoRevalidation(userId, FIXED_NOW.minusSeconds(3600),
				null);
		when(watermarks.findUserIdsMissingWatermark(any())).thenReturn(List.of());
		when(watermarks.findDueForRevalidation(any(), any())).thenReturn(List.of(watermark));
		when(links.findByUserId(userId)).thenReturn(List.of(
				new SsoLink(userId, "https://login.example.com/tid-1", "sub-1", "azure")));
		when(orchestrator.revalidate("azure", "sub-1")).thenReturn(Optional.of(
				new BackfillResult(Map.of("group-1", "Engineering"), false)));

		service.revalidateBatch(FIXED_NOW.minusSeconds(900), 200);

		assertThat(watermark.getLastStatus()).isEqualTo(RevalidationStatus.ACTIVE);
		verify(users, never()).save(any());
		verify(keys, never()).revokeUserKeys(any());
		verify(audit, never()).record(any(), any(), any(), any(), any(), any(), any());
		verify(watermarks, times(1)).save(watermark);
	}

	@Test
	@DisplayName("transport failures advance time without revoking")
	void transportFailuresNeverRevoke() {
		SsoRevalidation watermark = new SsoRevalidation(userId, FIXED_NOW.minusSeconds(3600),
				RevalidationStatus.ACTIVE);
		when(watermarks.findUserIdsMissingWatermark(any())).thenReturn(List.of());
		when(watermarks.findDueForRevalidation(any(), any())).thenReturn(List.of(watermark));
		when(links.findByUserId(userId)).thenReturn(List.of(
				new SsoLink(userId, "https://login.example.com/tid-1", "sub-1", "azure")));
		when(orchestrator.revalidate("azure", "sub-1")).thenReturn(Optional.empty());

		service.revalidateBatch(FIXED_NOW.minusSeconds(900), 200);

		assertThat(watermark.getLastVerifiedAt()).isEqualTo(FIXED_NOW);
		assertThat(watermark.getLastStatus()).isEqualTo(RevalidationStatus.ACTIVE);
		verify(users, never()).save(any());
		verify(keys, never()).revokeUserKeys(any());
		verify(watermarks, times(1)).save(watermark);
	}

	@Test
	@DisplayName("uncheckable links only advance time")
	void uncheckableLinksSkipFetch() {
		SsoRevalidation watermark = new SsoRevalidation(userId, FIXED_NOW.minusSeconds(3600),
				null);
		when(watermarks.findUserIdsMissingWatermark(any())).thenReturn(List.of());
		when(watermarks.findDueForRevalidation(any(), any())).thenReturn(List.of(watermark));
		when(links.findByUserId(userId)).thenReturn(List.of(
				new SsoLink(userId, "https://github.com", "op", "github")));

		service.revalidateBatch(FIXED_NOW.minusSeconds(900), 200);

		verify(orchestrator, never()).revalidate(any(), any());
		verify(watermarks, times(1)).save(watermark);
	}

	@Test
	@DisplayName("linkless watermarks only advance time")
	void linklessSkipsFetch() {
		SsoRevalidation watermark = new SsoRevalidation(userId, FIXED_NOW.minusSeconds(3600),
				null);
		when(watermarks.findUserIdsMissingWatermark(any())).thenReturn(List.of());
		when(watermarks.findDueForRevalidation(any(), any())).thenReturn(List.of(watermark));
		when(links.findByUserId(userId)).thenReturn(List.of());

		service.revalidateBatch(FIXED_NOW.minusSeconds(900), 200);

		verify(orchestrator, never()).revalidate(any(), any());
		verify(watermarks, times(1)).save(watermark);
	}

	@Test
	@DisplayName("missing watermarks self-seed at the epoch")
	void missingWatermarksSeed() {
		when(watermarks.findUserIdsMissingWatermark(any())).thenReturn(List.of(userId));
		when(watermarks.findDueForRevalidation(any(), any())).thenReturn(List.of());

		service.revalidateBatch(FIXED_NOW.minusSeconds(900), 200);

		verify(watermarks, times(1)).save(any(SsoRevalidation.class));
		verify(orchestrator, never()).revalidate(any(), any());
	}

	@Test
	@DisplayName("disabled switch cleans up missing accounts")
	void missingAccountCleanup() {
		SsoRevalidation watermark = new SsoRevalidation(userId, FIXED_NOW.minusSeconds(3600),
				RevalidationStatus.ACTIVE);
		when(watermarks.findUserIdsMissingWatermark(any())).thenReturn(List.of());
		when(watermarks.findDueForRevalidation(any(), any())).thenReturn(List.of(watermark));
		when(links.findByUserId(userId)).thenReturn(List.of(
				new SsoLink(userId, "https://login.example.com/tid-1", "sub-1", "azure")));
		when(orchestrator.revalidate("azure", "sub-1")).thenReturn(
				Optional.of(BackfillResult.forDisabledAccount()));
		when(users.findById(userId)).thenReturn(Optional.empty());

		service.revalidateBatch(FIXED_NOW.minusSeconds(900), 200);

		verify(watermarks, times(1)).deleteById(userId);
		verify(keys, never()).revokeUserKeys(any());
	}

	@Test
	@DisplayName("disabled switch respects the master switch")
	void disabledMasterSwitch() {		SsoRevalidationService stopped = new SsoRevalidationService(watermarks, links, users,
				keys, refresh, backfillProperties, orchestrator, audit,
				new SsoRevalidationProperties(false, "0 */15 * * * *", "0 0 2 * * *", 200, 15,
						24),
				Clock.fixed(FIXED_NOW, ZoneOffset.UTC));

		stopped.revalidateBatch(FIXED_NOW.minusSeconds(900), 200);

		verify(watermarks, never()).findUserIdsMissingWatermark(any());
		verify(watermarks, never()).findDueForRevalidation(any(), any());
	}

	@Test
	@DisplayName("scheduled wrappers delegate with configured cutoffs")
	void scheduledWrappersDelegate() {
		when(watermarks.findUserIdsMissingWatermark(any())).thenReturn(List.of());
		when(watermarks.findDueForRevalidation(any(), any())).thenReturn(List.of());

		service.sweepHot();
		service.sweepCold();

		verify(watermarks, times(2)).findUserIdsMissingWatermark(any());
		verify(watermarks, times(2)).findDueForRevalidation(any(), any());
	}

	@Test
	@DisplayName("github and none modes skip fetching without touching watermarks")
	void unserviceableModesSkip() {
		SsoBackfillProperties modes = new SsoBackfillProperties(List.of(
				new SsoBackfillProperties.RegistrationBackfill("github", BackfillMode.GITHUB_API,
						"acme-corp"),
				new SsoBackfillProperties.RegistrationBackfill("plain", BackfillMode.NONE,
						"scope")));
		SsoRevalidationService service = new SsoRevalidationService(watermarks, links, users,
				keys, refresh, modes, orchestrator, audit, SsoRevalidationProperties.DEFAULTS,
				Clock.fixed(FIXED_NOW, ZoneOffset.UTC));
		SsoRevalidation first = new SsoRevalidation(userId, FIXED_NOW.minusSeconds(3600), null);
		SsoRevalidation second = new SsoRevalidation(UUID.randomUUID(),
				FIXED_NOW.minusSeconds(3600), null);
		when(watermarks.findUserIdsMissingWatermark(any())).thenReturn(List.of());
		when(watermarks.findDueForRevalidation(any(), any()))
				.thenReturn(List.of(first, second));
		when(links.findByUserId(userId)).thenReturn(List.of(
				new SsoLink(userId, "https://github.com", "op", "github")));
		when(links.findByUserId(second.getUserId())).thenReturn(List.of(
				new SsoLink(second.getUserId(), "https://example.invalid", "sub-9", "plain")));

		service.revalidateBatch(FIXED_NOW.minusSeconds(900), 200);

		verify(orchestrator, never()).revalidate(any(), any());
		verify(watermarks, times(2)).save(any(SsoRevalidation.class));
	}
}
