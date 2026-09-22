package io.github.kxng0109.cacherelay.admin;

import io.github.kxng0109.cacherelay.admin.dto.CreateKeyRequest;
import io.github.kxng0109.cacherelay.admin.dto.CreatedKeyResponse;
import io.github.kxng0109.cacherelay.admin.dto.KeyResponse;
import io.github.kxng0109.cacherelay.admin.dto.UpdateKeyRequest;
import io.github.kxng0109.cacherelay.cache.contracts.CacheScope;
import io.github.kxng0109.cacherelay.contracts.SHA256Hash;
import io.github.kxng0109.cacherelay.contracts.VirtualApiKey;
import io.github.kxng0109.cacherelay.security.ratelimit.KeyManagementService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.server.ResponseStatusException;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.*;

@DisplayName("AdminKeyController")
class AdminKeyControllerTest {

	private final KeyManagementService keyManagementService = mock(KeyManagementService.class);
	private final AdminKeyController controller = new AdminKeyController(keyManagementService);

	@Test
	@DisplayName("createKey returns 201 Created with single-exposure plaintext and metadata")
	void createKeySuccess() {
		SHA256Hash hash = SHA256Hash.fromRawKey("gw-secret12345");
		VirtualApiKey metadata = new VirtualApiKey(
				hash, "gw-", "owner-1", "test-key", 60, 1000,
				Set.of("gpt-4o"), Set.of("openai"), true, Instant.now()
		);
		KeyManagementService.CreatedKey created = new KeyManagementService.CreatedKey(
				hash, "gw-secret12345", metadata
		);

		when(keyManagementService.createKey(
				eq("owner-1"), eq("test-key"), eq(60), eq(1000),
				eq(Set.of("gpt-4o")), eq(Set.of("openai")),
				eq(Set.of()), eq(Set.of()), eq(Set.of()), eq(Set.of()),
				eq(Set.of()), eq(Set.of()), eq(null), eq(Set.of(CacheScope.TENANT)),
				eq(Set.of()), eq(Set.of()), eq(null)
		)).thenReturn(created);

		CreateKeyRequest request = new CreateKeyRequest(
				"owner-1", "test-key", 60, 1000,
				Set.of("gpt-4o"), Set.of("openai")
		);

		ResponseEntity<CreatedKeyResponse> response = controller.createKey(request);

		assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
		assertThat(response.getBody()).isNotNull();
		assertThat(response.getBody().key()).isEqualTo("gw-secret12345");
		assertThat(response.getBody().keyId()).isEqualTo(hash.hex());
		assertThat(response.getBody().ownerId()).isEqualTo("owner-1");
		assertThat(response.getBody().rpmLimit()).isEqualTo(60);

		// Create key with allowedTools and deniedTools
		CreateKeyRequest requestWithTools = new CreateKeyRequest(
				"owner-1", "test-key-tools", 60, 1000,
				Set.of("gpt-4o"), Set.of("openai"),
				Set.of("postgres__*"), Set.of("*:delete_*")
		);
		VirtualApiKey keyWithTools = new VirtualApiKey(
				hash, "gw-", "owner-1", "test-key-tools", 60, 1000,
				Set.of("gpt-4o"), Set.of("openai"),
				Set.of("postgres__*"), Set.of("*:delete_*"),
				true, Instant.now()
		);
		when(keyManagementService.createKey(
				eq("owner-1"), eq("test-key-tools"), eq(60), eq(1000),
				eq(Set.of("gpt-4o")), eq(Set.of("openai")),
				eq(Set.of("postgres__*")), eq(Set.of("*:delete_*")),
				eq(Set.of()), eq(Set.of()), eq(Set.of()), eq(Set.of()),
				eq(null), eq(Set.of(CacheScope.TENANT)), eq(Set.of()), eq(Set.of()), eq(null)
		)).thenReturn(new KeyManagementService.CreatedKey(hash, "gw-secretTools", keyWithTools));

		ResponseEntity<CreatedKeyResponse> responseTools = controller.createKey(requestWithTools);
		assertThat(responseTools.getStatusCode()).isEqualTo(HttpStatus.CREATED);
		assertThat(responseTools.getBody().allowedTools()).containsExactly("postgres__*");
		assertThat(responseTools.getBody().deniedTools()).containsExactly("*:delete_*");
	}

