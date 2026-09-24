package io.github.kxng0109.cacherelay.me;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import io.github.kxng0109.cacherelay.admin.dto.LedgerSummaryResponse;
import io.github.kxng0109.cacherelay.auth.JwtService;
import io.github.kxng0109.cacherelay.auth.UserAccount;
import io.github.kxng0109.cacherelay.auth.UserAccountRepository;
import io.github.kxng0109.cacherelay.ledger.DashboardService;
import io.github.kxng0109.cacherelay.ledger.DashboardView;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.server.ResponseStatusException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Personal dashboard surface: session-authenticated users read only their own
 * usage, with freshness coordinates on headers and no secrets anywhere.
 */
@DisplayName("MeDashboardController")
class MeDashboardControllerTest {

	private final DashboardService dashboards = mock(DashboardService.class);
	private final JwtService sessions = mock(JwtService.class);
	private final UserAccountRepository users = mock(UserAccountRepository.class);
	private final MeDashboardController controller = new MeDashboardController(dashboards, sessions, users);
	private final UUID userId = UUID.randomUUID();

	private static final String SESSION = "session-jwt";

	private Jwt sessionFor(UUID user) {
		Instant now = Instant.now();
		return new Jwt("session", now, now.plusSeconds(600),
				Map.of("alg", "HS256"), Map.of("sub", user.toString()));
	}

	private void stubSession() {
		when(sessions.validate(SESSION)).thenReturn(sessionFor(userId));
		UserAccount account = new UserAccount("local", null, null, false);
		when(users.findById(userId)).thenReturn(Optional.of(account));
	}

	private DashboardView view() {
		LedgerSummaryResponse summary = new LedgerSummaryResponse(10L, 1000L, 500L, 1500L,
				14_000L, BigDecimal.valueOf(14_000, 6), 120.0, List.of(), List.of(), List.of());
		return new DashboardView(summary, Instant.parse("2026-09-23T12:00:00Z"),
				Instant.parse("2026-09-23T12:00:00Z"));
	}

	private String bearer() {
		return "Bearer " + SESSION;
	}

	@Test
	@DisplayName("returns the caller's summary with freshness headers")
	void returnsOwnSummary() {
		stubSession();
		Instant from = Instant.parse("2026-09-16T12:00:00Z");
		Instant to = Instant.parse("2026-09-23T12:00:00Z");
		when(dashboards.getPersonal(userId, from, to)).thenReturn(view());

		ResponseEntity<LedgerSummaryResponse> response = controller.myUsage(bearer(), from, to);

		assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
		assertThat(response.getBody()).isNotNull();
		assertThat(response.getBody().totalRequests()).isEqualTo(10L);
		assertThat(response.getHeaders().getFirst("X-Dashboard-Generated-At"))
				.isEqualTo("2026-09-23T12:00:00Z");
		assertThat(response.getHeaders().getFirst("X-Dashboard-Watermark"))
				.isEqualTo("2026-09-23T12:00:00Z");
		verify(dashboards).getPersonal(userId, from, to);
	}

	@Test
	@DisplayName("invalid sessions answer 401 without touching dashboards")
	void invalidSessionAnswers401() {
		when(sessions.validate(SESSION)).thenThrow(new RuntimeException("bad signature"));

		assertThatThrownBy(() -> controller.myUsage(bearer(), null, null))
				.isInstanceOf(ResponseStatusException.class)
				.extracting(ex -> ((ResponseStatusException) ex).getStatusCode())
				.isEqualTo(HttpStatus.UNAUTHORIZED);
		verify(dashboards, never()).getPersonal(any(), any(), any());
	}

	@Test
	@DisplayName("disabled accounts answer 401")
	void disabledAccountAnswers401() {
		when(sessions.validate(SESSION)).thenReturn(sessionFor(userId));
		UserAccount disabled = mock(UserAccount.class);
		when(disabled.isDisabled()).thenReturn(true);
		when(users.findById(userId)).thenReturn(Optional.of(disabled));

		assertThatThrownBy(() -> controller.myUsage(bearer(), null, null))
				.isInstanceOf(ResponseStatusException.class)
				.extracting(ex -> ((ResponseStatusException) ex).getStatusCode())
				.isEqualTo(HttpStatus.UNAUTHORIZED);
		verify(dashboards, never()).getPersonal(any(), any(), any());
	}

	@Test
	@DisplayName("service rejections propagate unchanged")
	void serviceRejectionsPropagate() {
		stubSession();
		when(dashboards.getPersonal(any(), any(), any())).thenThrow(
				new ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS, "Dashboard view rate exceeded"));

		assertThatThrownBy(() -> controller.myUsage(bearer(), null, null))
				.isInstanceOf(ResponseStatusException.class)
				.extracting(ex -> ((ResponseStatusException) ex).getStatusCode())
				.isEqualTo(HttpStatus.TOO_MANY_REQUESTS);
	}

	@Test
	@DisplayName("missing authorization answers 401 without touching dashboards")
	void missingAuthorizationAnswers401() {
		assertThatThrownBy(() -> controller.myUsage(null, null, null))
				.isInstanceOf(ResponseStatusException.class)
				.extracting(ex -> ((ResponseStatusException) ex).getStatusCode())
				.isEqualTo(HttpStatus.UNAUTHORIZED);
		verify(dashboards, never()).getPersonal(any(), any(), any());
	}

	@Test
	@DisplayName("prefix-less tokens validate as sessions")
	void prefixLessTokenWorks() {
		stubSession();
		when(dashboards.getPersonal(eq(userId), isNull(), isNull())).thenReturn(view());

		ResponseEntity<LedgerSummaryResponse> response = controller.myUsage(SESSION, null, null);

		assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
		assertThat(response.getBody()).isNotNull();
	}

	@Test
	@DisplayName("null decoded sessions answer 401")
	void nullDecodedSessionAnswers401() {
		when(sessions.validate(SESSION)).thenReturn(null);

		assertThatThrownBy(() -> controller.myUsage(bearer(), null, null))
				.isInstanceOf(ResponseStatusException.class)
				.extracting(ex -> ((ResponseStatusException) ex).getStatusCode())
				.isEqualTo(HttpStatus.UNAUTHORIZED);
		verify(dashboards, never()).getPersonal(any(), any(), any());
	}
}
