package io.github.kxng0109.cacherelay.admin;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.server.ResponseStatusException;

import io.github.kxng0109.cacherelay.admin.dto.CreatedKeyResponse;
import io.github.kxng0109.cacherelay.admin.dto.CreateKeyRequest;
import io.github.kxng0109.cacherelay.admin.dto.KeyResponse;
import io.github.kxng0109.cacherelay.admin.dto.UpdateKeyRequest;
import io.github.kxng0109.cacherelay.contracts.SHA256Hash;
import io.github.kxng0109.cacherelay.contracts.VirtualApiKey;
import io.github.kxng0109.cacherelay.security.ratelimit.KeyManagementService;

/**
 * Ownership wiring on the admin key surface: owner forwarding, reassignment,
 * terminal revocation, and owner attribution on reads.
 */
@DisplayName("AdminKeyOwnership")
class AdminKeyOwnershipTest {

	private final KeyManagementService service = mock(KeyManagementService.class);
	private final AdminKeyController controller = new AdminKeyController(service);
	private final UUID ownerId = UUID.randomUUID();

	private VirtualApiKey owned(boolean enabled, boolean revoked) {
		return new VirtualApiKey(
				SHA256Hash.fromRawKey("gw-ownership-test-key-000000000001"),
				"gw-", "tenant-a", "owned", 60, 1000,
				Set.of(), Set.of(), Set.of(), Set.of(), Set.of(), Set.of(),
				Set.of(), Set.of(), true, enabled, Instant.now(),
				Set.of(), Set.of(), Set.of(), ownerId, revoked);
	}

	private CreateKeyRequest ownedRequest() {
		return new CreateKeyRequest(
				"tenant-a", "owned", 60, 1000, Set.of(), Set.of(), Set.of(), Set.of(),
				Set.of(), Set.of(), Set.of(), Set.of(), null, Set.of(), Set.of(), Set.of(),
				ownerId);
	}

	@Test
	@DisplayName("create forwards the owner and surfaces it on the response")
	void createForwardsOwner() {
		VirtualApiKey key = owned(true, false);
		when(service.createKey(anyString(), anyString(), anyInt(), anyInt(), any(), any(), any(), any(),
				any(), any(), any(), any(), any(), any(), any(), any(), eq(ownerId)))
				.thenReturn(new KeyManagementService.CreatedKey(key.keyHash(), "gw-plaintext", key));
		when(service.usernameOf(ownerId)).thenReturn("local");

		ResponseEntity<CreatedKeyResponse> response = controller.createKey(ownedRequest());

		assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
		assertThat(response.getBody()).isNotNull();
		assertThat(response.getBody().ownerUserId()).isEqualTo(ownerId);
		assertThat(response.getBody().ownerUsername()).isEqualTo("local");
	}

	@Test
	@DisplayName("create with an unusable owner answers 400")
	void createBadOwnerAnswers400() {
		when(service.createKey(anyString(), anyString(), anyInt(), anyInt(), any(), any(), any(),
				any(), any(), any(), any(), any(), any(), any(), any(), any(), any()))
				.thenThrow(new IllegalArgumentException("owning account is unknown or disabled"));

		assertThatThrownBy(() -> controller.createKey(ownedRequest()))
				.isInstanceOf(ResponseStatusException.class)
				.extracting(ex -> ((ResponseStatusException) ex).getStatusCode())
				.isEqualTo(HttpStatus.BAD_REQUEST);
	}

	@Test
	@DisplayName("patch with an owner reassigns instead of updating fields")
	void patchOwnerReassigns() {
		VirtualApiKey key = owned(true, false);
		UUID other = UUID.randomUUID();
		VirtualApiKey moved = new VirtualApiKey(
				key.keyHash(), "gw-", "tenant-a", "owned", 60, 1000,
				Set.of(), Set.of(), Set.of(), Set.of(), Set.of(), Set.of(),
				Set.of(), Set.of(), true, true, Instant.now(),
				Set.of(), Set.of(), Set.of(), other, false);
		when(service.assignOwner(eq(key.keyHash()), eq(other))).thenReturn(Optional.of(moved));
		when(service.usernameOf(other)).thenReturn("other");
		when(service.updateKey(any(), any(), any(), any(), any(), any(), any(), any(), any(), any(),
				any(), any(), any(), any(), any(), any(), any())).thenReturn(Optional.of(moved));
		when(service.updateKey(any(), any(), any(), any(), any(), any(), any(), any(), any(), any(),
				any(), any(), any(), any(), any(), any(), any())).thenReturn(Optional.of(moved));

		UpdateKeyRequest patch = new UpdateKeyRequest(
				null, null, null, null, null, null, null, null, null, null,
				null, null, null, null, null, null, other);
		ResponseEntity<KeyResponse> response =
				controller.updateKey(key.keyHash().hex(), patch);

		assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
		assertThat(response.getBody()).isNotNull();
		assertThat(response.getBody().ownerUserId()).isEqualTo(other);
		verify(service).assignOwner(eq(key.keyHash()), eq(other));
		verify(service).updateKey(any(), any(), any(), any(), any(), any(), any(), any(), any(), any(),
				any(), any(), any(), any(), any(), any(), any());
	}

	@Test
	@DisplayName("revoke endpoint tombstones terminally")
	void revokeEndpointTombstones() {
		VirtualApiKey key = owned(true, false);
		VirtualApiKey tombstoned = new VirtualApiKey(
				key.keyHash(), "gw-", "tenant-a", "owned", 60, 1000,
				Set.of(), Set.of(), Set.of(), Set.of(), Set.of(), Set.of(),
				Set.of(), Set.of(), true, false, Instant.now(),
				Set.of(), Set.of(), Set.of(), ownerId, true);
		when(service.findByHash(key.keyHash())).thenReturn(Optional.of(tombstoned));

		ResponseEntity<KeyResponse> response = controller.revokeKey(key.keyHash().hex());

		assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
		assertThat(response.getBody()).isNotNull();
		assertThat(response.getBody().enabled()).isFalse();
		verify(service).revokeKey(key.keyHash());
	}

	@Test
	@DisplayName("revoke of an unknown key answers 404")
	void revokeUnknownAnswers404() {
		SHA256Hash missing = SHA256Hash.fromHex("a".repeat(64));
		when(service.findByHash(missing)).thenReturn(Optional.empty());

		ResponseEntity<KeyResponse> response = controller.revokeKey(missing.hex());

		assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
		verify(service, never()).revokeKey(any());
	}

	@Test
	@DisplayName("reads carry owner attribution")
	void readsCarryAttribution() {
		VirtualApiKey key = owned(true, false);
		when(service.listKeys(null)).thenReturn(List.of(key));
		when(service.usernameOf(ownerId)).thenReturn("local");

		ResponseEntity<List<KeyResponse>> response = controller.listKeys(null);

		assertThat(response.getBody()).isNotNull();
		assertThat(response.getBody()).hasSize(1);
		assertThat(response.getBody().getFirst().ownerUserId()).isEqualTo(ownerId);
		assertThat(response.getBody().getFirst().ownerUsername()).isEqualTo("local");
	}
}
