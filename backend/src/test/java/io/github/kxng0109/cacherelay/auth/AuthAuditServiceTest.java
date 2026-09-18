package io.github.kxng0109.cacherelay.auth;

import java.time.Instant;
import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Unit tests for audit filtering and pseudonym plumbing.
 */
@DisplayName("AuthAuditService")
class AuthAuditServiceTest {

	@Test
	@DisplayName("filters delegate to the repository and pseudonyms are stable")
	void filtersAndPseudonyms() {
		AuthAuditRepository repository = mock(AuthAuditRepository.class);
		AuthAuditService audit = new AuthAuditService(repository, AuthProperties.defaults());
		AuthAuditEvent event = new AuthAuditEvent("actor", AuthAuditService.ACTION_LOCAL_LOGIN,
				AuthAuditService.SEVERITY_INFO, "/v1/auth/login",
				AuthAuditService.OUTCOME_SUCCESS, null, null);
		when(repository.findBySeverityOrderByOccurredAtDesc("INFO")).thenReturn(List.of(event));
		when(repository.findByActionOrderByOccurredAtDesc("LOCAL_LOGIN"))
				.thenReturn(List.of(event));
		when(repository.countByActorHashAndActionAndOutcomeAndOccurredAtAfter(any(), any(),
				any(), any(Instant.class))).thenReturn(2L);

		assertThat(audit.bySeverity("INFO")).containsExactly(event);
		assertThat(audit.byAction("LOCAL_LOGIN")).containsExactly(event);
		assertThat(audit.recentFailures("hash", "LOCAL_LOGIN", Instant.now())).isEqualTo(2L);
		assertThat(audit.pseudonym("op")).isEqualTo(audit.pseudonym("op"));
	}
}
