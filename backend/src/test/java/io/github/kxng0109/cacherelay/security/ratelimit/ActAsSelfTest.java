package io.github.kxng0109.cacherelay.security.ratelimit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.HashOperations;
import org.springframework.data.redis.core.SetOperations;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.security.oauth2.jwt.Jwt;

import io.github.kxng0109.cacherelay.auth.JwtService;
import io.github.kxng0109.cacherelay.auth.UserAccount;
import io.github.kxng0109.cacherelay.auth.UserAccountRepository;
import io.github.kxng0109.cacherelay.contracts.SHA256Hash;
import io.github.kxng0109.cacherelay.contracts.VirtualApiKey;

/**
 * Act-as-self resolution: session JWT plus key selector resolving to the
 * caller's own usable key, or empty for every failure mode without distinction.
 */
@DisplayName("ActAsSelf")
class ActAsSelfTest {

	private static final String FIXED_PLAINTEXT = "gw-abcdefghijklmnopqrstuvwxyz012345";

	private StringRedisTemplate redisTemplate;
	private HashOperations<String, String, String> hashOps;
	private SetOperations<String, String> setOps;
	private ValueOperations<String, String> valueOps;
	private UserAccountRepository users;
	private JwtService jwtService;
	private KeyManagementService service;
	private UUID ownerId;

	@BeforeEach
	void setUp() {
		redisTemplate = mock(StringRedisTemplate.class);
		hashOps = mock(HashOperations.class);
		setOps = mock(SetOperations.class);
		valueOps = mock(ValueOperations.class);
		when(redisTemplate.<String, String>opsForHash()).thenReturn(hashOps);
		when(redisTemplate.<String, String>opsForSet()).thenReturn(setOps);
		when(redisTemplate.<String, String>opsForValue()).thenReturn(valueOps);
		users = mock(UserAccountRepository.class);
		jwtService = mock(JwtService.class);
		service = new KeyManagementService(redisTemplate);
		service.setUserAccountRepository(users);
		service.setJwtService(jwtService);
		ownerId = UUID.randomUUID();
		when(users.findById(ownerId)).thenReturn(Optional.of(new UserAccount("local", null, null, false)));
	}

	private static Jwt sessionFor(UUID userId) {
		Instant now = Instant.now();
		return new Jwt("session", now, now.plusSeconds(600),
				Map.of("alg", "HS256"), Map.of("sub", userId.toString()));
	}

	private void stubKey(SHA256Hash hash, UUID owner, boolean enabled, boolean revoked) {
		Map<String, String> entries = new LinkedHashMap<>();
		entries.put("ownerId", "tenant-a");
		entries.put("name", "key");
		entries.put("rpmLimit", "60");
		entries.put("tpmLimit", "1000");
		entries.put("enabled", Boolean.toString(enabled));
		entries.put("allowedModels", "");
		entries.put("allowedProviders", "");
		entries.put("createdAt", "2026-08-29T10:00:00Z");
		entries.put("keyPrefix", "gw-");
		if (owner != null) {
			entries.put("ownerUserId", owner.toString());
		}
		entries.put("revoked", Boolean.toString(revoked));
		when(redisTemplate.hasKey("apikey:" + hash.hex())).thenReturn(Boolean.TRUE);
		when(hashOps.entries("apikey:" + hash.hex())).thenReturn(entries);
	}

	@Test
	@DisplayName("default selector resolves the user's default key")
	void defaultSelectorResolves() {
		SHA256Hash hash = SHA256Hash.fromRawKey(FIXED_PLAINTEXT);
		stubKey(hash, ownerId, true, false);
		when(valueOps.get("userkey:" + ownerId + ":default")).thenReturn(hash.hex());
		when(jwtService.validate("session-jwt")).thenReturn(sessionFor(ownerId));

		Optional<VirtualApiKey> resolved = service.resolveActAsSelf("session-jwt", null);

		assertTrue(resolved.isPresent());
		assertEquals(ownerId, resolved.get().ownerUserId());
	}

	@Test
	@DisplayName("explicit selector resolves an owned key")
	void explicitSelectorResolves() {
		SHA256Hash hash = SHA256Hash.fromRawKey(FIXED_PLAINTEXT);
		stubKey(hash, ownerId, true, false);
		when(jwtService.validate("session-jwt")).thenReturn(sessionFor(ownerId));

		Optional<VirtualApiKey> resolved = service.resolveActAsSelf("session-jwt", hash.hex());

		assertTrue(resolved.isPresent());
	}

