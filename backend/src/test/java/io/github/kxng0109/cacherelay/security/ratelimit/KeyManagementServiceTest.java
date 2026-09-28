package io.github.kxng0109.cacherelay.security.ratelimit;

import io.github.kxng0109.cacherelay.auth.JwtService;
import io.github.kxng0109.cacherelay.auth.UserAccount;
import io.github.kxng0109.cacherelay.auth.UserAccountRepository;
import io.github.kxng0109.cacherelay.cache.contracts.CacheScope;
import io.github.kxng0109.cacherelay.contracts.BootstrapKey;
import io.github.kxng0109.cacherelay.contracts.GatewayProperties;
import io.github.kxng0109.cacherelay.contracts.SHA256Hash;
import io.github.kxng0109.cacherelay.contracts.VirtualApiKey;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.data.redis.core.HashOperations;
import org.springframework.data.redis.core.RedisCallback;
import org.springframework.data.redis.core.SetOperations;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.security.oauth2.jwt.Jwt;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@SuppressWarnings("DataFlowIssue")
class KeyManagementServiceTest {

	private static final String URL_SAFE_ALPHABET =
			"abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789-_";
	private static final String FIXED_PLAINTEXT = "gw-abcdefghijklmnopqrstuvwxyz012345";
	private static final String CREATED_AT = "2026-08-29T10:00:00Z";

	private final StringRedisTemplate redisTemplate = mock(StringRedisTemplate.class);
	private final HashOperations<String, String, String> hashOps = mock(HashOperations.class);
	private final SetOperations<String, String> setOps = mock(SetOperations.class);

	private KeyManagementService newService() {
		when(redisTemplate.<String, String>opsForHash()).thenReturn(hashOps);
		when(redisTemplate.<String, String>opsForSet()).thenReturn(setOps);
		when(redisTemplate.execute(any(), anyList(), any(Object[].class))).thenReturn(1L);
		return new KeyManagementService(redisTemplate);
	}

	/**
	 * Stubs the atomic-store script to succeed while recording its flat ARGV.
	 *
	 * @return reference holding the captured arguments after the call
	 */
	private AtomicReference<List<Object>> captureScriptArgv() {
		AtomicReference<List<Object>> argv = new AtomicReference<>(List.of());
		when(redisTemplate.execute(any(), anyList(), any(Object[].class))).thenAnswer(inv -> {
			Object[] all = inv.getArguments();
			List<Object> flat = new ArrayList<>();
			for (int i = 2; i < all.length; i++) {
				Object element = all[i];
				if (element instanceof Object[] nested) {
					flat.addAll(Arrays.asList(nested));
				} else {
					flat.add(element);
				}
			}
			argv.set(flat);
			return 1L;
		});
		return argv;
	}

	/**
	 * Rebuilds the stored field map from atomic-store script ARGV (flat key/value
	 * pairs plus the trailing index hex).
	 *
	 * @param argv captured script arguments
	 * @return field map as the hash would store it
	 */
	private static Map<String, String> scriptFields(List<Object> argv) {
		Map<String, String> fields = new LinkedHashMap<>();
		for (int i = 0; i + 1 < argv.size() - 1; i += 2) {
			fields.put(String.valueOf(argv.get(i)), String.valueOf(argv.get(i + 1)));
		}
		return fields;
	}

	private void stubPresent(SHA256Hash hash, Map<String, String> entries) {
		when(redisTemplate.hasKey(redisKey(hash))).thenReturn(Boolean.TRUE);
		when(hashOps.entries(redisKey(hash))).thenReturn(entries);
	}

	private static Map<String, String> fields(
			String ownerId,
			String name,
			String rpmLimit,
			String tpmLimit,
			String enabled,
			String allowedModels,
			String allowedProviders,
			String createdAt,
			String keyPrefix
	) {
		Map<String, String> fields = new LinkedHashMap<>();
		fields.put("ownerId", ownerId);
		fields.put("name", name);
		fields.put("rpmLimit", rpmLimit);
		fields.put("tpmLimit", tpmLimit);
		fields.put("enabled", enabled);
		fields.put("allowedModels", allowedModels);
		fields.put("allowedProviders", allowedProviders);
		fields.put("createdAt", createdAt);
		fields.put("keyPrefix", keyPrefix);
		return fields;
	}

	private static SHA256Hash hashOf(String plaintext) {
		return SHA256Hash.fromRawKey(plaintext);
	}

	private static String redisKey(SHA256Hash hash) {
		return "apikey:" + hash.hex();
	}

	@Test
	void generateKeyReturnsGwPrefixedPlaintextOfExactAlphabet() {
		KeyManagementService service = newService();

		String plaintext = service.generateKey(
				new BootstrapKey("owner", "name", "ignored", 5, 50, Set.of("a", "b"), Set.of("c")));

		assertTrue(plaintext.startsWith("gw-"));
		assertEquals(35, plaintext.length());
		String suffix = plaintext.substring("gw-".length());
		assertEquals(32, suffix.length());
		for (int i = 0; i < suffix.length(); i++) {
			assertTrue(
					URL_SAFE_ALPHABET.indexOf(suffix.charAt(i)) >= 0,
					"character not in URL-safe alphabet at index " + i
			);
		}
	}

	@Test
	void generateKeyStoresHashOnlyAndNeverThePlaintext() {
		KeyManagementService service = newService();
		AtomicReference<List<Object>> argv = captureScriptArgv();

		String plaintext = service.generateKey(
				new BootstrapKey("owner", "name", "ignored", 5, 50, Set.of(), Set.of()));

		String redisKey = redisKey(hashOf(plaintext));
		Map<String, String> stored = scriptFields(argv.get());
		assertFalse(stored.containsKey(plaintext));
		assertFalse(stored.containsValue(plaintext));
		for (String value : stored.values()) {
			assertFalse(value != null && value.contains(plaintext), "stored field contains the plaintext key");
		}
		assertEquals("owner", stored.get("ownerId"));
		assertEquals("name", stored.get("name"));
		assertEquals("5", stored.get("rpmLimit"));
		assertEquals("50", stored.get("tpmLimit"));
		assertEquals("true", stored.get("enabled"));
		assertEquals("", stored.get("allowedModels"));
		assertEquals("", stored.get("allowedProviders"));
		assertEquals("gw-", stored.get("keyPrefix"));
		assertNotNull(stored.get("createdAt"));
	}

	@Test
	void revokeKeyDisablesKeyAndInvalidatesCache() {
		KeyManagementService service = newService();
		SHA256Hash hash = hashOf(FIXED_PLAINTEXT);
		when(redisTemplate.hasKey(redisKey(hash))).thenReturn(Boolean.TRUE);
		when(hashOps.entries(redisKey(hash))).thenReturn(
				fields("owner", "name", "5", "50", "true", "", "", CREATED_AT, "gw-"),
				fields("owner", "name", "5", "50", "false", "", "", CREATED_AT, "gw-")
		);

		Optional<VirtualApiKey> before = service.findByHash(hash);
		service.revokeKey(hash);
		Optional<VirtualApiKey> after = service.findByHash(hash);

		verify(hashOps).put(redisKey(hash), "enabled", "false");
		verify(redisTemplate, times(2)).hasKey(redisKey(hash));
		assertTrue(before.isPresent());
		assertTrue(before.get().enabled());
		assertTrue(after.isPresent());
		assertFalse(after.get().enabled());
	}

