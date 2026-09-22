package io.github.kxng0109.cacherelay.security.ratelimit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.HashOperations;
import org.springframework.data.redis.core.SetOperations;
import org.springframework.data.redis.core.StringRedisTemplate;

import io.github.kxng0109.cacherelay.contracts.SHA256Hash;
import io.github.kxng0109.cacherelay.contracts.VirtualApiKey;

/**
 * Ownership and terminal-revocation persistence for virtual keys: legacy
 * constructors default to unlinked/enabled, Redis round-trips carry both new
 * fields, absent fields read as legacy-tolerant defaults, and malformed owner
 * ids fail closed.
 */
@DisplayName("KeyOwnership")
class KeyOwnershipTest {

	private static final String FIXED_PLAINTEXT = "gw-abcdefghijklmnopqrstuvwxyz012345";

	private final StringRedisTemplate redisTemplate = mock(StringRedisTemplate.class);
	private final HashOperations<String, String, String> hashOps = mock(HashOperations.class);
	private final SetOperations<String, String> setOps = mock(SetOperations.class);

	private KeyManagementService newService() {
		when(redisTemplate.<String, String>opsForHash()).thenReturn(hashOps);
		when(redisTemplate.<String, String>opsForSet()).thenReturn(setOps);
		return new KeyManagementService(redisTemplate);
	}

	private static SHA256Hash hashOf(String plaintext) {
		return SHA256Hash.fromRawKey(plaintext);
	}

	private static String redisKey(SHA256Hash hash) {
		return "apikey:" + hash.hex();
	}

	private void stubEntries(SHA256Hash hash, Map<String, String> entries) {
		when(redisTemplate.hasKey(redisKey(hash))).thenReturn(Boolean.TRUE);
		when(hashOps.entries(redisKey(hash))).thenReturn(entries);
	}

	private static Map<String, String> baseFields() {
		Map<String, String> fields = new LinkedHashMap<>();
		fields.put("ownerId", "tenant-a");
		fields.put("name", "key");
		fields.put("rpmLimit", "60");
		fields.put("tpmLimit", "1000");
		fields.put("enabled", "true");
		fields.put("allowedModels", "");
		fields.put("allowedProviders", "");
		fields.put("createdAt", "2026-08-29T10:00:00Z");
		fields.put("keyPrefix", "gw-");
		return fields;
	}

	@Test
	@DisplayName("legacy constructors default to unlinked owner and non-revoked")
	void legacyConstructorsDefault() {
		SHA256Hash hash = hashOf(FIXED_PLAINTEXT);
		Instant now = Instant.parse("2026-08-29T10:00:00Z");
		VirtualApiKey legacy = new VirtualApiKey(
				hash, "gw-", "tenant-a", "key", 60, 1000,
				Set.of(), Set.of(), true, now);

		assertNull(legacy.ownerUserId());
		assertFalse(legacy.revoked());
	}

	@Test
	@DisplayName("canonical constructor carries owner and revocation")
	void canonicalCarriesOwnership() {
		SHA256Hash hash = hashOf(FIXED_PLAINTEXT);
		Instant now = Instant.parse("2026-08-29T10:00:00Z");
		UUID owner = UUID.randomUUID();
		VirtualApiKey key = new VirtualApiKey(
				hash, "gw-", "tenant-a", "key", 60, 1000,
				Set.of(), Set.of(), Set.of(), Set.of(),
				Set.of(), Set.of(), Set.of(), Set.of(),
				true, true, now, Set.of(), Set.of(), Set.of(),
				owner, true);

		assertEquals(owner, key.ownerUserId());
		assertTrue(key.revoked());
	}

	@Test
	@DisplayName("Redis round-trip carries owner and revocation")
	void roundTripsOwnership() {
		SHA256Hash hash = hashOf(FIXED_PLAINTEXT);
		UUID owner = UUID.randomUUID();
		Map<String, String> entries = baseFields();
		entries.put("ownerUserId", owner.toString());
		entries.put("revoked", "true");
		stubEntries(hash, entries);

		Optional<VirtualApiKey> found = newService().findByHash(hash);

		assertTrue(found.isPresent());
		assertEquals(owner, found.get().ownerUserId());
		assertTrue(found.get().revoked());
	}

	@Test
	@DisplayName("absent ownership fields read as legacy-tolerant defaults")
	void absentFieldsReadLegacy() {
		SHA256Hash hash = hashOf(FIXED_PLAINTEXT);
		stubEntries(hash, baseFields());

		Optional<VirtualApiKey> found = newService().findByHash(hash);

		assertTrue(found.isPresent());
		assertNull(found.get().ownerUserId());
		assertFalse(found.get().revoked());
	}

	@Test
	@DisplayName("malformed owner ids fail closed to empty")
	void malformedOwnerFailsClosed() {
		SHA256Hash hash = hashOf(FIXED_PLAINTEXT);
		Map<String, String> entries = baseFields();
		entries.put("ownerUserId", "not-a-uuid");
		stubEntries(hash, entries);

		Optional<VirtualApiKey> found = newService().findByHash(hash);

		assertTrue(found.isEmpty());
	}

	@Test
	@DisplayName("malformed revocation flags fail closed to empty")
	void malformedRevokedFailsClosed() {
		SHA256Hash hash = hashOf(FIXED_PLAINTEXT);
		Map<String, String> entries = baseFields();
		entries.put("ownerUserId", UUID.randomUUID().toString());
		entries.put("revoked", "maybe");
		stubEntries(hash, entries);

		Optional<VirtualApiKey> found = newService().findByHash(hash);

		assertTrue(found.isEmpty());
	}

	@Test
	@DisplayName("blank owner ids read as legacy-tolerant null")
	void blankOwnerReadsLegacy() {
		SHA256Hash hash = hashOf(FIXED_PLAINTEXT);
		Map<String, String> entries = baseFields();
		entries.put("ownerUserId", "   ");
		stubEntries(hash, entries);

		Optional<VirtualApiKey> found = newService().findByHash(hash);

		assertTrue(found.isPresent());
		assertNull(found.get().ownerUserId());
	}

	@Test
	@DisplayName("null agent sets default to empty")
	void nullAgentSetsDefault() {
		SHA256Hash hash = hashOf(FIXED_PLAINTEXT);
		Instant now = Instant.parse("2026-08-29T10:00:00Z");
		VirtualApiKey key = new VirtualApiKey(
				hash, "gw-", "tenant-a", "key", 60, 1000,
				Set.of(), Set.of(), Set.of(), Set.of(),
				Set.of(), Set.of(), Set.of(), Set.of(),
				true, true, now, Set.of(), null, null,
				UUID.randomUUID(), false);

		assertTrue(key.allowedAgents().isEmpty());
		assertTrue(key.deniedAgents().isEmpty());
	}
}
