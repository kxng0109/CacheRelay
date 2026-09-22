package io.github.kxng0109.cacherelay.security.ratelimit;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.data.redis.core.HashOperations;
import org.springframework.data.redis.core.SetOperations;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import io.github.kxng0109.cacherelay.auth.UserAccount;
import io.github.kxng0109.cacherelay.auth.UserAccountRepository;
import io.github.kxng0109.cacherelay.contracts.BootstrapKey;
import io.github.kxng0109.cacherelay.contracts.GatewayProperties;
import io.github.kxng0109.cacherelay.contracts.SHA256Hash;
import io.github.kxng0109.cacherelay.contracts.VirtualApiKey;

/**
 * Lifecycle rules for user-attached keys: mandatory active owners, terminal
 * revocation nothing can undo, owner-scoped listing and cascade, and
 * fail-closed bootstrap seeding.
 */
@DisplayName("KeyLifecycle")
class KeyLifecycleTest {

	private static final String FIXED_PLAINTEXT = "gw-abcdefghijklmnopqrstuvwxyz012345";

	private StringRedisTemplate redisTemplate;
	private HashOperations<String, String, String> hashOps;
	private SetOperations<String, String> setOps;
	private UserAccountRepository users;
	private KeyManagementService service;
	private UUID ownerId;

	private ValueOperations<String, String> valueOps;

	@BeforeEach
	void setUp() {
		redisTemplate = mock(StringRedisTemplate.class);
		hashOps = mock(HashOperations.class);
		setOps = mock(SetOperations.class);
		valueOps = mock(ValueOperations.class);
		when(redisTemplate.<String, String>opsForHash()).thenReturn(hashOps);
		when(redisTemplate.<String, String>opsForSet()).thenReturn(setOps);
		when(redisTemplate.<String, String>opsForValue()).thenReturn(valueOps);
		when(redisTemplate.<String, String>opsForValue()).thenReturn(valueOps);
		users = mock(UserAccountRepository.class);
		service = new KeyManagementService(redisTemplate);
		service.setUserAccountRepository(users);
		ownerId = UUID.randomUUID();
		UserAccount owner = new UserAccount("local", null, null, false);
		when(users.findById(ownerId)).thenReturn(Optional.of(owner));
	}

	private static SHA256Hash hashOf(String plaintext) {
		return SHA256Hash.fromRawKey(plaintext);
	}

	private static String redisKey(SHA256Hash hash) {
		return "apikey:" + hash.hex();
	}

	private KeyManagementService.CreatedKey createOwned() {
		return service.createKey(
				"tenant-a", "key", 60, 1000, Set.of(), Set.of(), Set.of(), Set.of(),
				Set.of(), Set.of(), Set.of(), Set.of(), null, null,
				Set.of(), Set.of(), ownerId);
	}

	private void stubEntries(SHA256Hash hash, Map<String, String> entries) {
		when(redisTemplate.hasKey(redisKey(hash))).thenReturn(Boolean.TRUE);
		when(hashOps.entries(redisKey(hash))).thenReturn(entries);
	}

	private static Map<String, String> baseFields(UUID owner, boolean enabled, boolean revoked) {
		Map<String, String> fields = new LinkedHashMap<>();
		fields.put("ownerId", "tenant-a");
		fields.put("name", "key");
		fields.put("rpmLimit", "60");
		fields.put("tpmLimit", "1000");
		fields.put("enabled", Boolean.toString(enabled));
		fields.put("allowedModels", "");
		fields.put("allowedProviders", "");
		fields.put("createdAt", "2026-08-29T10:00:00Z");
		fields.put("keyPrefix", "gw-");
		if (owner != null) {
			fields.put("ownerUserId", owner.toString());
		}
		fields.put("revoked", Boolean.toString(revoked));
		return fields;
	}

	@Test
	@DisplayName("creating without an owner fails fast")
	void createRequiresOwner() {
		assertThrows(IllegalArgumentException.class, () -> service.createKey(
				"tenant-a", "key", 60, 1000, Set.of(), Set.of(), Set.of(), Set.of(),
				Set.of(), Set.of(), Set.of(), Set.of(), null, null,
				Set.of(), Set.of(), null));
		verify(hashOps, never()).putAll(any(), any());
	}