	@Test
	void findByHashCachesWithinTtl() {
		KeyManagementService service = newService();
		SHA256Hash hash = hashOf(FIXED_PLAINTEXT);
		stubPresent(hash, fields("owner", "name", "5", "50", "true", "", "", CREATED_AT, "gw-"));

		Optional<VirtualApiKey> first = service.findByHash(hash);
		Optional<VirtualApiKey> second = service.findByHash(hash);

		assertTrue(first.isPresent());
		assertSame(first, second);
		verify(redisTemplate, times(1)).hasKey(redisKey(hash));
	}

	@Test
	void findByHashCachesNegativeResults() {
		KeyManagementService service = newService();
		SHA256Hash hash = hashOf("gw-missing");
		when(redisTemplate.hasKey(redisKey(hash))).thenReturn(Boolean.FALSE);

		Optional<VirtualApiKey> first = service.findByHash(hash);
		Optional<VirtualApiKey> second = service.findByHash(hash);

		assertTrue(first.isEmpty());
		assertTrue(second.isEmpty());
		assertSame(first, second);
		verify(redisTemplate, times(1)).hasKey(redisKey(hash));
	}

	@Test
	void findByHashRoundTripsEveryVirtualApiKeyField() {
		KeyManagementService service = newService();
		SHA256Hash hash = hashOf(FIXED_PLAINTEXT);
		Instant createdAt = Instant.parse(CREATED_AT);
		stubPresent(
				hash, fields(
						"owner-7", "prod-key", "120", "9000", "true",
						"gpt-4,gpt-4o", "openai,anthropic", createdAt.toString(), "gw-"
				)
		);

		Optional<VirtualApiKey> result = service.findByHash(hash);

		VirtualApiKey expected = new VirtualApiKey(
				hash, "gw-", "owner-7", "prod-key", 120, 9000,
				Set.of("gpt-4", "gpt-4o"), Set.of("openai", "anthropic"), true, createdAt
		);
		assertEquals(Optional.of(expected), result);
	}

	@Test
	void findByHashTrimsCsvEntriesAndDropsBlanks() {
		KeyManagementService service = newService();
		SHA256Hash hash = hashOf("gw-kkkkkkkkkkkkkkkkkkkkkkkkkkkkkkkk");
		stubPresent(
				hash, fields(
						"owner", "name", "5", "50", "true",
						" gpt-4 ,,gpt-4o ", " openai , ", CREATED_AT, "gw-"
				)
		);

		Optional<VirtualApiKey> result = service.findByHash(hash);

		assertTrue(result.isPresent());
		assertEquals(Set.of("gpt-4", "gpt-4o"), result.get().allowedModels());
		assertEquals(Set.of("openai"), result.get().allowedProviders());
	}

	@Test
	void findByHashDefaultsKeyPrefixWhenAbsent() {
		KeyManagementService service = newService();
		SHA256Hash hash = hashOf("gw-llllllllllllllllllllllllllllllll");
		Map<String, String> stored = fields("owner", "name", "5", "50", "true", "", "", CREATED_AT, "gw-");
		stored.remove("keyPrefix");
		stubPresent(hash, stored);

		Optional<VirtualApiKey> result = service.findByHash(hash);

		assertTrue(result.isPresent());
		assertEquals("gw-", result.get().keyPrefix());
	}

	@Test
	void findByHashTreatsMissingEnabledFlagAsDisabled() {
		KeyManagementService service = newService();
		SHA256Hash hash = hashOf("gw-jjjjjjjjjjjjjjjjjjjjjjjjjjjjjjjj");
		Map<String, String> stored = fields("owner", "name", "5", "50", "true", "", "", CREATED_AT, "gw-");
		stored.remove("enabled");
		stubPresent(hash, stored);

		Optional<VirtualApiKey> result = service.findByHash(hash);

		assertTrue(result.isPresent());
		assertFalse(result.get().enabled());
	}

	@Test
	void findByHashReturnsEmptyForMalformedOrIncompleteStoredData() {
		KeyManagementService service = newService();

		SHA256Hash missingRpmLimit = hashOf("gw-ffffffffffffffffffffffffffffffff");
		Map<String, String> noRpm = fields("owner", "name", "5", "50", "true", "", "", CREATED_AT, "gw-");
		noRpm.remove("rpmLimit");
		stubPresent(missingRpmLimit, noRpm);
		assertTrue(service.findByHash(missingRpmLimit).isEmpty());

		SHA256Hash malformedRpmLimit = hashOf("gw-gggggggggggggggggggggggggggggggg");
		stubPresent(
				malformedRpmLimit,
				fields("owner", "name", "not-a-number", "50", "true", "", "", CREATED_AT, "gw-")
		);
		assertTrue(service.findByHash(malformedRpmLimit).isEmpty());

		SHA256Hash malformedCreatedAt = hashOf("gw-hhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhh");
		stubPresent(
				malformedCreatedAt,
				fields("owner", "name", "5", "50", "true", "", "", "not-a-date", "gw-")
		);
		assertTrue(service.findByHash(malformedCreatedAt).isEmpty());

		SHA256Hash emptyEntries = hashOf("gw-iiiiiiiiiiiiiiiiiiiiiiiiiiiiiiii");
		when(redisTemplate.hasKey(redisKey(emptyEntries))).thenReturn(Boolean.TRUE);
		when(hashOps.entries(redisKey(emptyEntries))).thenReturn(Map.of());
		assertTrue(service.findByHash(emptyEntries).isEmpty());
	}

	@Test
	void findByHashPropagatesRedisFailuresFailClosed() {
		KeyManagementService service = newService();
		SHA256Hash hash = hashOf("gw-eeeeeeeeeeeeeeeeeeeeeeeeeeeeeeee");
		when(redisTemplate.hasKey(redisKey(hash)))
				.thenThrow(new RedisConnectionFailureException("redis down"));

		assertThrows(RedisConnectionFailureException.class, () -> service.findByHash(hash));
		// Failures are never cached: the next lookup attempts Redis again.
		assertThrows(RedisConnectionFailureException.class, () -> service.findByHash(hash));
		verify(redisTemplate, times(2)).hasKey(redisKey(hash));
	}

	@Test
	void seedBootstrapKeysStoresOnlyMissingKeysAndIsIdempotent() {
		KeyManagementService service = newService();
		BootstrapKey fresh = new BootstrapKey(
				"owner-a", "key-a", "gw-aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa", 1, 1, Set.of(), Set.of());
		BootstrapKey existing = new BootstrapKey(
				"owner-b", "key-b", "gw-bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb", 2, 2, Set.of(), Set.of());
		String freshKey = redisKey(hashOf(fresh.plaintextKey()));
		String existingKey = redisKey(hashOf(existing.plaintextKey()));
		// Atomic claim: fresh wins once, every other attempt loses (as in real Redis).
		when(redisTemplate.execute(any(), anyList(), any(Object[].class))).thenReturn(1L, 0L, 0L, 0L);

		GatewayProperties properties = new GatewayProperties();
		properties.setBootstrapKeys(List.of(fresh, existing));

		service.seedBootstrapKeys(properties);
		service.seedBootstrapKeys(properties);

		ArgumentCaptor<List> keysCaptor = ArgumentCaptor.forClass(List.class);
		verify(redisTemplate, times(4)).execute(any(), keysCaptor.capture(), any(Object[].class));
		List<List> claimed = keysCaptor.getAllValues();
		assertTrue(claimed.stream().anyMatch(keys -> keys.contains(freshKey)));
		assertTrue(claimed.stream().anyMatch(keys -> keys.contains(existingKey)));
	}