	@Test
	@DisplayName("createKey forwards resource and prompt visibility sets")
	void createKeyForwardsVisibility() {
		SHA256Hash hash = SHA256Hash.fromRawKey("gw-secretVis1");
		VirtualApiKey metadata = new VirtualApiKey(
				hash, "gw-", "owner-1", "vis-key", 60, 1000,
				Set.of(), Set.of(),
				Set.of(), Set.of(),
				Set.of("postgres://*"), Set.of(),
				Set.of(), Set.of("admin_*"),
				true,
				true, Instant.now()
		);
		when(keyManagementService.createKey(
				eq("owner-1"), eq("vis-key"), eq(60), eq(1000),
				eq(Set.of()), eq(Set.of()),
				eq(Set.of()), eq(Set.of()),
				eq(Set.of("postgres://*")), eq(Set.of()),
				eq(Set.of()), eq(Set.of("admin_*")),
				eq(null), eq(Set.of(CacheScope.TENANT)), eq(Set.of()), eq(Set.of()), eq(null)
		)).thenReturn(new KeyManagementService.CreatedKey(hash, "gw-secretVis1", metadata));

		CreateKeyRequest request = new CreateKeyRequest(
				"owner-1", "vis-key", 60, 1000,
				Set.of(), Set.of(),
				Set.of(), Set.of(),
				Set.of("postgres://*"), Set.of(),
				Set.of(), Set.of("admin_*")
		);

		ResponseEntity<CreatedKeyResponse> response = controller.createKey(request);

		assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
		assertThat(response.getBody().allowedResources()).containsExactly("postgres://*");
		assertThat(response.getBody().deniedPrompts()).containsExactly("admin_*");
	}

	@Test
	@DisplayName("createKey forwards an explicit injection flag")
	void createKeyForwardsInjectionFlag() {
		SHA256Hash hash = SHA256Hash.fromRawKey("gw-secretFlag1");
		VirtualApiKey metadata = new VirtualApiKey(
				hash, "gw-", "owner-1", "flag-key", 60, 1000,
				Set.of(), Set.of(),
				Set.of(), Set.of(),
				Set.of(), Set.of(),
				Set.of(), Set.of(),
				false,
				true, Instant.now()
		);
		when(keyManagementService.createKey(
				eq("owner-1"), eq("flag-key"), eq(60), eq(1000),
				eq(Set.of()), eq(Set.of()),
				eq(Set.of()), eq(Set.of()),
				eq(Set.of()), eq(Set.of()),
				eq(Set.of()), eq(Set.of()),
				eq(false), eq(Set.of(CacheScope.TENANT)), eq(Set.of()), eq(Set.of()), eq(null)
		)).thenReturn(new KeyManagementService.CreatedKey(hash, "gw-secretFlag1", metadata));

		CreateKeyRequest request = new CreateKeyRequest(
				"owner-1", "flag-key", 60, 1000,
				Set.of(), Set.of(),
				Set.of(), Set.of(),
				Set.of(), Set.of(),
				Set.of(), Set.of(),
				false
		);

		ResponseEntity<CreatedKeyResponse> response = controller.createKey(request);

		assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
		assertThat(response.getBody().injectionBlock()).isFalse();
		assertThat(response.getBody().allowedCacheScopes()).isEqualTo(Set.of(CacheScope.TENANT));
	}

	@Test
	@DisplayName("updateKey forwards an explicit injection flag")
	void updateKeyForwardsInjectionFlag() {
		SHA256Hash hash = SHA256Hash.fromRawKey("gw-secretFlag2");
		VirtualApiKey metadata = new VirtualApiKey(
				hash, "gw-", "owner-1", "flag-key", 60, 1000,
				Set.of(), Set.of(),
				Set.of(), Set.of(),
				Set.of(), Set.of(),
				Set.of(), Set.of(),
				false,
				true, Instant.now()
		);
		when(keyManagementService.updateKey(
				eq(hash), eq(null), eq(null), eq(null),
				eq(null), eq(null),
				eq(null), eq(null),
				eq(null), eq(null),
				eq(null), eq(null),
				eq(false), eq(null), eq(null),
				eq(null), eq(null)
		)).thenReturn(Optional.of(metadata));

		UpdateKeyRequest request = new UpdateKeyRequest(
				null, null, null,
				null, null,
				null, null,
				null, null,
				null, null,
				false, null
		);

		ResponseEntity<KeyResponse> response = controller.updateKey(hash.hex(), request);

		assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
		assertThat(response.getBody().injectionBlock()).isFalse();
	}