	@Test
	@DisplayName("creating for unknown or disabled owners fails fast")
	void createRequiresActiveOwner() {
		UUID unknown = UUID.randomUUID();
		when(users.findById(unknown)).thenReturn(Optional.empty());
		UserAccount disabled = mock(UserAccount.class);
		when(disabled.isDisabled()).thenReturn(true);
		UUID disabledId = UUID.randomUUID();
		when(users.findById(disabledId)).thenReturn(Optional.of(disabled));

		assertThrows(IllegalArgumentException.class, () -> service.createKey(
				"t", "k", 1, 1, Set.of(), Set.of(), Set.of(), Set.of(),
				Set.of(), Set.of(), Set.of(), Set.of(), null, null,
				Set.of(), Set.of(), unknown));
		assertThrows(IllegalArgumentException.class, () -> service.createKey(
				"t", "k", 1, 1, Set.of(), Set.of(), Set.of(), Set.of(),
				Set.of(), Set.of(), Set.of(), Set.of(), null, null,
				Set.of(), Set.of(), disabledId));
	}

	@Test
	@DisplayName("created keys persist the owner")
	void createdKeysPersistOwner() {
		KeyManagementService.CreatedKey created = createOwned();

		assertEquals(ownerId, created.key().ownerUserId());
		@SuppressWarnings("unchecked")
		ArgumentCaptor<Map<String, String>> fieldsCaptor = ArgumentCaptor.forClass(Map.class);
		verify(hashOps).putAll(any(), fieldsCaptor.capture());
		assertEquals(ownerId.toString(), fieldsCaptor.getValue().get("ownerUserId"));
		assertEquals("false", fieldsCaptor.getValue().get("revoked"));
	}

	@Test
	@DisplayName("revocation is terminal: tombstone set, enabled cleared, never reversible")
	void revocationIsTerminal() {
		KeyManagementService.CreatedKey created = createOwned();
		SHA256Hash hash = created.hash();

		service.revokeKey(hash);

		verify(hashOps).put(redisKey(hash), "revoked", "true");
		verify(hashOps).put(redisKey(hash), "enabled", "false");

		stubEntries(hash, baseFields(ownerId, false, true));
		Optional<VirtualApiKey> reloaded = service.findByHash(hash);
		assertTrue(reloaded.isPresent());
		assertTrue(reloaded.get().revoked());

		assertThrows(IllegalArgumentException.class, () -> service.updateKey(
				hash, null, null, null, null, null, null, null, null, null,
				null, null, null, null, null, null, Boolean.TRUE));
	}

	@Test
	@DisplayName("reversible disable clears enabled without tombstoning")
	void reversibleDisableKeepsNoTombstone() {
		KeyManagementService.CreatedKey created = createOwned();
		SHA256Hash hash = created.hash();
		stubEntries(hash, baseFields(ownerId, true, false));
		when(hashOps.entries(redisKey(hash))).thenReturn(
				baseFields(ownerId, true, false), baseFields(ownerId, false, false));

		Optional<VirtualApiKey> updated = service.updateKey(
				hash, null, null, null, null, null, null, null, null, null,
				null, null, null, null, null, null, Boolean.FALSE);

		assertTrue(updated.isPresent());
		assertFalse(updated.get().enabled());
		assertFalse(updated.get().revoked());
	}

	@Test
	@DisplayName("owner reassignment validates the new owner")
	void assignOwnerValidates() {
		KeyManagementService.CreatedKey created = createOwned();
		SHA256Hash hash = created.hash();
		UUID other = UUID.randomUUID();
		UserAccount account = new UserAccount("other", null, null, false);
		when(users.findById(other)).thenReturn(Optional.of(account));
		stubEntries(hash, baseFields(other, true, false));

		Optional<VirtualApiKey> moved = service.assignOwner(hash, other);

		verify(hashOps).put(redisKey(hash), "ownerUserId", other.toString());
		assertTrue(moved.isPresent());
		assertEquals(other, moved.get().ownerUserId());

		UUID unknown = UUID.randomUUID();
		when(users.findById(unknown)).thenReturn(Optional.empty());
		assertThrows(IllegalArgumentException.class, () -> service.assignOwner(created.hash(), unknown));
		assertThrows(IllegalArgumentException.class, () -> service.assignOwner(created.hash(), null));
	}

	@Test
	@DisplayName("user revocation cascades terminally across all owned keys")
	void cascadeRevokesUserKeys() {
		when(redisTemplate.opsForSet()).thenReturn(setOps);
		SHA256Hash first = hashOf(FIXED_PLAINTEXT);
		SHA256Hash second = hashOf("gw-12345678901234567890123456789012");
		when(setOps.members(any())).thenReturn(Set.of(first.hex(), second.hex()));
		stubEntries(first, baseFields(ownerId, true, false));
		stubEntries(second, baseFields(ownerId, true, false));

		int revoked = service.revokeUserKeys(ownerId);

		assertEquals(2, revoked);
		verify(hashOps).put(redisKey(first), "revoked", "true");
		verify(hashOps).put(redisKey(second), "revoked", "true");
	}