	@Test
	void seedBootstrapKeysSkipsBlankAndNullPlaintextKeys() {
		KeyManagementService service = new KeyManagementService(redisTemplate);
		GatewayProperties properties = new GatewayProperties();
		properties.setBootstrapKeys(List.of(
				new BootstrapKey("o1", "n1", null, 1, 1, Set.of(), Set.of()),
				new BootstrapKey("o2", "n2", "", 1, 1, Set.of(), Set.of()),
				new BootstrapKey("o3", "n3", "   ", 1, 1, Set.of(), Set.of())
		));

		service.seedBootstrapKeys(properties);

		verifyNoInteractions(redisTemplate);
	}

	@Test
	void seedBootstrapKeysDoesNotOverwriteExistingKey() {
		KeyManagementService service = newService();
		BootstrapKey existing = new BootstrapKey(
				"owner-b", "key-b", "gw-bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb", 2, 2, Set.of(), Set.of());
		String existingKey = redisKey(hashOf(existing.plaintextKey()));
		// Losing the atomic claim leaves the stored record untouched (single Lua round trip).
		when(redisTemplate.execute(any(), anyList(), any(Object[].class))).thenReturn(0L);

		GatewayProperties properties = new GatewayProperties();
		properties.setBootstrapKeys(List.of(existing));

		service.seedBootstrapKeys(properties);
		service.seedBootstrapKeys(properties);

		ArgumentCaptor<List> keysCaptor = ArgumentCaptor.forClass(List.class);
		verify(redisTemplate, times(2)).execute(any(), keysCaptor.capture(), any(Object[].class));
		assertTrue(keysCaptor.getAllValues().stream().allMatch(keys -> keys.contains(existingKey)));
	}

	@Test
	void seedBootstrapKeysWithEmptyListDoesNotTouchRedis() {
		KeyManagementService service = new KeyManagementService(redisTemplate);
		GatewayProperties properties = new GatewayProperties();
		properties.setBootstrapKeys(List.of());

		service.seedBootstrapKeys(properties);

		verifyNoInteractions(redisTemplate);
	}

	@Test
	void seedBootstrapKeysPropagatesRedisFailuresForTheSeederToHandle() {
		KeyManagementService service = newService();
		BootstrapKey key = new BootstrapKey(
				"owner", "name", "gw-cccccccccccccccccccccccccccccccc", 1, 1, Set.of(), Set.of());
		when(redisTemplate.execute(any(), anyList(), any(Object[].class)))
				.thenThrow(new RedisConnectionFailureException("redis down"));

		GatewayProperties properties = new GatewayProperties();
		properties.setBootstrapKeys(List.of(key));

		assertThrows(RedisConnectionFailureException.class, () -> service.seedBootstrapKeys(properties));
	}

	@Test
	void seedBootstrapKeyWithoutDashKeepsFullPlaintextAsPrefix() {
		KeyManagementService service = newService();
		String noDash = "noprefixkeyabcdefghijklmnopqrstuvwxyz";
		BootstrapKey key = new BootstrapKey("owner", "name", noDash, 10, 100, Set.of(), Set.of());
		when(redisTemplate.execute(any(), anyList(), any(Object[].class))).thenReturn(1L);

		GatewayProperties properties = new GatewayProperties();
		properties.setBootstrapKeys(List.of(key));
		service.seedBootstrapKeys(properties);

		ArgumentCaptor<Object[]> captor = ArgumentCaptor.forClass(Object[].class);
		verify(redisTemplate).execute(any(), anyList(), captor.capture());
		Object[] args = captor.getValue();
		assertEquals("keyPrefix", args[args.length - 3]);
		assertEquals(noDash, args[args.length - 2]);
	}

	@Test
	void seedBootstrapKeyWithNullAllowListsStoresEmptyCsv() {
		KeyManagementService service = newService();
		BootstrapKey key = new BootstrapKey("owner", "name", FIXED_PLAINTEXT, 10, 100, null, null);
		when(redisTemplate.execute(any(), anyList(), any(Object[].class))).thenReturn(1L);

		GatewayProperties properties = new GatewayProperties();
		properties.setBootstrapKeys(List.of(key));
		service.seedBootstrapKeys(properties);

		ArgumentCaptor<Object[]> captor = ArgumentCaptor.forClass(Object[].class);
		verify(redisTemplate).execute(any(), anyList(), captor.capture());
		Object[] args = captor.getValue();
		assertEquals("", args[11]);
		assertEquals("", args[13]);
	}

	@Test
	void missingAllowListFieldsInRedisParseToEmptySets() {
		KeyManagementService service = newService();
		SHA256Hash hash = hashOf("gw-yyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyy");
		Map<String, String> entries = new LinkedHashMap<>();
		entries.put("ownerId", "owner");
		entries.put("name", "name");
		entries.put("rpmLimit", "10");
		entries.put("tpmLimit", "100");
		entries.put("enabled", "true");
		entries.put("createdAt", CREATED_AT);
		entries.put("keyPrefix", "gw-");
		stubPresent(hash, entries);

		Optional<VirtualApiKey> result = service.findByHash(hash);

		assertTrue(result.isPresent());
		assertEquals(Set.of(), result.get().allowedModels());
		assertEquals(Set.of(), result.get().allowedProviders());
	}

	@Test
	void emptyEntriesMapDegradesToEmptyResult() {
		KeyManagementService service = newService();
		SHA256Hash hash = hashOf(FIXED_PLAINTEXT);
		when(redisTemplate.hasKey(redisKey(hash))).thenReturn(Boolean.TRUE);
		when(hashOps.entries(redisKey(hash))).thenReturn(Map.of());

		assertTrue(service.findByHash(hash).isEmpty());
	}

	@Test
	void malformedStoredMetadataDegradesToEmptyResult() {
		KeyManagementService service = newService();
		SHA256Hash hash = hashOf("gw-zzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzz");
		Map<String, String> bad = fields("owner", "name", "not-a-number", "100", "true", "", "", CREATED_AT, "gw-");
		stubPresent(hash, bad);

		assertTrue(service.findByHash(hash).isEmpty());
	}

	@Test
	void createKeyPersistsAndReturnsPlaintext() {
		KeyManagementService service = newService();

		KeyManagementService.CreatedKey created = service.createKey(
				"owner-test", "key-name", 60, 5000, Set.of("m1"), Set.of("p1"),
				Set.of(), Set.of(), Set.of(), Set.of(), Set.of(), Set.of(),
				null, null, Set.of(), Set.of(), UUID.randomUUID()
		);

		assertNotNull(created);
		assertNotNull(created.plaintextKey());
		assertTrue(created.plaintextKey().startsWith("gw-"));
		assertEquals(created.hash(), SHA256Hash.fromRawKey(created.plaintextKey()));
		assertEquals("owner-test", created.key().ownerId());
		assertEquals("key-name", created.key().name());
		assertEquals(60, created.key().rpmLimit());
		assertEquals(5000, created.key().tpmLimit());
		verify(redisTemplate).execute(
				any(), eq(List.of(redisKey(created.hash()), "admin:keys")), any(Object[].class));
		verify(setOps, never()).add(anyString(), any(String[].class));
	}