	@Test
	@DisplayName("create and update cover remaining visibility combinations")
	void visibilityCombinations() {
		SHA256Hash hash = SHA256Hash.fromRawKey("gw-secretCombo1");
		VirtualApiKey metadata = new VirtualApiKey(
				hash, "gw-", "owner-1", "combo-key", 60, 1000,
				Set.of(), Set.of(),
				Set.of("postgres__*"), Set.of(),
				Set.of(), Set.of("postgres://secret/*"),
				Set.of("review_*"), Set.of(),
				true,
				true, Instant.now()
		);
		when(keyManagementService.createKey(
				eq("owner-1"), eq("combo-key"), eq(60), eq(1000),
				eq(Set.of()), eq(Set.of()),
				eq(Set.of("postgres__*")), eq(Set.of()),
				eq(Set.of()), eq(Set.of("postgres://secret/*")),
				eq(Set.of("review_*")), eq(Set.of()),
				eq(null), eq(Set.of(CacheScope.TENANT)), eq(Set.of()), eq(Set.of()), eq(null)
		)).thenReturn(new KeyManagementService.CreatedKey(hash, "gw-secretCombo1", metadata));

		CreateKeyRequest create = new CreateKeyRequest(
				"owner-1", "combo-key", 60, 1000,
				Set.of(), Set.of(),
				Set.of("postgres__*"), Set.of(),
				Set.of(), Set.of("postgres://secret/*"),
				Set.of("review_*"), Set.of()
		);

		ResponseEntity<CreatedKeyResponse> created = controller.createKey(create);

		assertThat(created.getStatusCode()).isEqualTo(HttpStatus.CREATED);
		assertThat(created.getBody().deniedResources()).containsExactly("postgres://secret/*");
		assertThat(created.getBody().allowedPrompts()).containsExactly("review_*");

		when(keyManagementService.updateKey(
				eq(hash), eq(null), eq(null), eq(null),
				eq(null), eq(null),
				eq(null), eq(null),
				eq(Set.of("postgres://*")), eq(Set.of()),
				eq(null), eq(null),
				eq(null), eq(null), eq(null),
				eq(null), eq(null)
		)).thenReturn(Optional.of(metadata));

		UpdateKeyRequest update = new UpdateKeyRequest(
				null, null, null,
				null, null,
				null, null,
				Set.of("postgres://*"), Set.of(),
				null, null,
				null, null
		);

		ResponseEntity<KeyResponse> updated = controller.updateKey(hash.hex(), update);

		assertThat(updated.getStatusCode()).isEqualTo(HttpStatus.OK);
	}

	@Test
	@DisplayName("create with tools and an explicit flag takes the full path")
	void createToolsPlusFlag() {
		SHA256Hash hash = SHA256Hash.fromRawKey("gw-secretCombo2");
		VirtualApiKey metadata = new VirtualApiKey(
				hash, "gw-", "owner-1", "combo2-key", 60, 1000,
				Set.of(), Set.of(),
				Set.of("postgres__*"), Set.of(),
				Set.of(), Set.of(),
				Set.of(), Set.of(),
				true,
				true, Instant.now()
		);
		when(keyManagementService.createKey(
				eq("owner-1"), eq("combo2-key"), eq(60), eq(1000),
				eq(Set.of()), eq(Set.of()),
				eq(Set.of("postgres__*")), eq(Set.of()),
				eq(Set.of()), eq(Set.of()),
				eq(Set.of()), eq(Set.of()),
				eq(true), eq(Set.of(CacheScope.TENANT)), eq(Set.of()), eq(Set.of()), eq(null)
		)).thenReturn(new KeyManagementService.CreatedKey(hash, "gw-secretCombo2", metadata));

		CreateKeyRequest request = new CreateKeyRequest(
				"owner-1", "combo2-key", 60, 1000,
				Set.of(), Set.of(),
				Set.of("postgres__*"), Set.of(),
				Set.of(), Set.of(),
				Set.of(), Set.of(),
				true
		);

		ResponseEntity<CreatedKeyResponse> response = controller.createKey(request);

		assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
		assertThat(response.getBody().injectionBlock()).isTrue();
	}

