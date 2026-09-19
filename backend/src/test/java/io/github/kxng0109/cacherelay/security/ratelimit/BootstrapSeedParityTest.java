package io.github.kxng0109.cacherelay.security.ratelimit;

import io.github.kxng0109.cacherelay.cache.contracts.CacheScope;
import io.github.kxng0109.cacherelay.contracts.BootstrapKey;
import io.github.kxng0109.cacherelay.contracts.GatewayProperties;
import io.github.kxng0109.cacherelay.contracts.SHA256Hash;
import io.github.kxng0109.cacherelay.contracts.VirtualApiKey;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.data.redis.core.HashOperations;
import org.springframework.data.redis.core.SetOperations;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.util.Arrays;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@DisplayName("Bootstrap seed parity for RBAC fields (SEC-05/FS-02)")
class BootstrapSeedParityTest {

	private final StringRedisTemplate redisTemplate = mock(StringRedisTemplate.class);
	private final HashOperations<String, String, String> hashOps = mock(HashOperations.class);
	private final SetOperations<String, String> setOps = mock(SetOperations.class);

	private KeyManagementService newService() {
		when(redisTemplate.<String, String>opsForHash()).thenReturn(hashOps);
		when(redisTemplate.<String, String>opsForSet()).thenReturn(setOps);
		return new KeyManagementService(redisTemplate);
	}

	private static BootstrapKey fullTemplate() {
		return new BootstrapKey(
				"owner", "name", "gw-aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa", 5, 50,
				Set.of("gpt-4o"), Set.of("openai"),
				Set.of("postgres__*"), Set.of("*:delete_*"),
				Set.of("postgres://*"), Set.of("postgres://secret/*"),
				Set.of("server__review_*"), Set.of("server__admin_*"),
				Set.of(CacheScope.TENANT));
	}

	@Test
	@DisplayName("seed persists every RBAC field the store path persists")
	void seedPersistsAllRbacFields() {
		KeyManagementService service = newService();
		when(redisTemplate.execute(any(), anyList(), any(Object[].class))).thenReturn(1L);

		GatewayProperties properties = new GatewayProperties();
		properties.setBootstrapKeys(List.of(fullTemplate()));
		service.seedBootstrapKeys(properties);

		ArgumentCaptor<Object[]> argsCaptor = ArgumentCaptor.forClass(Object[].class);
		verify(redisTemplate).execute(any(), anyList(), argsCaptor.capture());
		List<Object> args = Arrays.asList(argsCaptor.getValue());

		assertPair(args, "allowedResources", "[\"postgres://*\"]");
		assertPair(args, "deniedResources", "[\"postgres://secret/*\"]");
		assertPair(args, "allowedPrompts", "[\"server__review_*\"]");
		assertPair(args, "deniedPrompts", "[\"server__admin_*\"]");
		assertPair(args, "injectionBlock", "true");
		assertPair(args, "allowedCacheScopes", "TENANT");
	}

	private static void assertPair(List<Object> args, String field, String value) {
		int fieldIndex = args.indexOf(field);
		assertTrue(fieldIndex >= 0, "seed args must carry " + field);
		assertEquals(value, args.get(fieldIndex + 1));
	}
}
