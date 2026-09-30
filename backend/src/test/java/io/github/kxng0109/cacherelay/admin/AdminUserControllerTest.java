package io.github.kxng0109.cacherelay.admin;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.server.ResponseStatusException;

import io.github.kxng0109.cacherelay.admin.dto.PageResponse;
import io.github.kxng0109.cacherelay.admin.dto.SetDisabledRequest;
import io.github.kxng0109.cacherelay.admin.dto.UserSummaryResponse;
import io.github.kxng0109.cacherelay.auth.UserAccount;
import io.github.kxng0109.cacherelay.auth.UserAccountRepository;
import io.github.kxng0109.cacherelay.security.ratelimit.KeyManagementService;
import jakarta.validation.Validation;
import jakarta.validation.Validator;

/**
 * User lifecycle administration: disabling toggles access without touching
 * terminal key tombstones, and deletion cascades irreversibly first.
 */
@DisplayName("AdminUserController")
class AdminUserControllerTest {

	private static final Validator VALIDATOR =
			Validation.buildDefaultValidatorFactory().getValidator();

	private final UserAccountRepository users = mock(UserAccountRepository.class);
	private final KeyManagementService keys = mock(KeyManagementService.class);
	private final AdminUserController controller = new AdminUserController(users, keys);
	private final UUID userId = UUID.randomUUID();

	@Test
	@DisplayName("disabling suspends access and clears the owner-activity cache")
	void disablingSuspends() {
		UserAccount account = mock(UserAccount.class);
		when(account.getId()).thenReturn(userId);
		when(users.findById(userId)).thenReturn(Optional.of(account));

		ResponseEntity<Void> response = controller.setDisabled(userId.toString(), new SetDisabledRequest(true));

		assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
		verify(account).disable();
		verify(users).save(account);
		verify(keys).invalidateOwnerCache(userId);
	}

	@Test
	@DisplayName("re-enabling a user never resurrects tombstoned keys")
	void reenablingRespectsTombstones() {
		UserAccount account = mock(UserAccount.class);
		when(users.findById(userId)).thenReturn(Optional.of(account));

		controller.setDisabled(userId.toString(), new SetDisabledRequest(false));

		verify(account).enable();
		verify(keys, never()).updateKey(any(), any(), any(), any(), any(), any(), any(),
				any(), any(), any(), any(), any(), any(), any(), any(), any(), any());
	}

	@Test
	@DisplayName("disabling an unknown user answers 404")
	void disableUnknownAnswers404() {
		when(users.findById(userId)).thenReturn(Optional.empty());

		assertThatThrownBy(() -> controller.setDisabled(userId.toString(), new SetDisabledRequest(true)))
				.isInstanceOf(ResponseStatusException.class)
				.extracting(ex -> ((ResponseStatusException) ex).getStatusCode())
				.isEqualTo(HttpStatus.NOT_FOUND);
	}

	@Test
	@DisplayName("deletion cascades terminal revocation before removing the row")
	void deletionCascadesTerminally() {
		UserAccount account = mock(UserAccount.class);
		when(account.getId()).thenReturn(userId);
		when(users.findById(userId)).thenReturn(Optional.of(account));
		when(keys.revokeUserKeys(userId)).thenReturn(2);

		ResponseEntity<Void> response = controller.deleteUser(userId.toString());

		assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
		verify(keys).revokeUserKeys(userId);
		verify(keys).clearDefaultKey(userId);
		verify(users).deleteById(userId);
	}

	@Test
	@DisplayName("deleting an unknown user answers 404 without touching keys")
	void deleteUnknownAnswers404() {
		when(users.findById(userId)).thenReturn(Optional.empty());

		assertThatThrownBy(() -> controller.deleteUser(userId.toString()))
				.isInstanceOf(ResponseStatusException.class)
				.extracting(ex -> ((ResponseStatusException) ex).getStatusCode())
				.isEqualTo(HttpStatus.NOT_FOUND);
		verify(keys, never()).revokeUserKeys(any());
	}

	@Test
	@DisplayName("malformed user ids answer 400")
	void malformedIdsAnswer400() {
		assertThatThrownBy(() -> controller.deleteUser("not-a-uuid"))
				.isInstanceOf(ResponseStatusException.class)
				.extracting(ex -> ((ResponseStatusException) ex).getStatusCode())
				.isEqualTo(HttpStatus.BAD_REQUEST);
	}

	@Test
	@SuppressWarnings("DataFlowIssue")
	@DisplayName("FS-B10: absent disabled flag violates the constraint (empty {} cannot re-enable)")
	void absentDisabledFlagViolates() {
		assertThat(VALIDATOR.validate(new SetDisabledRequest(null))).isNotEmpty();
		assertThat(VALIDATOR.validate(new SetDisabledRequest(Boolean.TRUE))).isEmpty();
		assertThat(VALIDATOR.validate(new SetDisabledRequest(Boolean.FALSE))).isEmpty();
	}

	@Test
	@DisplayName("B1: user listing maps accounts without exposing hashes")
	void listUsersMapsAccounts() {
		UserAccount account = mock(UserAccount.class);
		when(account.getId()).thenReturn(userId);
		when(account.getUsername()).thenReturn("alice");
		when(account.isAdmin()).thenReturn(false);
		when(account.isDisabled()).thenReturn(false);
		when(account.getCreatedAt()).thenReturn(Instant.parse("2026-09-01T00:00:00Z"));
		when(users.findAll(any(Pageable.class))).thenReturn(new PageImpl<>(List.of(account)));

		ResponseEntity<PageResponse<UserSummaryResponse>> response =
				controller.listUsers(PageRequest.of(0, 20));

		assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
		assertThat(response.getBody().content()).hasSize(1);
		assertThat(response.getBody().content().getFirst().username()).isEqualTo("alice");
		assertThat(response.getBody().content().getFirst().userId()).isEqualTo(userId);
		assertThat(response.getBody().totalElements()).isEqualTo(1);
	}

	@Test
	@DisplayName("B1: empty user table lists empty without failing")
	void listUsersEmptyTable() {
		when(users.findAll(any(Pageable.class))).thenReturn(new PageImpl<>(List.of()));

		ResponseEntity<PageResponse<UserSummaryResponse>> response =
				controller.listUsers(PageRequest.of(0, 20));

		assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
		assertThat(response.getBody().content()).isEmpty();
		assertThat(response.getBody().totalElements()).isZero();
	}
}