	@Test
	void createKeyRejectsMalformedTenant() {
		KeyManagementService service = newService();

		assertThrows(IllegalArgumentException.class, () -> service.createKey(
				"Victim Tenant!", "key-name", 60, 5000, Set.of("m1"), Set.of("p1"),
				Set.of(), Set.of(), Set.of(), Set.of(), Set.of(), Set.of(),
				null, null, Set.of(), Set.of(), UUID.randomUUID()
		));
	}

	@Test
	void createKeyRejectsReservedTenants() {
		KeyManagementService service = newService();

		assertThrows(IllegalArgumentException.class, () -> service.createKey(
				"global", "key-name", 60, 5000, Set.of(), Set.of(),
				Set.of(), Set.of(), Set.of(), Set.of(), Set.of(), Set.of(),
				null, null, Set.of(), Set.of(), UUID.randomUUID()
		));
		assertThrows(IllegalArgumentException.class, () -> service.createKey(
				"unknown", "key-name", 60, 5000, Set.of(), Set.of(),
				Set.of(), Set.of(), Set.of(), Set.of(), Set.of(), Set.of(),
				null, null, Set.of(), Set.of(), UUID.randomUUID()
		));
	}

	@Test
	void listKeysReturnsAllAndFilteredByOwner() {
		KeyManagementService service = newService();

		SHA256Hash hash1 = hashOf("gw-key11111111111111111111111111111");
		SHA256Hash hash2 = hashOf("gw-key22222222222222222222222222222");

		when(setOps.members("admin:keys"))
				.thenReturn(new LinkedHashSet<>(List.of(hash1.hex(), hash2.hex(), "invalid-hex-entry")));
		Map<String, String> row1 = fields("owner-1", "k1", "10", "100", "true", "", "", "2026-08-30T10:00:00Z", "gw-");
		Map<String, String> row2 = fields("owner-2", "k2", "20", "200", "true", "", "", "2026-08-31T10:00:00Z", "gw-");
		when(redisTemplate.executePipelined(any(RedisCallback.class)))
				.thenReturn(List.of(row1, row2));

		List<VirtualApiKey> all = service.listKeys(null);
		assertEquals(2, all.size());
		assertEquals(hash2, all.getFirst().keyHash()); // newer timestamp first

		List<VirtualApiKey> blankOwner = service.listKeys("   ");
		assertEquals(2, blankOwner.size());

		List<VirtualApiKey> filtered = service.listKeys("owner-1");
		assertEquals(1, filtered.size());
		assertEquals("owner-1", filtered.getFirst().ownerId());

		// Empty set in Redis
		when(setOps.members("admin:keys")).thenReturn(Set.of());
		assertTrue(service.listKeys(null).isEmpty());
	}

	@Test
	void updateKeyModifiesFieldsAndEvictsCache() {
		KeyManagementService service = newService();
		SHA256Hash hash = hashOf("gw-key11111111111111111111111111111");

		when(redisTemplate.hasKey(redisKey(hash))).thenReturn(Boolean.TRUE);
		stubPresent(hash, fields("owner-1", "k1", "10", "100", "true", "", "", CREATED_AT, "gw-"));

		Optional<VirtualApiKey> updated = service.updateKey(
				hash, "k1-renamed", 100, 2000, Set.of("modelA"), Set.of("provA"), false
		);

		assertTrue(updated.isPresent());
		verify(hashOps).putAll(eq(redisKey(hash)), anyMap());

		// Test updating each individual field
		service.updateKey(hash, "new-name", null, null, null, null, null);
		service.updateKey(hash, null, 50, null, null, null, null);
		service.updateKey(hash, null, null, 500, null, null, null);
		service.updateKey(hash, null, null, null, Set.of("m1"), null, null);
		service.updateKey(hash, null, null, null, null, Set.of("p1"), null);
		service.updateKey(hash, "tool-key", null, null, null, null, Set.of("postgres__*"), Set.of("*:delete_*"), null);
		service.updateKey(hash, null, null, null, null, null, true);

		// When update payload has all null fields (no-op updates map)
		Optional<VirtualApiKey> noOpUpdate = service.updateKey(
				hash, null, null, null, null, null, null
		);
		assertTrue(noOpUpdate.isPresent());

		// Create key with tools
		KeyManagementService.CreatedKey createdTools = service.createKey(
				"owner", "name", 10, 100, Set.of("m1"), Set.of("p1"), Set.of("postgres__*"), Set.of("*:delete_*"),
				Set.of(), Set.of(), Set.of(), Set.of(), null, null, Set.of(), Set.of(), UUID.randomUUID()
		);
		assertNotNull(createdTools);
		assertEquals(Set.of("postgres__*"), createdTools.key().allowedTools());
		assertEquals(Set.of("*:delete_*"), createdTools.key().deniedTools());

		// When key does not exist in Redis (null or false)
		SHA256Hash missingHash = hashOf("gw-missingkey");
		when(redisTemplate.hasKey(redisKey(missingHash))).thenReturn(Boolean.FALSE);
		Optional<VirtualApiKey> missing = service.updateKey(missingHash, "name", null, null, null, null, null);
		assertTrue(missing.isEmpty());

		when(redisTemplate.hasKey(redisKey(missingHash))).thenReturn(null);
		Optional<VirtualApiKey> missingNull = service.updateKey(missingHash, "name", null, null, null, null, null);
		assertTrue(missingNull.isEmpty());
	}

	@Test
	void listKeysSkipsInvalidHexEntriesInIndex() {
		KeyManagementService service = newService();
		when(setOps.members("admin:keys")).thenReturn(Set.of("invalid-hex-entry", "1234"));
		List<VirtualApiKey> keys = service.listKeys(null);
		assertTrue(keys.isEmpty());
	}

	@Test
	void deleteKeyRemovesFromRedisAndIndexSet() {
		ValueOperations<String, String> valueOps = mock(ValueOperations.class);
		when(redisTemplate.opsForValue()).thenReturn(valueOps);
		KeyManagementService service = newService();

		SHA256Hash hash = hashOf("gw-key11111111111111111111111111111");
		when(redisTemplate.delete(redisKey(hash))).thenReturn(Boolean.TRUE);

		boolean deleted = service.deleteKey(hash);
		assertTrue(deleted);
		verify(setOps).remove("admin:keys", hash.hex());

		// When delete returns false
		SHA256Hash missingHash = hashOf("gw-missing");
		when(redisTemplate.delete(redisKey(missingHash))).thenReturn(Boolean.FALSE);
		assertFalse(service.deleteKey(missingHash));
	}

	@Test
	void createKeyPersistsResourceAndPromptVisibility() {
		KeyManagementService service = newService();
		AtomicReference<List<Object>> argv = captureScriptArgv();

		KeyManagementService.CreatedKey created = service.createKey(
				"owner", "name", 5, 50, Set.of(), Set.of(), Set.of(), Set.of(),
				Set.of("postgres://*"), Set.of("postgres://secret/*"),
				Set.of(), Set.of("admin_*"), null, null, Set.of(), Set.of(), UUID.randomUUID());

		assertEquals(Set.of("postgres://*"), created.key().allowedResources());
		assertEquals(Set.of("postgres://secret/*"), created.key().deniedResources());
		assertEquals(Set.of(), created.key().allowedPrompts());
		assertEquals(Set.of("admin_*"), created.key().deniedPrompts());
		Map<String, String> stored = scriptFields(argv.get());
		assertEquals("[\"postgres://*\"]", stored.get("allowedResources"));
		assertEquals("[\"postgres://secret/*\"]", stored.get("deniedResources"));
		assertEquals("[]", stored.get("allowedPrompts"));
		assertEquals("[\"admin_*\"]", stored.get("deniedPrompts"));
	}

