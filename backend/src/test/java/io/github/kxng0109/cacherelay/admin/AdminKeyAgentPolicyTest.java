package io.github.kxng0109.cacherelay.admin;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import io.github.kxng0109.cacherelay.admin.dto.CreateKeyRequest;
import io.github.kxng0109.cacherelay.admin.dto.CreatedKeyResponse;
import io.github.kxng0109.cacherelay.admin.dto.KeyResponse;
import io.github.kxng0109.cacherelay.admin.dto.UpdateKeyRequest;
import io.github.kxng0109.cacherelay.cache.contracts.CacheScope;
import io.github.kxng0109.cacherelay.contracts.SHA256Hash;
import io.github.kxng0109.cacherelay.contracts.VirtualApiKey;
import io.github.kxng0109.cacherelay.security.ratelimit.KeyManagementService;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

/**
 * Unit tests for the A2A agent-policy pass-through on the admin key endpoints:
 * requests carrying agent allow/deny lists must select the canonical service
 * overload and echo the sets back in the response.
 */
@DisplayName("AdminKeyController agent policy pass-through")
class AdminKeyAgentPolicyTest {

	private static final SHA256Hash HASH = SHA256Hash.fromRawKey("gw-admin-agent-policy-test");

	private KeyManagementService service;

	private AdminKeyController controller;

	@BeforeEach
	void setUp() {
		service = mock(KeyManagementService.class);
		controller = new AdminKeyController(service);
	}

	@Test
	@DisplayName("create forwards agent sets and the owner")
	void createForwardsAgentSets() {
		UUID owner = UUID.randomUUID();
		VirtualApiKey key = key(Set.of("research-*"), Set.of("prod-*"), owner);
		when(service.createKey(
				anyString(), anyString(), anyInt(), anyInt(),
				any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(),
				eq(owner)))
				.thenReturn(new KeyManagementService.CreatedKey(HASH, "gw-plaintext", key));
		when(service.usernameOf(owner)).thenReturn("local");

		ResponseEntity<CreatedKeyResponse> response = controller.createKey(new CreateKeyRequest(
				"owner", "key", 10, 100, Set.of(), Set.of(), Set.of(), Set.of(),
				Set.of(), Set.of(), Set.of(), Set.of(), null, Set.of(CacheScope.TENANT),
				Set.of("research-*"), Set.of("prod-*"), owner));

		assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
		assertThat(response.getBody()).isNotNull();
		assertThat(response.getBody().allowedAgents()).containsExactly("research-*");
		assertThat(response.getBody().deniedAgents()).containsExactly("prod-*");
		assertThat(response.getBody().ownerUserId()).isEqualTo(owner);
		assertThat(response.getBody().ownerUsername()).isEqualTo("local");
	}

	@Test
	@DisplayName("create with only denied agents still forwards the owner")
	void createForwardsDeniedAgentsOnly() {
		UUID owner = UUID.randomUUID();
		VirtualApiKey key = key(Set.of(), Set.of("prod-*"), owner);
		when(service.createKey(
				anyString(), anyString(), anyInt(), anyInt(),
				any(), any(), any(), any(), any(), any(), any(), any(), any(), any(),
				eq(Set.of()), eq(Set.of("prod-*")), eq(owner)))
				.thenReturn(new KeyManagementService.CreatedKey(HASH, "gw-plaintext", key));

		ResponseEntity<CreatedKeyResponse> response = controller.createKey(new CreateKeyRequest(
				"owner", "key", 10, 100, Set.of(), Set.of(), Set.of(), Set.of(),
				Set.of(), Set.of(), Set.of(), Set.of(), null, Set.of(CacheScope.TENANT),
				Set.of(), Set.of("prod-*"), owner));

		assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
		assertThat(response.getBody()).isNotNull();
		assertThat(response.getBody().deniedAgents()).containsExactly("prod-*");
		verify(service).createKey(
				anyString(), anyString(), anyInt(), anyInt(),
				any(), any(), any(), any(), any(), any(), any(), any(), any(), any(),
				eq(Set.of()), eq(Set.of("prod-*")), eq(owner));
	}

	@Test
	@DisplayName("update forwards agent sets and echoes them")
	void updateForwardsAgentSets() {
		when(service.updateKey(
				any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(),
				any(), any(), eq(Set.of("research-*")), eq(Set.of("prod-*")), eq(true)))
				.thenReturn(Optional.of(key(Set.of("research-*"), Set.of("prod-*"), null)));

		ResponseEntity<KeyResponse> response = controller.updateKey(HASH.hex(), new UpdateKeyRequest(
				null, null, null, null, null, null, null, null, null, null, null, null,
				null, true, Set.of("research-*"), Set.of("prod-*"), null));

		assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
		assertThat(response.getBody()).isNotNull();
		assertThat(response.getBody().allowedAgents()).containsExactly("research-*");
		verify(service).updateKey(
				any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(),
				any(), any(), eq(Set.of("research-*")), eq(Set.of("prod-*")), eq(true));
	}

	private static VirtualApiKey key(Set<String> allowedAgents, Set<String> deniedAgents, UUID owner) {
		return new VirtualApiKey(
				HASH,
				"gw-",
				"owner",
				"key",
				10,
				100,
				Set.of(),
				Set.of(),
				Set.of(),
				Set.of(),
				Set.of(),
				Set.of(),
				Set.of(),
				Set.of(),
				true,
				true,
				Instant.now(),
				VirtualApiKey.normalizeCacheScopes(Set.of()),
				allowedAgents,
				deniedAgents,
				owner,
				false
		);
	}
}