	@Test
	@DisplayName("createKey forwards a non-default cache-scope allowlist")
	void createKeyForwardsCacheScopes() {
		SHA256Hash hash = SHA256Hash.fromRawKey("gw-secretScope1");
		VirtualApiKey metadata = new VirtualApiKey(
				hash, "gw-", "owner-1", "scope-key", 60, 1000,
				Set.of(), Set.of(),
				Set.of(), Set.of(),
				Set.of(), Set.of(),
				Set.of(), Set.of(),
				true,
				true, Instant.now(),
				Set.of(CacheScope.TENANT, CacheScope.GLOBAL)
		);
		when(keyManagementService.createKey(
				eq("owner-1"), eq("scope-key"), eq(60), eq(1000),
				eq(Set.of()), eq(Set.of()),
				eq(Set.of()), eq(Set.of()),
				eq(Set.of()), eq(Set.of()),
				eq(Set.of()), eq(Set.of()),
				eq(null), eq(Set.of(CacheScope.TENANT, CacheScope.GLOBAL)),
				eq(Set.of()), eq(Set.of()), eq(null)
		)).thenReturn(new KeyManagementService.CreatedKey(hash, "gw-secretScope1", metadata));

		CreateKeyRequest request = new CreateKeyRequest(
				"owner-1", "scope-key", 60, 1000,
				Set.of(), Set.of(),
				Set.of(), Set.of(),
				Set.of(), Set.of(),
				Set.of(), Set.of(),
				null, Set.of(CacheScope.TENANT, CacheScope.GLOBAL)
		);

		ResponseEntity<CreatedKeyResponse> response = controller.createKey(request);

		assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
		assertThat(response.getBody().allowedCacheScopes())
				.isEqualTo(Set.of(CacheScope.TENANT, CacheScope.GLOBAL));
	}

	@Test
	@DisplayName("create with only denied tools takes the tools branch")
	void createDeniedOnly() {
		SHA256Hash hash = SHA256Hash.fromRawKey("gw-secretDenied1");
		VirtualApiKey metadata = new VirtualApiKey(
				hash, "gw-", "owner-1", "denied-key", 60, 1000,
				Set.of(), Set.of(),
				Set.of(), Set.of("*:delete_*"),
				Set.of(), Set.of(),
				Set.of(), Set.of(),
				true,
				true, Instant.now()
		);
		when(keyManagementService.createKey(
				eq("owner-1"), eq("denied-key"), eq(60), eq(1000),
				eq(Set.of()), eq(Set.of()),
				eq(Set.of()), eq(Set.of("*:delete_*")),
				eq(Set.of()), eq(Set.of()), eq(Set.of()), eq(Set.of()),
				eq(null), eq(Set.of(CacheScope.TENANT)), eq(Set.of()), eq(Set.of()), eq(null)
		)).thenReturn(new KeyManagementService.CreatedKey(hash, "gw-secretDenied1", metadata));

		CreateKeyRequest request = new CreateKeyRequest(
				"owner-1", "denied-key", 60, 1000,
				Set.of(), Set.of(),
				Set.of(), Set.of("*:delete_*")
		);

		ResponseEntity<CreatedKeyResponse> response = controller.createKey(request);

		assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
		assertThat(response.getBody().deniedTools()).containsExactly("*:delete_*");
	}