	@Test
	void legacyKeysWithoutVisibilityFieldsLoadAsFullyVisible() {
		KeyManagementService service = newService();
		SHA256Hash hash = hashOf(FIXED_PLAINTEXT);
		when(redisTemplate.hasKey(redisKey(hash))).thenReturn(Boolean.TRUE);
		when(hashOps.entries(redisKey(hash))).thenReturn(
				fields("owner", "name", "5", "50", "true", "", "", CREATED_AT, "gw-"));

		Optional<VirtualApiKey> loaded = service.findByHash(hash);

		assertTrue(loaded.isPresent());
		assertEquals(Set.of(), loaded.get().allowedResources());
		assertEquals(Set.of(), loaded.get().deniedResources());
		assertEquals(Set.of(), loaded.get().allowedPrompts());
		assertEquals(Set.of(), loaded.get().deniedPrompts());
	}

	@Test
	void explicitInjectionFlagRoundTripsThroughCreateAndUpdate() {
		KeyManagementService service = newService();
		AtomicReference<List<Object>> argv = captureScriptArgv();

		KeyManagementService.CreatedKey created = service.createKey(
				"owner", "name", 5, 50, Set.of(), Set.of(), Set.of(), Set.of(),
				Set.of(), Set.of(), Set.of(), Set.of(), Boolean.FALSE, null,
				Set.of(), Set.of(), UUID.randomUUID());

		assertFalse(created.key().injectionBlock());
		Map<String, String> stored = scriptFields(argv.get());
		assertEquals("false", stored.get("injectionBlock"));

		SHA256Hash hash = created.hash();
		when(redisTemplate.hasKey(redisKey(hash))).thenReturn(Boolean.TRUE);
		when(hashOps.entries(redisKey(hash))).thenReturn(
				fields("owner", "name", "5", "50", "true", "", "", CREATED_AT, "gw-"));

		Optional<VirtualApiKey> updated = service.updateKey(
				hash, null, null, null, null, null, null, null,
				null, null, null, null, Boolean.FALSE, null);

		assertTrue(updated.isPresent());
		@SuppressWarnings("unchecked")
		ArgumentCaptor<Map<String, String>> updateCaptor = ArgumentCaptor.forClass(Map.class);
		verify(hashOps, times(1))
				.putAll(eq(redisKey(hash)), updateCaptor.capture());
		assertEquals("false", updateCaptor.getAllValues().getLast().get("injectionBlock"));
	}

	@Test
	void missingInjectionFlagDefaultsToBlock() {
		KeyManagementService service = newService();
		SHA256Hash hash = hashOf(FIXED_PLAINTEXT);
		when(redisTemplate.hasKey(redisKey(hash))).thenReturn(Boolean.TRUE);
		when(hashOps.entries(redisKey(hash))).thenReturn(
				fields("owner", "name", "5", "50", "true", "", "", CREATED_AT, "gw-"));

		Optional<VirtualApiKey> loaded = service.findByHash(hash);

		assertTrue(loaded.isPresent());
		assertTrue(loaded.get().injectionBlock());
	}

	@Test
	void explicitTrueInjectionFlagRoundTrips() {
		KeyManagementService service = newService();
		AtomicReference<List<Object>> argv = captureScriptArgv();

		KeyManagementService.CreatedKey created = service.createKey(
				"owner", "name", 5, 50, Set.of(), Set.of(), Set.of(), Set.of(),
				Set.of(), Set.of(), Set.of(), Set.of(), Boolean.TRUE, null,
				Set.of(), Set.of(), UUID.randomUUID());

		assertTrue(created.key().injectionBlock());
		Map<String, String> stored = scriptFields(argv.get());
		assertEquals("true", stored.get("injectionBlock"));
	}

	@Test
	void storedFalseInjectionFlagLoadsAsFlagMode() {
		KeyManagementService service = newService();
		SHA256Hash hash = hashOf(FIXED_PLAINTEXT);
		when(redisTemplate.hasKey(redisKey(hash))).thenReturn(Boolean.TRUE);
		Map<String, String> stored = new LinkedHashMap<>(fields(
				"owner", "name", "5", "50", "true", "", "", CREATED_AT, "gw-"));
		stored.put("injectionBlock", "false");
		when(hashOps.entries(redisKey(hash))).thenReturn(stored);

		Optional<VirtualApiKey> loaded = service.findByHash(hash);

		assertTrue(loaded.isPresent());
		assertFalse(loaded.get().injectionBlock());
	}

	@Test
	void updateAllVisibilityFieldsWritesEveryColumn() {
		KeyManagementService service = newService();
		SHA256Hash hash = hashOf(FIXED_PLAINTEXT);
		when(redisTemplate.hasKey(redisKey(hash))).thenReturn(Boolean.TRUE);
		when(hashOps.entries(redisKey(hash))).thenReturn(
				fields("owner", "name", "5", "50", "true", "", "", CREATED_AT, "gw-"));

		Optional<VirtualApiKey> updated = service.updateKey(
				hash, null, null, null, null, null, null, null,
				Set.of("postgres://*"), Set.of("postgres://secret/*"),
				Set.of("review_*"), Set.of("admin_*"), Boolean.TRUE, null);

		assertTrue(updated.isPresent());
		@SuppressWarnings("unchecked")
		ArgumentCaptor<Map<String, String>> updateCaptor = ArgumentCaptor.forClass(Map.class);
		verify(hashOps).putAll(eq(redisKey(hash)), updateCaptor.capture());
		Map<String, String> written = updateCaptor.getValue();
		assertEquals("[\"postgres://*\"]", written.get("allowedResources"));
		assertEquals("[\"postgres://secret/*\"]", written.get("deniedResources"));
		assertEquals("[\"review_*\"]", written.get("allowedPrompts"));
		assertEquals("[\"admin_*\"]", written.get("deniedPrompts"));
		assertEquals("true", written.get("injectionBlock"));
	}

	@Test
	void jsonNullElementLoadsAsEmptySet() {
		KeyManagementService service = newService();
		SHA256Hash hash = hashOf("gw-nullelem00000000000000000000001");
		Map<String, String> stored = fields("owner", "name", "5", "50", "true", "", "", CREATED_AT, "gw-");
		stored.put("allowedResources", "[null]");
		stubPresent(hash, stored);

		Optional<VirtualApiKey> loaded = service.findByHash(hash);

		assertThat(loaded).as("key with JSON null element loads").isPresent();
		assertThat(loaded.get().allowedResources()).as("null JSON element is dropped").isEmpty();
	}

	@Test
	void emptyCacheScopesStoreAsEmptyCsv() {
		KeyManagementService service = newService();
		AtomicReference<List<Object>> argv = captureScriptArgv();

		KeyManagementService.CreatedKey created = service.createKey(
				"owner", "name", 5, 50, Set.of(), Set.of(), Set.of(), Set.of(),
				Set.of(), Set.of(), Set.of(), Set.of(), null, Set.of(), Set.of(), Set.of(), UUID.randomUUID());

		assertThat(scriptFields(argv.get()).get("allowedCacheScopes"))
				.as("empty scope set persists as empty CSV").isEmpty();
	}

