package io.github.kxng0109.cacherelay.security.ratelimit;

import io.github.kxng0109.cacherelay.contracts.SHA256Hash;
import io.github.kxng0109.cacherelay.contracts.VirtualApiKey;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.data.redis.core.HashOperations;
import org.springframework.data.redis.core.SetOperations;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

@DisplayName("RBAC set persistence: JSON encoding with CSV fallback (SEC-21/FS-04)")
class KeySetPersistenceTest {

	private final StringRedisTemplate redisTemplate = mock(StringRedisTemplate.class);
	private final HashOperations<String, String, String> hashOps = mock(HashOperations.class);
	private final SetOperations<String, String> setOps = mock(SetOperations.class);

	private KeyManagementService newService() {
		when(redisTemplate.<String, String>opsForHash()).thenReturn(hashOps);
		when(redisTemplate.<String, String>opsForSet()).thenReturn(setOps);
		return new KeyManagementService(redisTemplate);
	}

	private static Map<String, String> baseFields() {
		Map<String, String> fields = new HashMap<>();
		fields.put("ownerId", "owner");
		fields.put("name", "name");
		fields.put("rpmLimit", "5");
		fields.put("tpmLimit", "50");
		fields.put("enabled", "true");
		fields.put("allowedModels", "");
		fields.put("allowedProviders", "");
		fields.put("allowedTools", "");
		fields.put("deniedTools", "");
		fields.put("injectionBlock", "true");
		fields.put("createdAt", "2026-09-01T10:00:00Z");
		fields.put("keyPrefix", "gw-");
		return fields;
	}

	private Optional<VirtualApiKey> reload(Map<String, String> stored) {
		SHA256Hash hash = SHA256Hash.fromRawKey("gw-ffffffffffffffffffffffffffffffff");
		when(redisTemplate.hasKey("apikey:" + hash.hex())).thenReturn(Boolean.TRUE);
		when(hashOps.entries("apikey:" + hash.hex())).thenReturn(stored);
		return newService().findByHash(hash);
	}

	@Test
	@DisplayName("elements with commas, quotes, backslashes, and brackets survive exactly")
	void nastyElementsRoundTrip() {
		KeyManagementService service = newService();
		Set<String> nasty = Set.of(
				"postgres://db/x?fields=a,b",
				"say \"hi\"",
				"back\\slash",
				"[bracket]",
				"  spaced  ");

		KeyManagementService.CreatedKey created = service.createKey(
				"owner", "name", 5, 50, Set.of(), Set.of(), Set.of(), Set.of(),
				nasty, Set.of(), Set.of(), Set.of(), null, null, Set.of(), Set.of(), UUID.randomUUID());

		@SuppressWarnings("unchecked")
		ArgumentCaptor<Map<String, String>> fieldsCaptor = ArgumentCaptor.forClass(Map.class);
		verifyStored(created, fieldsCaptor);
		Map<String, String> stored = new HashMap<>(fieldsCaptor.getValue());

		Optional<VirtualApiKey> reloaded = reload(stored);

		assertTrue(reloaded.isPresent());
		assertEquals(nasty, reloaded.get().allowedResources());
	}

	@Test
	@DisplayName("legacy CSV values keep loading")
	void legacyCsvLoads() {
		Map<String, String> stored = baseFields();
		stored.put("allowedResources", "postgres://a,postgres://b");
		stored.put("deniedResources", "");
		stored.put("allowedPrompts", "");
		stored.put("deniedPrompts", "");

		Optional<VirtualApiKey> reloaded = reload(stored);

		assertTrue(reloaded.isPresent());
		assertEquals(Set.of("postgres://a", "postgres://b"), reloaded.get().allowedResources());
	}

	@Test
	@DisplayName("empty set stores as [] and loads as empty (allow-all preserved)")
	void emptySetRoundTrip() {
		Map<String, String> stored = baseFields();
		stored.put("allowedResources", "[]");
		stored.put("deniedResources", "[]");
		stored.put("allowedPrompts", "[]");
		stored.put("deniedPrompts", "[]");

		Optional<VirtualApiKey> reloaded = reload(stored);

		assertTrue(reloaded.isPresent());
		assertTrue(reloaded.get().allowedResources().isEmpty());
	}

	@Test
	@DisplayName("encoding is deterministic regardless of set iteration order")
	void encodingDeterministic() {
		KeyManagementService service = newService();
		Set<String> first = new HashSet<>(Set.of("b-*", "a-*", "c-*"));
		Set<String> second = new HashSet<>(Set.of("c-*", "b-*", "a-*"));

		String storedFirst = storedResources(service, first);
		String storedSecond = storedResources(service, second);

		assertEquals(storedFirst, storedSecond);
	}

	@Test
	@DisplayName("corrupt stored JSON fails closed to a miss")
	void corruptJsonFailsClosed() {
		Map<String, String> stored = baseFields();
		stored.put("allowedResources", "[\"unclosed");
		stored.put("deniedResources", "[]");
		stored.put("allowedPrompts", "[]");
		stored.put("deniedPrompts", "[]");

		assertTrue(reload(stored).isEmpty());
	}

	private String storedResources(KeyManagementService ignored, Set<String> resources) {
		StringRedisTemplate template = mock(StringRedisTemplate.class);
		HashOperations<String, String, String> hashes = mock(HashOperations.class);
		when(template.<String, String>opsForHash()).thenReturn(hashes);
		when(template.<String, String>opsForSet()).thenReturn(mock(SetOperations.class));
		KeyManagementService.CreatedKey created = new KeyManagementService(template).createKey(
				"owner", "name", 5, 50, Set.of(), Set.of(), Set.of(), Set.of(),
				resources, Set.of(), Set.of(), Set.of(), null, null, Set.of(), Set.of(), UUID.randomUUID());
		@SuppressWarnings("unchecked")
		ArgumentCaptor<Map<String, String>> fieldsCaptor = ArgumentCaptor.forClass(Map.class);
		org.mockito.Mockito.verify(hashes).putAll(
				eq("apikey:" + created.hash().hex()), fieldsCaptor.capture());
		return fieldsCaptor.getValue().get("allowedResources");
	}

	private void verifyStored(KeyManagementService.CreatedKey created,
	                          ArgumentCaptor<Map<String, String>> fieldsCaptor) {
		org.mockito.Mockito.verify(hashOps).putAll(
				eq("apikey:" + created.hash().hex()), fieldsCaptor.capture());
	}
}