	@Test
	@DisplayName("create with only prompts takes the visibility branch")
	void createPromptsOnly() {
		SHA256Hash hash = SHA256Hash.fromRawKey("gw-secretPrompts1");
		VirtualApiKey metadata = new VirtualApiKey(
				hash, "gw-", "owner-1", "prompts-key", 60, 1000,
				Set.of(), Set.of(),
				Set.of(), Set.of(),
				Set.of(), Set.of(),
				Set.of("server__review_*"), Set.of(),
				true,
				true, Instant.now()
		);
		when(keyManagementService.createKey(
				eq("owner-1"), eq("prompts-key"), eq(60), eq(1000),
				eq(Set.of()), eq(Set.of()),
				eq(Set.of()), eq(Set.of()),
				eq(Set.of()), eq(Set.of()),
				eq(Set.of("server__review_*")), eq(Set.of()),
				eq(null), eq(Set.of(CacheScope.TENANT)), eq(Set.of()), eq(Set.of()), eq(null)
		)).thenReturn(new KeyManagementService.CreatedKey(hash, "gw-secretPrompts1", metadata));

		CreateKeyRequest request = new CreateKeyRequest(
				"owner-1", "prompts-key", 60, 1000,
				Set.of(), Set.of(),
				Set.of(), Set.of(),
				Set.of(), Set.of(),
				Set.of("server__review_*"), Set.of()
		);

		ResponseEntity<CreatedKeyResponse> response = controller.createKey(request);

		assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
		assertThat(response.getBody().allowedPrompts()).containsExactly("server__review_*");
	}

	@Test
	@DisplayName("update with only scopes takes the full path")
	void updateScopesOnly() {
		SHA256Hash hash = SHA256Hash.fromRawKey("gw-secretScopes1");
		VirtualApiKey metadata = new VirtualApiKey(
				hash, "gw-", "owner-1", "scopes-key", 60, 1000,
				Set.of(), Set.of(),
				Set.of(), Set.of(),
				Set.of(), Set.of(),
				Set.of(), Set.of(),
				true,
				true, Instant.now(),
				Set.of(CacheScope.TENANT, CacheScope.GLOBAL)
		);
		when(keyManagementService.updateKey(
				eq(hash), eq(null), eq(null), eq(null),
				eq(null), eq(null),
				eq(null), eq(null),
				eq(null), eq(null),
				eq(null), eq(null),
				eq(null), eq(Set.of(CacheScope.TENANT, CacheScope.GLOBAL)), eq(null),
				eq(null), eq(null)
		)).thenReturn(Optional.of(metadata));

		UpdateKeyRequest request = new UpdateKeyRequest(
				null, null, null,
				null, null,
				null, null,
				null, null,
				null, null,
				null, Set.of(CacheScope.TENANT, CacheScope.GLOBAL), null
		);

		ResponseEntity<KeyResponse> response = controller.updateKey(hash.hex(), request);

		assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
		assertThat(response.getBody().allowedCacheScopes())
				.isEqualTo(Set.of(CacheScope.TENANT, CacheScope.GLOBAL));
	}

	@Test
	@DisplayName("update with tools plus injection flag takes the full path")
	void updateToolsPlusInjection() {
		SHA256Hash hash = SHA256Hash.fromRawKey("gw-secretToolsFlag1");
		VirtualApiKey metadata = new VirtualApiKey(
				hash, "gw-", "owner-1", "toolsflag-key", 60, 1000,
				Set.of(), Set.of(),
				Set.of("postgres__*"), Set.of(),
				Set.of(), Set.of(),
				Set.of(), Set.of(),
				false,
				true, Instant.now()
		);
		when(keyManagementService.updateKey(
				eq(hash), eq(null), eq(null), eq(null),
				eq(null), eq(null),
				eq(Set.of("postgres__*")), eq(Set.of()),
				eq(null), eq(null),
				eq(null), eq(null),
				eq(false), eq(null), eq(null),
				eq(null), eq(null)
		)).thenReturn(Optional.of(metadata));

		UpdateKeyRequest request = new UpdateKeyRequest(
				null, null, null,
				null, null,
				Set.of("postgres__*"), Set.of(),
				null, null,
				null, null,
				false, null, null
		);

		ResponseEntity<KeyResponse> response = controller.updateKey(hash.hex(), request);

		assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
		assertThat(response.getBody().injectionBlock()).isFalse();
	}