	@Test
	void allUnknownCacheScopesDefaultToTenant() {
		KeyManagementService service = newService();
		SHA256Hash hash = hashOf("gw-bogusscope000000000000000000001");
		Map<String, String> stored = fields("owner", "name", "5", "50", "true", "", "", CREATED_AT, "gw-");
		stored.put("allowedCacheScopes", "BOGUS_SCOPE,NOPE");
		stubPresent(hash, stored);

		Optional<VirtualApiKey> loaded = service.findByHash(hash);

		assertThat(loaded).as("key with unknown scopes loads").isPresent();
		assertThat(loaded.get().allowedCacheScopes())
				.as("unrecognized scopes fail closed to TENANT").containsExactly(CacheScope.TENANT);
	}

	@Test
	@SuppressWarnings("DataFlowIssue")
	void generateKeyRejectsNullOwnerUsernameWhenWired() {
		UserAccountRepository users = mock(UserAccountRepository.class);
		KeyManagementService service = newService();
		service.setUserAccountRepository(users);

		assertThatThrownBy(() -> service.generateKey(fullTemplate(null)))
				.as("null owner username fails fast")
				.isInstanceOf(IllegalArgumentException.class)
				.hasMessageContaining("required");
		verify(hashOps, never()).putAll(anyString(), anyMap());
	}

	@Test
	void generateKeyRejectsDisabledOwnerWhenWired() {
		UserAccountRepository users = mock(UserAccountRepository.class);
		KeyManagementService service = newService();
		service.setUserAccountRepository(users);
		UserAccount disabled = mock(UserAccount.class);
		when(disabled.isDisabled()).thenReturn(true);
		when(users.findByUsernameIgnoreCase("local")).thenReturn(Optional.of(disabled));

		assertThatThrownBy(() -> service.generateKey(fullTemplate("local")))
				.as("disabled owner fails fast")
				.isInstanceOf(IllegalArgumentException.class)
				.hasMessageContaining("unknown or disabled");
		verify(hashOps, never()).putAll(anyString(), anyMap());
	}

	@Test
	void updateRevokedKeyWithoutReenablingProceeds() {
		KeyManagementService service = newService();
		SHA256Hash hash = hashOf("gw-revoked000000000000000000000001");
		Map<String, String> revoked = fields("owner", "name", "5", "50", "false", "", "", CREATED_AT, "gw-");
		revoked.put("revoked", "true");
		stubPresent(hash, revoked);

		Optional<VirtualApiKey> updated = service.updateKey(
				hash, null, null, null, null, null, null, null, null, null,
				null, null, null, null, null, null, Boolean.FALSE);

		assertThat(updated).as("revoked key accepts a non-reenabling update").isPresent();
		assertThat(updated.get().revoked()).as("tombstone survives the update").isTrue();
		assertThat(updated.get().enabled()).as("enabled stays cleared").isFalse();
	}

	@Test
	@DisplayName("ADM-B04: patchKey validates everything before writing anything")
	void patchKeyWritesNothingOnValidationFailure() {
		KeyManagementService service = newService();
		SHA256Hash hash = hashOf("gw-revoked000000000000000000000002");
		Map<String, String> revoked = fields("owner", "name", "5", "50", "false", "", "", CREATED_AT, "gw-");
		revoked.put("revoked", "true");
		stubPresent(hash, revoked);

		assertThatThrownBy(() -> service.patchKey(
				hash, UUID.randomUUID(), null, null, null, null, null, null, null, null, null,
				null, null, null, null, null, null, Boolean.TRUE))
				.isInstanceOf(IllegalArgumentException.class)
				.hasMessageContaining("terminally revoked");
		verify(hashOps, never()).put(anyString(), anyString(), anyString());
		verify(hashOps, never()).putAll(anyString(), anyMap());
	}

	@Test
	@DisplayName("ADM-B16: listKeys bulk-loads hashes in one pipeline round trip")
	void listKeysBulkLoads() {
		KeyManagementService service = newService();
		SHA256Hash hash1 = hashOf("gw-key11111111111111111111111111111");
		SHA256Hash hash2 = hashOf("gw-key22222222222222222222222222222");
		when(setOps.members("admin:keys")).thenReturn(Set.of(hash1.hex(), hash2.hex(), "invalid-hex-entry"));
		Map<String, String> row = fields("owner-1", "k1", "10", "100", "true", "", "", CREATED_AT, "gw-");
		Map<byte[], byte[]> rawRow = new LinkedHashMap<>();
		row.forEach((k, v) -> rawRow.put(k.getBytes(StandardCharsets.UTF_8), v.getBytes(StandardCharsets.UTF_8)));
		when(redisTemplate.executePipelined(any(RedisCallback.class)))
				.thenReturn(List.of(row, rawRow));

		List<VirtualApiKey> keys = service.listKeys(null);

		assertEquals(2, keys.size());
		verify(redisTemplate, times(1)).executePipelined(any(RedisCallback.class));
		verify(hashOps, never()).entries(anyString());
	}

	@Test
	@DisplayName("ADM-B16: usernames resolve in one batch query")
	void usernamesResolveInOneBatch() {
		UserAccountRepository users = mock(UserAccountRepository.class);
		KeyManagementService service = newService();
		service.setUserAccountRepository(users);
		UserAccount alice = new UserAccount("alice", null, null, false);
		UserAccount bob = new UserAccount("bob", null, null, false);
		when(users.findAllById(any())).thenReturn(List.of(alice, bob));

		Map<UUID, String> names = service.usernamesOf(Set.of(alice.getId(), bob.getId()));

		assertEquals("alice", names.get(alice.getId()));
		assertEquals("bob", names.get(bob.getId()));
		verify(users, times(1)).findAllById(any());
	}

	@Test
	@DisplayName("ADM-B16: usernamesOf degrades to empty instead of failing reads")
	void usernamesOfDegradesGracefully() {
		KeyManagementService unwired = newService();

		assertThat(unwired.usernamesOf(null)).isEmpty();
		assertThat(unwired.usernamesOf(Set.of())).isEmpty();
		assertThat(unwired.usernamesOf(Set.of(UUID.randomUUID()))).isEmpty();

		UserAccountRepository failing = mock(UserAccountRepository.class);
		KeyManagementService broken = newService();
		broken.setUserAccountRepository(failing);
		when(failing.findAllById(any())).thenThrow(new RuntimeException("db down"));

		assertThat(broken.usernamesOf(Set.of(UUID.randomUUID()))).isEmpty();
	}

	@Test
	@DisplayName("ADM-B16: usernamesOf skips unresolvable accounts without failing the batch")
	void usernamesOfSkipsUnknownAccounts() {
		UserAccountRepository users = mock(UserAccountRepository.class);
		KeyManagementService service = newService();
		service.setUserAccountRepository(users);
		UserAccount alice = new UserAccount("alice", null, null, false);
		when(users.findAllById(any())).thenReturn(Arrays.asList(alice, null));

		Map<UUID, String> names = service.usernamesOf(Set.of(alice.getId(), UUID.randomUUID()));

		assertThat(names).containsExactly(Map.entry(alice.getId(), "alice"));
	}

	@Test
	@DisplayName("ADM-B14: key creation fails closed when the atomic store reports a collision")
	void storeCollisionFailsClosed() {
		KeyManagementService service = newService();
		when(redisTemplate.execute(any(), anyList(), any(Object[].class))).thenReturn(0L);

		assertThatThrownBy(() -> service.createKey(
				"owner", "name", 5, 50, Set.of(), Set.of(), Set.of(), Set.of(),
				Set.of(), Set.of(), Set.of(), Set.of(), null, null,
				Set.of(), Set.of(), UUID.randomUUID()))
				.isInstanceOf(IllegalStateException.class)
				.hasMessageContaining("collision");
	}