	@Test
	@DisplayName("usability requires an enabled, non-revoked key with an active owner")
	void usabilityMatrix() {
		VirtualApiKey usable = new VirtualApiKey(
				hashOf(FIXED_PLAINTEXT), "gw-", "t", "k", 1, 1,
				Set.of(), Set.of(), Set.of(), Set.of(), Set.of(), Set.of(),
				Set.of(), Set.of(), true, true,
				Instant.now(), Set.of(), Set.of(), Set.of(), ownerId, false);
		assertTrue(service.isUsable(usable));

		VirtualApiKey revoked = new VirtualApiKey(
				hashOf(FIXED_PLAINTEXT), "gw-", "t", "k", 1, 1,
				Set.of(), Set.of(), Set.of(), Set.of(), Set.of(), Set.of(),
				Set.of(), Set.of(), true, false,
				Instant.now(), Set.of(), Set.of(), Set.of(), ownerId, true);
		assertFalse(service.isUsable(revoked));

		VirtualApiKey unowned = new VirtualApiKey(
				hashOf(FIXED_PLAINTEXT), "gw-", "t", "k", 1, 1,
				Set.of(), Set.of(), Set.of(), Set.of(), Set.of(), Set.of(),
				Set.of(), Set.of(), true, true,
				Instant.now(), Set.of(), Set.of(), Set.of(), null, false);
		assertFalse(service.isUsable(unowned));

		UUID gone = UUID.randomUUID();
		when(users.findById(gone)).thenReturn(Optional.empty());
		VirtualApiKey orphaned = new VirtualApiKey(
				hashOf(FIXED_PLAINTEXT), "gw-", "t", "k", 1, 1,
				Set.of(), Set.of(), Set.of(), Set.of(), Set.of(), Set.of(),
				Set.of(), Set.of(), true, true,
				Instant.now(), Set.of(), Set.of(), Set.of(), gone, false);
		assertFalse(service.isUsable(orphaned));
	}

	@Test
	@DisplayName("listing by user returns only owned keys")
	void listsByUser() {
		SHA256Hash mine = hashOf(FIXED_PLAINTEXT);
		SHA256Hash theirs = hashOf("gw-12345678901234567890123456789012");
		when(setOps.members(any())).thenReturn(Set.of(mine.hex(), theirs.hex()));
		stubEntries(mine, baseFields(ownerId, true, false));
		stubEntries(theirs, baseFields(UUID.randomUUID(), true, false));

		List<VirtualApiKey> listed = service.listKeysByUser(ownerId);

		assertEquals(1, listed.size());
		assertEquals(ownerId, listed.getFirst().ownerUserId());
	}

	@Test
	@DisplayName("generated keys resolve template owners when wired")
	void generateKeyResolvesOwner() {
		UserAccount local = new UserAccount("local", null, null, false);
		when(users.findByUsernameIgnoreCase("local")).thenReturn(Optional.of(local));

		service.generateKey(template("local"));

		@SuppressWarnings("unchecked")
		ArgumentCaptor<Map<String, String>> fieldsCaptor = ArgumentCaptor.forClass(Map.class);
		verify(hashOps).putAll(any(), fieldsCaptor.capture());
		assertEquals(local.getId().toString(), fieldsCaptor.getValue().get("ownerUserId"));
	}

	private BootstrapKey template(String ownerUsername) {
		return new BootstrapKey(
				"tenant-a", "seed", "gw-seed-key-00000000000000000000001", 5, 50,
				Set.of(), Set.of(), Set.of(), Set.of(), Set.of(), Set.of(), Set.of(), Set.of(),
				Set.of(), ownerUsername);
	}

	@Test
	@DisplayName("generation fails fast on blank or unknown owners when wired")
	void generateKeyRejectsBadOwners() {
		assertThrows(IllegalArgumentException.class, () -> service.generateKey(template("  ")));
		assertThrows(IllegalArgumentException.class, () -> service.generateKey(template("ghost")));
	}

	@Test
	@DisplayName("generation tolerates owners only when unwired")
	void generateKeyUnwiredTolerates() {
		KeyManagementService unwired = new KeyManagementService(redisTemplate);

		String plaintext = unwired.generateKey(template("local"));

		assertEquals(null, unwired.findByHash(SHA256Hash.fromRawKey(plaintext))
				.map(VirtualApiKey::ownerUserId).orElse(null));
	}