	@Test
	@DisplayName("update with visibility plus scopes takes the full path")
	void updateVisibilityPlusScopes() {
		SHA256Hash hash = SHA256Hash.fromRawKey("gw-secretVisScopes1");
		VirtualApiKey metadata = new VirtualApiKey(
				hash, "gw-", "owner-1", "visscopes-key", 60, 1000,
				Set.of(), Set.of(),
				Set.of(), Set.of(),
				Set.of("postgres://*"), Set.of(),
				Set.of(), Set.of(),
				true,
				true, Instant.now(),
				Set.of(CacheScope.GLOBAL)
		);
		when(keyManagementService.updateKey(
				eq(hash), eq(null), eq(null), eq(null),
				eq(null), eq(null),
				eq(null), eq(null),
				eq(Set.of("postgres://*")), eq(Set.of()),
				eq(null), eq(null),
				eq(null), eq(Set.of(CacheScope.GLOBAL)), eq(null),
				eq(null), eq(null)
		)).thenReturn(Optional.of(metadata));

		UpdateKeyRequest request = new UpdateKeyRequest(
				null, null, null,
				null, null,
				null, null,
				Set.of("postgres://*"), Set.of(),
				null, null,
				null, Set.of(CacheScope.GLOBAL), null
		);

		ResponseEntity<KeyResponse> response = controller.updateKey(hash.hex(), request);

		assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
		assertThat(response.getBody().allowedResources()).containsExactly("postgres://*");
	}

	@Test
	@DisplayName("listKeys returns list of safe key responses")
	void listKeysSuccess() {
		SHA256Hash hash = SHA256Hash.fromRawKey("gw-key1");
		VirtualApiKey key = new VirtualApiKey(
				hash, "gw-", "owner-1", "test-key", 60, 1000,
				Set.of(), Set.of(), true, Instant.now()
		);

		when(keyManagementService.listKeys("owner-1")).thenReturn(List.of(key));

		ResponseEntity<List<KeyResponse>> response = controller.listKeys("owner-1");

		assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
		assertThat(response.getBody()).hasSize(1);
		assertThat(response.getBody().getFirst().keyId()).isEqualTo(hash.hex());

		// List without owner filter
		VirtualApiKey keyNullHash = new VirtualApiKey(
				null, "gw-", "owner-1", "test-key", 60, 1000,
				Set.of(), Set.of(), true, Instant.now()
		);
		when(keyManagementService.listKeys(null)).thenReturn(List.of(key, keyNullHash));
		ResponseEntity<List<KeyResponse>> allResponse = controller.listKeys(null);
		assertThat(allResponse.getStatusCode()).isEqualTo(HttpStatus.OK);
		assertThat(allResponse.getBody()).hasSize(2);
		assertThat(allResponse.getBody().get(1).keyId()).isEmpty();
	}

	@Test
	@DisplayName("getKey returns 200 OK when found, 404 when absent, 400 on invalid hex")
	void getKeyScenarios() {
		SHA256Hash hash = SHA256Hash.fromRawKey("gw-key1");
		VirtualApiKey key = new VirtualApiKey(
				hash, "gw-", "owner-1", "test-key", 60, 1000,
				Set.of(), Set.of(), true, Instant.now()
		);

		when(keyManagementService.findByHash(hash)).thenReturn(Optional.of(key));
		when(keyManagementService.findByHash(argThat(h -> h != null && !h.equals(hash)))).thenReturn(Optional.empty());

		// Found
		ResponseEntity<KeyResponse> found = controller.getKey(hash.hex());
		assertThat(found.getStatusCode()).isEqualTo(HttpStatus.OK);
		assertThat(found.getBody()).isNotNull();
		assertThat(found.getBody().keyId()).isEqualTo(hash.hex());

		// Not Found
		SHA256Hash missingHash = SHA256Hash.fromRawKey("gw-missing");
		ResponseEntity<KeyResponse> notFound = controller.getKey(missingHash.hex());
		assertThat(notFound.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);

		// Invalid Hex Scenarios
		assertThatThrownBy(() -> controller.getKey(null))
				.isInstanceOf(ResponseStatusException.class);
		assertThatThrownBy(() -> controller.getKey("short-hex"))
				.isInstanceOf(ResponseStatusException.class);
		assertThatThrownBy(() -> controller.getKey("z".repeat(64)))
				.isInstanceOf(ResponseStatusException.class);
	}