	@Test
	@DisplayName("ADM-B16: listKeys degrades to available rows when the pipeline gaps")
	void listKeysDegradesOnPipelineGaps() {
		KeyManagementService service = newService();
		SHA256Hash hash1 = hashOf("gw-key11111111111111111111111111111");
		SHA256Hash hash2 = hashOf("gw-key22222222222222222222222222222");
		when(setOps.members("admin:keys")).thenReturn(
				new LinkedHashSet<>(List.of(hash1.hex(), hash2.hex())));
		Map<String, String> row = fields("owner-1", "k1", "10", "100", "true", "", "", CREATED_AT, "gw-");

		when(redisTemplate.executePipelined(any(RedisCallback.class))).thenReturn(null);
		assertThat(service.listKeys(null)).isEmpty();

		when(redisTemplate.executePipelined(any(RedisCallback.class))).thenReturn(List.of(row));
		List<VirtualApiKey> partial = service.listKeys(null);
		assertThat(partial).hasSize(1);
		assertThat(partial.getFirst().ownerId()).isEqualTo("owner-1");

		when(redisTemplate.executePipelined(any(RedisCallback.class)))
				.thenReturn(List.of(Map.of(), row));
		assertThat(service.listKeys(null)).hasSize(1);
	}

	@Test
	@DisplayName("ADM-B04: patchKey applies owner and fields in a single write")
	void patchKeyAppliesAtomically() {
		KeyManagementService service = newService();
		SHA256Hash hash = hashOf(FIXED_PLAINTEXT);
		stubPresent(hash, fields("owner", "name", "5", "50", "true", "", "", CREATED_AT, "gw-"));
		UUID owner = UUID.randomUUID();

		Optional<VirtualApiKey> updated = service.patchKey(
				hash, owner, "renamed", null, null, null, null, null, null, null, null,
				null, null, null, null, null, null, null);

		assertThat(updated).isPresent();
		verify(hashOps, times(1)).putAll(eq(redisKey(hash)), anyMap());
		verify(hashOps, never()).put(anyString(), anyString(), anyString());
	}

	@Test
	@DisplayName("ADM-B14: key creation stores hash and index atomically via script")
	void createKeyStoresAtomically() {
		KeyManagementService service = newService();
		when(redisTemplate.execute(any(RedisScript.class), anyList(), any(Object[].class)))
				.thenReturn(1L);

		KeyManagementService.CreatedKey created = service.createKey(
				"owner", "name", 5, 50, Set.of(), Set.of(), Set.of(), Set.of(),
				Set.of(), Set.of(), Set.of(), Set.of(), null, null, Set.of(), Set.of(),
				UUID.randomUUID());

		assertThat(created).isNotNull();
		verify(redisTemplate).execute(
				any(RedisScript.class),
				eq(List.of("apikey:" + created.hash().hex(), "admin:keys")),
				any(Object[].class));
		verify(hashOps, never()).putAll(anyString(), anyMap());
		verify(setOps, never()).add(anyString(), any(String[].class));
	}

	@Test
	@SuppressWarnings("DataFlowIssue")
	void isOwnerActiveReadsActiveWhenUnwired() {
		KeyManagementService service = newService();

		assertThat(service.isOwnerActive(UUID.randomUUID())).as("unwired owners read active").isTrue();
		assertThat(service.isOwnerActive(null)).as("null owner reads inactive").isFalse();
	}

	@Test
	@SuppressWarnings("DataFlowIssue")
	void invalidateOwnerCacheRefreshesVerdicts() {
		UserAccountRepository users = mock(UserAccountRepository.class);
		KeyManagementService service = newService();
		service.setUserAccountRepository(users);
		UUID id = UUID.randomUUID();
		when(users.findById(id)).thenReturn(Optional.of(new UserAccount("seeduser", null, null, false)));

		assertThat(service.isOwnerActive(id)).as("first lookup resolves active").isTrue();

		UserAccount disabled = mock(UserAccount.class);
		when(disabled.isDisabled()).thenReturn(true);
		when(users.findById(id)).thenReturn(Optional.of(disabled));
		assertThat(service.isOwnerActive(id)).as("cached verdict survives repository change").isTrue();

		service.invalidateOwnerCache(id);
		assertThat(service.isOwnerActive(id)).as("invalidated verdict refreshes").isFalse();
		verify(users, times(2)).findById(id);

		service.invalidateOwnerCache(null);
	}

	@Test
	@SuppressWarnings("DataFlowIssue")
	void listKeysByUserReadsEmptyWhenIndexIsNull() {
		KeyManagementService service = newService();
		when(setOps.members("admin:keys")).thenReturn(null);

		assertThat(service.listKeysByUser(UUID.randomUUID())).as("null index reads empty").isEmpty();
	}

	@Test
	@SuppressWarnings("DataFlowIssue")
	void resolveActAsSelfReadsEmptyWithoutSessions() {
		KeyManagementService unwired = newService();

		assertThat(unwired.resolveActAsSelf("session-jwt", "default"))
				.as("unwired session service resolves empty").isEmpty();

		JwtService sessions = mock(JwtService.class);
		KeyManagementService service = newService();
		service.setJwtService(sessions);

		assertThat(service.resolveActAsSelf(null, "default")).as("null token resolves empty").isEmpty();
		assertThat(service.resolveActAsSelf("   ", "default")).as("blank token resolves empty").isEmpty();
		verifyNoInteractions(sessions);
	}

	@Test
	void resolveActAsSelfReadsEmptyForUnknownKey() {
		JwtService sessions = mock(JwtService.class);
		KeyManagementService service = newService();
		service.setJwtService(sessions);
		UUID userId = UUID.randomUUID();
		Instant now = Instant.now();
		Jwt jwt = new Jwt("session", now, now.plusSeconds(600),
				Map.of("alg", "HS256"), Map.of("sub", userId.toString()));
		when(sessions.validate("session-jwt")).thenReturn(jwt);
		SHA256Hash unknown = hashOf("gw-unknown00000000000000000000001");
		when(redisTemplate.hasKey(redisKey(unknown))).thenReturn(Boolean.FALSE);

		assertThat(service.resolveActAsSelf("session-jwt", unknown.hex()))
				.as("well-formed but unknown key resolves empty").isEmpty();
	}

	@Test
	void seedSkipsBlankOwnerUsernameWhenWired() {
		UserAccountRepository users = mock(UserAccountRepository.class);
		KeyManagementService service = newService();
		service.setUserAccountRepository(users);
		GatewayProperties properties = new GatewayProperties();
		properties.setBootstrapKeys(List.of(fullTemplate("  ")));

		service.seedBootstrapKeys(properties);

		verify(redisTemplate, never()).execute(any(), anyList(), any(Object[].class));
	}

	@Test
	void seedPersistsWiredOwnerLookup() {
		UserAccountRepository users = mock(UserAccountRepository.class);
		KeyManagementService service = newService();
		service.setUserAccountRepository(users);
		UUID seedOwner = UUID.randomUUID();
		UserAccount account = mock(UserAccount.class);
		when(account.isDisabled()).thenReturn(false);
		when(account.getId()).thenReturn(seedOwner);
		when(users.findByUsernameIgnoreCase("seeduser")).thenReturn(Optional.of(account));
		when(redisTemplate.execute(any(), anyList(), any(Object[].class))).thenReturn(1L);
		GatewayProperties properties = new GatewayProperties();
		properties.setBootstrapKeys(List.of(fullTemplate("seeduser")));

		service.seedBootstrapKeys(properties);

		ArgumentCaptor<Object[]> argsCaptor = ArgumentCaptor.forClass(Object[].class);
		verify(redisTemplate).execute(any(), anyList(), argsCaptor.capture());
		assertThat(Arrays.asList(argsCaptor.getValue()))
				.as("seed carries the resolved owner").contains(seedOwner.toString());
	}