	@Test
	@DisplayName("seeding skips blank and unknown owners when wired")
	void seedSkipsBadOwners() {
		GatewayProperties properties = new GatewayProperties();
		properties.setBootstrapKeys(List.of(template(null), template("ghost")));

		service.seedBootstrapKeys(properties);

		verify(redisTemplate, never()).execute(any(), anyList(), any(Object[].class));
	}

	@Test
	@DisplayName("seeding persists resolved owners")
	void seedPersistsResolvedOwner() {
		when(users.findByUsernameIgnoreCase("local"))
				.thenReturn(Optional.of(new UserAccount("local", null, null, false)));
		when(redisTemplate.execute(any(), anyList(), any(Object[].class))).thenReturn(1L);
		GatewayProperties properties = new GatewayProperties();
		properties.setBootstrapKeys(List.of(template("local")));

		service.seedBootstrapKeys(properties);

		verify(redisTemplate).execute(any(), anyList(), any(Object[].class));
	}

	@Test
	@DisplayName("username lookup degrades to null without information")
	void usernameOfDegrades() {
		assertNull(service.usernameOf(null));

		KeyManagementService unwired = new KeyManagementService(redisTemplate);
		assertNull(unwired.usernameOf(ownerId));

		when(users.findById(ownerId)).thenReturn(Optional.empty());
		assertNull(service.usernameOf(ownerId));

		when(users.findById(ownerId)).thenThrow(new RuntimeException("db down"));
		assertNull(service.usernameOf(ownerId));
	}

	@Test
	@DisplayName("default selection reads empty for null, blank, and malformed values")
	void defaultReadsEmpty() {
		assertTrue(service.defaultKey(null).isEmpty());

		KeyManagementService probed = new KeyManagementService(redisTemplate);
		probed.setUserAccountRepository(users);
		when(valueOps.get("userkey:" + ownerId + ":default")).thenReturn("  ", "not-hex");
		assertTrue(probed.defaultKey(ownerId).isEmpty());
		assertTrue(probed.defaultKey(ownerId).isEmpty());
	}

	@Test
	@DisplayName("clearing and user-scoped operations tolerate null owners")
	void nullOwnersTolerated() {
		assertDoesNotThrow(() -> service.clearDefaultKey(null));
		assertTrue(service.listKeysByUser(null).isEmpty());
		assertEquals(0, service.revokeUserKeys(null));
	}

	@Test
	@DisplayName("user listing skips empty indexes and invalid hex")
	void userListingSkipsGarbage() {
		when(setOps.members(any())).thenReturn(Set.of(), Set.of("not-hex"));
		assertTrue(service.listKeysByUser(ownerId).isEmpty());
		assertTrue(service.listKeysByUser(ownerId).isEmpty());
	}

	@Test
	@DisplayName("reassignment and updates of missing keys read empty")
	void missingKeysReadEmpty() {
		SHA256Hash missing = hashOf("gw-missing-key-00000000000000000001");
		when(redisTemplate.hasKey(redisKey(missing))).thenReturn(Boolean.FALSE);

		assertTrue(service.assignOwner(missing, ownerId).isEmpty());
		assertTrue(service.updateKey(missing, null, null, null, null, null, null, null, null, null,
				null, null, null, null, null, null, null).isEmpty());
	}

	@Test
	@DisplayName("owner activity caches across lookups and honors disabled accounts")
	void ownerActivityCached() {
		UserAccount disabled = mock(UserAccount.class);
		when(disabled.isDisabled()).thenReturn(true);
		UUID disabledId = UUID.randomUUID();
		when(users.findById(disabledId)).thenReturn(Optional.of(disabled));

		assertTrue(service.isOwnerActive(ownerId));
		assertTrue(service.isOwnerActive(ownerId));
		verify(users, times(1)).findById(ownerId);
		assertFalse(service.isOwnerActive(disabledId));
	}

	@Test
	@DisplayName("default selection rejects unknown keys")
	void defaultRejectsUnknown() {
		SHA256Hash missing = hashOf("gw-missing-key-00000000000000000001");
		when(redisTemplate.hasKey(redisKey(missing))).thenReturn(Boolean.FALSE);

		assertThrows(IllegalArgumentException.class, () -> service.setDefaultKey(ownerId, missing));
	}

	@Test
	@DisplayName("unindexed listing reads empty")
	void unindexedListingReadsEmpty() {
		when(setOps.members(any())).thenReturn(null);

		assertTrue(service.listKeys(null).isEmpty());
	}
}
