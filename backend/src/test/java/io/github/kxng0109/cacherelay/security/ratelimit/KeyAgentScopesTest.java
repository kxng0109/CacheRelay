package io.github.kxng0109.cacherelay.security.ratelimit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import io.github.kxng0109.cacherelay.contracts.BootstrapKey;
import io.github.kxng0109.cacherelay.contracts.GatewayProperties;
import io.github.kxng0109.cacherelay.contracts.SHA256Hash;
import io.github.kxng0109.cacherelay.contracts.VirtualApiKey;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.data.redis.core.HashOperations;
import org.springframework.data.redis.core.SetOperations;
import org.springframework.data.redis.core.StringRedisTemplate;

/**
 * Persistence round-trips for the A2A agent RBAC sets on virtual keys: create,
 * update, read, legacy-key defaults, and bootstrap seeding parity.
 */
class KeyAgentScopesTest {

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

	private static Map<String, String> storedFields(String allowedAgents, String deniedAgents) {
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
		fields.put("allowedCacheScopes", "TENANT");
		if (allowedAgents != null) {
			fields.put("allowedAgents", allowedAgents);
		}
		if (deniedAgents != null) {
			fields.put("deniedAgents", deniedAgents);
		}
		fields.put("createdAt", "2026-08-29T10:00:00Z");
		fields.put("keyPrefix", "gw-");
		return fields;
	}

	@Test
	void createKeyPersistsAgentSets() {
		KeyManagementService service = newService();

		KeyManagementService.CreatedKey created = service.createKey(
				"owner", "name", 5, 50, Set.of(), Set.of(), Set.of(), Set.of(),
				Set.of(), Set.of(), Set.of(), Set.of(), null, null,
				Set.of("research-*", "support"), Set.of("prod-*"));

		assertEquals(Set.of("research-*", "support"), created.key().allowedAgents());
		assertEquals(Set.of("prod-*"), created.key().deniedAgents());
		@SuppressWarnings("unchecked")
		ArgumentCaptor<Map<String, String>> fieldsCaptor = ArgumentCaptor.forClass(Map.class);
		verify(hashOps).putAll(eq(redisKey(created.hash())), fieldsCaptor.capture());
		assertEquals(Set.of("research-*", "support"),
				Set.of(fieldsCaptor.getValue().get("allowedAgents").split(",")));
		assertEquals("prod-*", fieldsCaptor.getValue().get("deniedAgents"));
	}

	@Test
	void updateKeyPersistsAgentSets() {
		KeyManagementService service = newService();
		SHA256Hash hash = SHA256Hash.fromRawKey("gw-aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa");
		when(redisTemplate.hasKey(redisKey(hash))).thenReturn(Boolean.TRUE);
		when(hashOps.entries(redisKey(hash))).thenReturn(storedFields(null, null));

		Optional<VirtualApiKey> updated = service.updateKey(
				hash, null, null, null, null, null, null, null, null, null, null, null,
				null, null, Set.of("research-agent"), Set.of("legacy-*"), null);

		assertTrue(updated.isPresent());
		@SuppressWarnings("unchecked")
		ArgumentCaptor<Map<String, String>> updatesCaptor = ArgumentCaptor.forClass(Map.class);
		verify(hashOps).putAll(eq(redisKey(hash)), updatesCaptor.capture());
		assertEquals("research-agent", updatesCaptor.getValue().get("allowedAgents"));
		assertEquals("legacy-*", updatesCaptor.getValue().get("deniedAgents"));
	}

	@Test
	void updateKeyWithoutAgentSetsKeepsExistingValues() {
		KeyManagementService service = newService();
		SHA256Hash hash = SHA256Hash.fromRawKey("gw-bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb");
		when(redisTemplate.hasKey(redisKey(hash))).thenReturn(Boolean.TRUE);
		when(hashOps.entries(redisKey(hash))).thenReturn(storedFields("research-*", null));

		Optional<VirtualApiKey> updated = service.updateKey(
				hash, "renamed", null, null, null, null, null, null, null, null, null, null,
				null, null, null, null, null);

		assertTrue(updated.isPresent());
		assertEquals(Set.of("research-*"), updated.get().allowedAgents());
		assertTrue(updated.get().deniedAgents().isEmpty());
	}

	@Test
	void findByHashReadsAgentSets() {
		KeyManagementService service = newService();
		SHA256Hash hash = SHA256Hash.fromRawKey("gw-cccccccccccccccccccccccccccccccc");
		when(redisTemplate.hasKey(redisKey(hash))).thenReturn(Boolean.TRUE);
		when(hashOps.entries(redisKey(hash))).thenReturn(storedFields("research-*,support", "prod-*"));

		Optional<VirtualApiKey> result = service.findByHash(hash);

		assertTrue(result.isPresent());
		assertEquals(Set.of("research-*", "support"), result.get().allowedAgents());
		assertEquals(Set.of("prod-*"), result.get().deniedAgents());
	}

	@Test
	void findByHashDefaultsLegacyKeysToEmptyAgentSets() {
		KeyManagementService service = newService();
		SHA256Hash hash = SHA256Hash.fromRawKey("gw-dddddddddddddddddddddddddddddddd");
		when(redisTemplate.hasKey(redisKey(hash))).thenReturn(Boolean.TRUE);
		when(hashOps.entries(redisKey(hash))).thenReturn(storedFields(null, null));

		Optional<VirtualApiKey> result = service.findByHash(hash);

		assertTrue(result.isPresent());
		assertTrue(result.get().allowedAgents().isEmpty());
		assertTrue(result.get().deniedAgents().isEmpty());
	}

	@Test
	void seedBootstrapKeysPersistsEmptyAgentSets() {
		KeyManagementService service = newService();
		BootstrapKey template = new BootstrapKey(
				"owner", "name", "gw-eeeeeeeeeeeeeeeeeeeeeeeeeeeeeeee", 1, 1,
				Set.of(), Set.of(), Set.of(), Set.of(), Set.of(), Set.of(), Set.of(), Set.of(),
				Set.of());
		when(redisTemplate.execute(any(), anyList(), any(Object[].class))).thenReturn(1L);

		GatewayProperties properties = new GatewayProperties();
		properties.setBootstrapKeys(List.of(template));
		service.seedBootstrapKeys(properties);

		ArgumentCaptor<Object[]> argsCaptor = ArgumentCaptor.forClass(Object[].class);
		verify(redisTemplate).execute(any(), anyList(), argsCaptor.capture());
		List<Object> args = Arrays.asList(argsCaptor.getValue());
		int fieldIndex = args.indexOf("allowedAgents");
		assertTrue(fieldIndex >= 0, "seed args must carry allowedAgents");
		assertEquals("", args.get(fieldIndex + 1));
		assertTrue(args.indexOf("deniedAgents") >= 0, "seed args must carry deniedAgents");
	}
}