	@Test
	@DisplayName("updateKey returns 200 OK when updated, 404 when not found")
	void updateKeyScenarios() {
		SHA256Hash hash = SHA256Hash.fromRawKey("gw-key1");
		VirtualApiKey updated = new VirtualApiKey(
				hash, "gw-", "owner-1", "renamed-key", 120, 2000,
				Set.of("claude-3-5"), Set.of("anthropic"), true, Instant.now()
		);

		when(keyManagementService.updateKey(
				eq(hash), eq("renamed-key"), eq(120), eq(2000),
				eq(Set.of("claude-3-5")), eq(Set.of("anthropic")),
				eq(null), eq(null), eq(null), eq(null), eq(null), eq(null),
				eq(null), eq(null), eq(null), eq(null), eq(true)
		)).thenReturn(Optional.of(updated));

		UpdateKeyRequest request = new UpdateKeyRequest(
				"renamed-key", 120, 2000,
				Set.of("claude-3-5"), Set.of("anthropic"), true
		);

		ResponseEntity<KeyResponse> response = controller.updateKey(hash.hex(), request);
		assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
		assertThat(response.getBody()).isNotNull();
		assertThat(response.getBody().name()).isEqualTo("renamed-key");

		// Update with tools
		UpdateKeyRequest requestTools = new UpdateKeyRequest(
				"renamed-key-tools", 120, 2000,
				Set.of(), Set.of(),
				Set.of("postgres__*"), Set.of("*:delete_*"),
				true
		);
		VirtualApiKey updatedWithTools = new VirtualApiKey(
				hash, "gw-", "owner-1", "renamed-key-tools", 120, 2000,
				Set.of(), Set.of(),
				Set.of("postgres__*"), Set.of("*:delete_*"),
				true, Instant.now()
		);
		when(keyManagementService.updateKey(
				eq(hash), eq("renamed-key-tools"), eq(120), eq(2000),
				eq(Set.of()), eq(Set.of()),
				eq(Set.of("postgres__*")), eq(Set.of("*:delete_*")),
				eq(null), eq(null), eq(null), eq(null), eq(null), eq(null),
				eq(null), eq(null), eq(true)
		)).thenReturn(Optional.of(updatedWithTools));

		ResponseEntity<KeyResponse> responseTools = controller.updateKey(hash.hex(), requestTools);
		assertThat(responseTools.getStatusCode()).isEqualTo(HttpStatus.OK);
		assertThat(responseTools.getBody().allowedTools()).containsExactly("postgres__*");

		// Not found
		SHA256Hash missingHash = SHA256Hash.fromRawKey("gw-missing");
		ResponseEntity<KeyResponse> notFound = controller.updateKey(missingHash.hex(), request);
		assertThat(notFound.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
	}

	@Test
	@DisplayName("deleteKey returns 204 No Content when deleted, 404 when not found")
	void deleteKeyScenarios() {
		SHA256Hash hash = SHA256Hash.fromRawKey("gw-key1");
		when(keyManagementService.deleteKey(hash)).thenReturn(true);

		SHA256Hash missingHash = SHA256Hash.fromRawKey("gw-missing");
		when(keyManagementService.deleteKey(missingHash)).thenReturn(false);

		ResponseEntity<Void> deleted = controller.deleteKey(hash.hex());
		assertThat(deleted.getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);

		ResponseEntity<Void> notFound = controller.deleteKey(missingHash.hex());
		assertThat(notFound.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
	}

	@Test
	@DisplayName("reassignment to an unknown owner answers 404")
	void reassignUnknownOwnerAnswers404() {
		SHA256Hash hash = SHA256Hash.fromRawKey("gw-reassign1");
		UUID ghost = UUID.randomUUID();
		when(keyManagementService.assignOwner(eq(hash), eq(ghost))).thenReturn(Optional.empty());

		UpdateKeyRequest request = new UpdateKeyRequest(
				null, null, null, null, null, null, null, null, null, null,
				null, null, null, null, null, null, ghost);

		ResponseEntity<KeyResponse> response = controller.updateKey(hash.hex(), request);

		assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
	}
}