	@Test
	void seedSkipsDisabledOwnerWhenWired() {		UserAccountRepository users = mock(UserAccountRepository.class);
		KeyManagementService service = newService();
		service.setUserAccountRepository(users);
		UserAccount disabled = mock(UserAccount.class);
		when(disabled.isDisabled()).thenReturn(true);
		when(users.findByUsernameIgnoreCase("seeduser")).thenReturn(Optional.of(disabled));
		GatewayProperties properties = new GatewayProperties();
		properties.setBootstrapKeys(List.of(fullTemplate("seeduser")));

		service.seedBootstrapKeys(properties);

		verify(redisTemplate, never()).execute(any(), anyList(), any(Object[].class));
	}

	@Test
	void seedProceedsWithoutOwnerLookupWhenUnwired() {
		KeyManagementService service = newService();
		GatewayProperties properties = new GatewayProperties();
		properties.setBootstrapKeys(List.of(fullTemplate("seeduser")));

		service.seedBootstrapKeys(properties);

		verify(redisTemplate, atLeastOnce()).execute(any(), anyList(), any(Object[].class));
	}

	@Test
	void patchKeyValidatesLegacyStoredOwner() {
		KeyManagementService service = newService();
		SHA256Hash hash = hashOf(FIXED_PLAINTEXT);
		stubPresent(hash, fields("owner", "name", "5", "50", "true", "", "", CREATED_AT, "gw-"));
		when(hashOps.get(redisKey(hash), "ownerId")).thenReturn("owner");
		UUID owner = UUID.randomUUID();

		Optional<VirtualApiKey> updated = service.patchKey(
				hash, owner, "renamed", null, null, null, null, null, null, null, null,
				null, null, null, null, null, null, null);

		assertThat(updated).isPresent();
		@SuppressWarnings("unchecked")
		ArgumentCaptor<Map<String, String>> updatesCaptor = ArgumentCaptor.forClass(Map.class);
		verify(hashOps).putAll(eq(redisKey(hash)), updatesCaptor.capture());
		assertThat(updatesCaptor.getValue().get("ownerUserId")).isEqualTo(owner.toString());
		assertThat(updatesCaptor.getValue().get("name")).isEqualTo("renamed");
	}

	@Test
	void listKeysSkipsRowsWithNullFieldsAndMissingKeys() {
		KeyManagementService service = newService();
		SHA256Hash hash1 = hashOf("gw-key11111111111111111111111111111");
		when(setOps.members("admin:keys")).thenReturn(new LinkedHashSet<>(List.of(hash1.hex())));
		Map<String, String> row = fields("owner-1", "k1", "10", "100", "true", "", "", CREATED_AT, "gw-");

		Map<Object, Object> nullKey = new HashMap<>();
		nullKey.put(null, "v");
		when(redisTemplate.executePipelined(any(RedisCallback.class))).thenReturn(List.of(nullKey));
		assertThat(service.listKeys(null)).isEmpty();

		Map<Object, Object> nullValue = new HashMap<>(row);
		nullValue.put("name", null);
		when(redisTemplate.executePipelined(any(RedisCallback.class))).thenReturn(List.of(nullValue));
		assertThat(service.listKeys(null)).isEmpty();

		Map<String, String> sparse = new LinkedHashMap<>(row);
		sparse.remove("rpmLimit");
		when(redisTemplate.executePipelined(any(RedisCallback.class))).thenReturn(List.of(sparse));
		assertThat(service.listKeys(null)).isEmpty();
	}

	private static BootstrapKey fullTemplate(String ownerUsername) {
		return new BootstrapKey(
				"tenant-a", "seed", "gw-seed-key-00000000000000000001", 5, 50,
				Set.of(), Set.of(), Set.of(), Set.of(), Set.of(), Set.of(), Set.of(), Set.of(),
				null, ownerUsername);
	}

	@Test
	void deleteKeyWritesPermanentTombstoneMarker() {
		ValueOperations<String, String> valueOps = mock(ValueOperations.class);
		when(redisTemplate.opsForValue()).thenReturn(valueOps);
		KeyManagementService service = newService();
		SHA256Hash hash = hashOf(FIXED_PLAINTEXT);
		when(redisTemplate.delete(redisKey(hash))).thenReturn(Boolean.TRUE);

		assertTrue(service.deleteKey(hash));
		verify(valueOps).set("deleted-key:" + hash.hex(), "1");
	}

	@Test
	void deleteKeyWithoutRemovalWritesNoMarker() {
		ValueOperations<String, String> valueOps = mock(ValueOperations.class);
		when(redisTemplate.opsForValue()).thenReturn(valueOps);
		KeyManagementService service = newService();
		SHA256Hash hash = hashOf(FIXED_PLAINTEXT);
		when(redisTemplate.delete(redisKey(hash))).thenReturn(Boolean.FALSE);

		assertFalse(service.deleteKey(hash));
		verify(valueOps, never()).set(anyString(), anyString());
	}

	@Test
	void seedBootstrapKeysNeverResurrectsTombstonedKeys() {
		KeyManagementService service = newService();
		BootstrapKey doomed = new BootstrapKey(
				"owner", "doomed", "gw-dddddddddddddddddddddddddddddddd", 1, 1, Set.of(), Set.of());
		String marker = "deleted-key:" + hashOf(doomed.plaintextKey()).hex();
		when(redisTemplate.hasKey(marker)).thenReturn(Boolean.TRUE);

		GatewayProperties properties = new GatewayProperties();
		properties.setBootstrapKeys(List.of(doomed));

		service.seedBootstrapKeys(properties);

		verify(redisTemplate, never()).execute(any(), anyList(), any(Object[].class));
	}

	@Test
	void assignOwnerValidatesLegacyStoredOwner() {
		KeyManagementService service = newService();
		SHA256Hash hash = hashOf(FIXED_PLAINTEXT);
		Map<String, String> stored = fields("tenant-legacy", "name", "5", "50", "true",
				"", "", CREATED_AT, "gw-");
		stored.put("ownerId", "tenant-legacy");
		stubPresent(hash, stored);
		when(hashOps.get(redisKey(hash), "ownerId")).thenReturn("tenant-legacy");
		UUID owner = UUID.randomUUID();

		assertTrue(service.assignOwner(hash, owner).isPresent());
		verify(hashOps).put(redisKey(hash), "ownerUserId", owner.toString());
	}

	@Test
	void assignOwnerRejectsInvalidLegacyStoredOwner() {
		KeyManagementService service = newService();
		SHA256Hash hash = hashOf(FIXED_PLAINTEXT);
		stubPresent(hash, fields("x", "name", "5", "50", "true", "", "", CREATED_AT, "gw-"));
		when(hashOps.get(redisKey(hash), "ownerId")).thenReturn("NOT A TENANT!!");

		assertThatThrownBy(() -> service.assignOwner(hash, UUID.randomUUID()))
				.isInstanceOf(IllegalArgumentException.class);
	}
}