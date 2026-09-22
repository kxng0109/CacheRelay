package io.github.kxng0109.cacherelay.security.ratelimit;

import io.github.kxng0109.cacherelay.cache.contracts.CacheScope;
import io.github.kxng0109.cacherelay.contracts.BootstrapKey;
import io.github.kxng0109.cacherelay.contracts.GatewayProperties;
import io.github.kxng0109.cacherelay.contracts.SHA256Hash;
import io.github.kxng0109.cacherelay.contracts.VirtualApiKey;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.data.redis.core.HashOperations;
import org.springframework.data.redis.core.SetOperations;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class KeyCacheScopesTest {

	private final StringRedisTemplate redisTemplate = mock(StringRedisTemplate.class);
	private final HashOperations<String, String, String> hashOps = mock(HashOperations.class);
	private final SetOperations<String, String> setOps = mock(SetOperations.class);

	private KeyManagementService newService() {
		when(redisTemplate.<String, String>opsForHash()).thenReturn(hashOps);
		when(redisTemplate.<String, String>opsForSet()).thenReturn(setOps);
		return new KeyManagementService(redisTemplate);
	}

	private static String redisKey(SHA256Hash hash) {
		return "apikey:" + hash.hex();
	}

	private static Map<String, String> storedFields(String scopesCsv) {
		Map<String, String> fields = new LinkedHashMap<>();
		fields.put("ownerId", "owner");
		fields.put("name", "name");
		fields.put("rpmLimit", "5");
		fields.put("tpmLimit", "50");
		fields.put("enabled", "true");
		fields.put("allowedModels", "");
		fields.put("allowedProviders", "");
		fields.put("allowedTools", "");
		fields.put("deniedTools", "");
		fields.put("allowedResources", "");
		fields.put("deniedResources", "");
		fields.put("allowedPrompts", "");
		fields.put("deniedPrompts", "");
		fields.put("injectionBlock", "true");
		if (scopesCsv != null) {
			fields.put("allowedCacheScopes", scopesCsv);
		}
		fields.put("createdAt", "2026-08-29T10:00:00Z");
		fields.put("keyPrefix", "gw-");
		return fields;
	}

	@Test
	void createKeyPersistsCacheScopes() {
		KeyManagementService service = newService();

		KeyManagementService.CreatedKey created = service.createKey(
				"owner", "name", 5, 50, Set.of(), Set.of(), Set.of(), Set.of(),
				Set.of(), Set.of(), Set.of(), Set.of(), null,
				Set.of(CacheScope.GLOBAL, CacheScope.TENANT), Set.of(), Set.of(), UUID.randomUUID());

		assertEquals(Set.of(CacheScope.GLOBAL, CacheScope.TENANT), created.key().allowedCacheScopes());
		@SuppressWarnings("unchecked")
		ArgumentCaptor<Map<String, String>> fieldsCaptor = ArgumentCaptor.forClass(Map.class);
		verify(hashOps).putAll(eq(redisKey(created.hash())), fieldsCaptor.capture());
		assertEquals("GLOBAL,TENANT", fieldsCaptor.getValue().get("allowedCacheScopes"));
	}

	@Test
	void createKeyWithNullSetsPersistsEmpty() {
		KeyManagementService service = newService();

		KeyManagementService.CreatedKey created = service.createKey(
				"owner", "name", 5, 50, null, null, null, null,
				null, null, null, null, null, null, Set.of(), Set.of(), UUID.randomUUID());

		assertTrue(created.key().allowedModels().isEmpty());
		assertTrue(created.key().allowedCacheScopes().contains(CacheScope.TENANT));
		@SuppressWarnings("unchecked")
		ArgumentCaptor<Map<String, String>> fieldsCaptor = ArgumentCaptor.forClass(Map.class);
		verify(hashOps).putAll(eq(redisKey(created.hash())), fieldsCaptor.capture());
		assertEquals("[]", fieldsCaptor.getValue().get("allowedResources"));
		assertEquals("", fieldsCaptor.getValue().get("allowedCacheScopes"));
	}

	@Test
	void findByHashWithMissingFieldsDefaults() {
		KeyManagementService service = newService();
		SHA256Hash hash = SHA256Hash.fromRawKey("gw-ffffffffffffffffffffffffffffffff");
		Map<String, String> fields = storedFields(null);
		fields.remove("allowedResources");
		fields.remove("deniedResources");
		fields.remove("allowedPrompts");
		fields.remove("deniedPrompts");
		when(redisTemplate.hasKey(redisKey(hash))).thenReturn(Boolean.TRUE);
		when(hashOps.entries(redisKey(hash))).thenReturn(fields);

		Optional<VirtualApiKey> result = service.findByHash(hash);

		assertTrue(result.isPresent());
		assertTrue(result.get().allowedResources().isEmpty());
		assertTrue(result.get().deniedPrompts().isEmpty());
	}

	@Test
	void updateKeyPersistsCacheScopes() {
		KeyManagementService service = newService();
		SHA256Hash hash = SHA256Hash.fromRawKey("gw-cccccccccccccccccccccccccccccccc");
		when(redisTemplate.hasKey(redisKey(hash))).thenReturn(Boolean.TRUE);
		when(hashOps.entries(redisKey(hash))).thenReturn(storedFields("TENANT"));

		Optional<VirtualApiKey> updated = service.updateKey(
				hash, null, null, null, null, null, null, null, null, null, null, null,
				null, Set.of(CacheScope.GLOBAL), null);

		assertTrue(updated.isPresent());
		@SuppressWarnings("unchecked")
		ArgumentCaptor<Map<String, String>> updatesCaptor = ArgumentCaptor.forClass(Map.class);
		verify(hashOps).putAll(eq(redisKey(hash)), updatesCaptor.capture());
		assertEquals("GLOBAL", updatesCaptor.getValue().get("allowedCacheScopes"));
	}

	@Test
	void findByHashReadsCacheScopesAndDropsUnknownNames() {
		KeyManagementService service = newService();
		SHA256Hash hash = SHA256Hash.fromRawKey("gw-dddddddddddddddddddddddddddddddd");
		when(redisTemplate.hasKey(redisKey(hash))).thenReturn(Boolean.TRUE);
		when(hashOps.entries(redisKey(hash)))
				.thenReturn(storedFields("GLOBAL,TENANT,BOGUS_SCOPE"));

		Optional<VirtualApiKey> result = service.findByHash(hash);

		assertTrue(result.isPresent());
		assertEquals(Set.of(CacheScope.GLOBAL, CacheScope.TENANT), result.get().allowedCacheScopes());
	}

	@Test
	void findByHashDefaultsLegacyKeysToTenantOnly() {
		KeyManagementService service = newService();
		SHA256Hash hash = SHA256Hash.fromRawKey("gw-eeeeeeeeeeeeeeeeeeeeeeeeeeeeeeee");
		when(redisTemplate.hasKey(redisKey(hash))).thenReturn(Boolean.TRUE);
		when(hashOps.entries(redisKey(hash))).thenReturn(storedFields(null));

		Optional<VirtualApiKey> result = service.findByHash(hash);

		assertTrue(result.isPresent());
		assertEquals(Set.of(CacheScope.TENANT), result.get().allowedCacheScopes());
	}

	@Test
	void seedBootstrapKeysForwardsCacheScopes() {
		KeyManagementService service = newService();
		BootstrapKey scoped = new BootstrapKey(
				"owner", "name", "gw-ffffffffffffffffffffffffffffffff", 1, 1,
				Set.of(), Set.of(), Set.of(), Set.of(), Set.of(), Set.of(), Set.of(), Set.of(),
				Set.of(CacheScope.GLOBAL), null);
		when(redisTemplate.execute(any(), anyList(), any(Object[].class))).thenReturn(1L);

		GatewayProperties properties = new GatewayProperties();
		properties.setBootstrapKeys(List.of(scoped));
		service.seedBootstrapKeys(properties);

		ArgumentCaptor<Object[]> argsCaptor = ArgumentCaptor.forClass(Object[].class);
		verify(redisTemplate).execute(any(), anyList(), argsCaptor.capture());
		List<Object> args = Arrays.asList(argsCaptor.getValue());
		int fieldIndex = args.indexOf("allowedCacheScopes");
		assertTrue(fieldIndex >= 0, "seed args must carry allowedCacheScopes");
		assertEquals("GLOBAL", args.get(fieldIndex + 1));
	}
}