	@Test
	@DisplayName("other users keys never resolve")
	void foreignKeysNeverResolve() {
		SHA256Hash hash = SHA256Hash.fromRawKey(FIXED_PLAINTEXT);
		stubKey(hash, UUID.randomUUID(), true, false);
		when(jwtService.validate("session-jwt")).thenReturn(sessionFor(ownerId));

		assertTrue(service.resolveActAsSelf("session-jwt", hash.hex()).isEmpty());
	}

	@Test
	@DisplayName("revoked, disabled, and missing defaults resolve empty")
	void unusableDefaultsResolveEmpty() {
		SHA256Hash hash = SHA256Hash.fromRawKey(FIXED_PLAINTEXT);
		stubKey(hash, ownerId, true, true);
		when(valueOps.get("userkey:" + ownerId + ":default")).thenReturn(hash.hex());
		when(jwtService.validate("session-jwt")).thenReturn(sessionFor(ownerId));

		assertTrue(service.resolveActAsSelf("session-jwt", null).isEmpty());
		assertTrue(service.resolveActAsSelf("session-jwt", "default").isEmpty());
	}

	@Test
	@DisplayName("bad tokens, unknown users, and malformed selectors resolve empty")
	void failuresResolveEmpty() {
		when(jwtService.validate("bad-jwt")).thenThrow(new RuntimeException("bad signature"));
		assertTrue(service.resolveActAsSelf("bad-jwt", null).isEmpty());
		assertTrue(service.resolveActAsSelf(null, null).isEmpty());

		UUID gone = UUID.randomUUID();
		when(users.findById(gone)).thenReturn(Optional.empty());
		when(jwtService.validate("ghost-jwt")).thenReturn(sessionFor(gone));
		assertTrue(service.resolveActAsSelf("ghost-jwt", null).isEmpty());

		when(jwtService.validate("session-jwt")).thenReturn(sessionFor(ownerId));
		assertTrue(service.resolveActAsSelf("session-jwt", "not-hex").isEmpty());
		assertTrue(service.resolveActAsSelf("session-jwt", null).isEmpty());
	}

	@Test
	@DisplayName("default key preference is owned-scoped and clearable")
	void defaultPreferenceScoped() {
		SHA256Hash hash = SHA256Hash.fromRawKey(FIXED_PLAINTEXT);
		stubKey(hash, ownerId, true, false);

		service.setDefaultKey(ownerId, hash);
		verify(valueOps).set("userkey:" + ownerId + ":default", hash.hex());

		when(valueOps.get("userkey:" + ownerId + ":default")).thenReturn(hash.hex());
		assertEquals(hash, service.defaultKey(ownerId).orElseThrow());

		service.clearDefaultKey(ownerId);
		verify(redisTemplate).delete("userkey:" + ownerId + ":default");
	}

	@Test
	@DisplayName("default preference rejects foreign keys")
	void defaultPreferenceValidatesOwnership() {
		SHA256Hash foreign = SHA256Hash.fromRawKey(FIXED_PLAINTEXT);
		stubKey(foreign, UUID.randomUUID(), true, false);

		try {
			service.setDefaultKey(ownerId, foreign);
			assertTrue(false, "expected IllegalArgumentException");
		} catch (IllegalArgumentException expected) {
			assertTrue(expected.getMessage().contains("owned"));
		}
	}

	@Test
	@DisplayName("blank tokens and subject-less tokens resolve empty")
	void blankAndSubjectlessResolveEmpty() {
		assertTrue(service.resolveActAsSelf("  ", null).isEmpty());

		Instant now = Instant.now();
		Jwt subjectless = new Jwt("session", now, now.plusSeconds(600),
				Map.of("alg", "HS256"), Map.of("aud", "test"));
		when(jwtService.validate("subjectless-jwt")).thenReturn(subjectless);

		assertTrue(service.resolveActAsSelf("subjectless-jwt", null).isEmpty());
	}

	@Test
	@DisplayName("malformed stored defaults resolve empty")
	void malformedDefaultResolvesEmpty() {
		when(valueOps.get("userkey:" + ownerId + ":default")).thenReturn("not-hex");
		when(jwtService.validate("session-jwt")).thenReturn(sessionFor(ownerId));

		assertTrue(service.resolveActAsSelf("session-jwt", null).isEmpty());
	}
}
