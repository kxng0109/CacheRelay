package io.github.kxng0109.cacherelay.auth.backfill;

import java.io.IOException;
import java.net.http.HttpClient;
import java.security.PrivateKey;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Pattern;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import tools.jackson.databind.JsonNode;

/**
 * Okta Users API backfill: service-app token (OAuth private key JWT) then
 * one user's status plus group memberships.
 *
 * <p>Least privilege by construction: the assertion requests
 * {@code okta.users.read} only, and the JWT header always carries the
 * registered {@code kid}. Non-{@code ACTIVE} statuses and deleted users deny
 * login; every transport, protocol, or payload surprise fails closed. Link
 * follow-ups accept same-host URLs only up to a small cap.</p>
 */
public class OktaBackfillClient {

	private static final Pattern USER_ID_PATTERN = Pattern.compile("^[A-Za-z0-9_-]{1,64}$");

	private static final int MAX_PAGES = 3;

	private final String clientId;

	private final String privateKeyPem;

	private final String keyId;

	private final Clock clock;

	private final HttpClient http;

	private final Cache<String, CachedToken> tokenCache;

	/**
	 * Creates the client.
	 *
	 * @param clientId       OAuth service-app id, never {@code null}
	 * @param privateKeyPem  PKCS8 private key PEM, never {@code null} (never logged)
	 * @param keyId          registered JWK key id, never {@code null}
	 * @param clock          clock for token expiry, never {@code null}
	 */
	public OktaBackfillClient(String clientId, String privateKeyPem, String keyId,
			Clock clock) {
		this.clientId = clientId;
		this.privateKeyPem = privateKeyPem;
		this.keyId = keyId;
		this.clock = clock;
		this.http = BackfillHttp.newClient();
		this.tokenCache = Caffeine.newBuilder().maximumSize(4).build();
	}

	/**
	 * Fetches one user's groups and disabled state.
	 *
	 * @param domain Okta domain URL without trailing slash, never {@code null}
	 * @param userId Okta user id, never {@code null}
	 * @return groups plus disabled flag, or empty to deny on any failure
	 */
	public Optional<BackfillResult> fetch(String domain, String userId) {
		if (domain == null || userId == null || !USER_ID_PATTERN.matcher(userId).matches()) {
			return Optional.empty();
		}
		String token = serviceToken(domain);
		if (token == null) {
			return Optional.empty();
		}
		Optional<Boolean> disabled = userDisabled(token, domain, userId);
		if (disabled.isEmpty()) {
			return Optional.empty();
		}
		if (disabled.get()) {
			return Optional.of(BackfillResult.forDisabledAccount());
		}
		return readGroups(token, domain, userId);
	}

	private String serviceToken(String domain) {
		CachedToken cached = tokenCache.getIfPresent("okta");
		if (cached != null && clock.instant().isBefore(cached.expiresAt())) {
			return cached.token();
		}
		PrivateKey key = JwtAssertions.parseRsaPrivateKey(privateKeyPem);
		if (key == null) {
			return null;
		}
		String assertion = JwtAssertions.signRs256(key, keyId, clientId, clientId,
				domain + "/oauth2/v1/token", clock.instant(), Duration.ofSeconds(300L), Map.of());
		if (assertion == null) {
			return null;
		}
		String form = "grant_type=client_credentials"
				+ "&scope=" + BackfillHttp.encodeForm("okta.users.read")
				+ "&client_assertion_type=" + BackfillHttp.encodeForm(
						"urn:ietf:params:oauth:client-assertion-type:jwt-bearer")
				+ "&client_assertion=" + BackfillHttp.encodeForm(assertion);
		try {
			BackfillHttp.HttpResult result = BackfillHttp.postForm(http,
					domain + "/oauth2/v1/token", form);
			if (result.status() != 200) {
				return null;
			}
			JsonNode root = BackfillHttp.parseJson(result.body());
			String token = root.path("access_token").asString(null);
			long expiresIn = root.path("expires_in").asLong(3600L);
			if (token == null || token.isEmpty()) {
				return null;
			}
			tokenCache.put("okta", new CachedToken(token,
					clock.instant().plusSeconds(Math.max(60L, expiresIn - 300L))));
			return token;
		} catch (IOException | RuntimeException failed) {
			return null;
		} catch (InterruptedException interrupted) {
			Thread.currentThread().interrupt();
			return null;
		}
	}

	private Optional<Boolean> userDisabled(String token, String domain, String userId) {
		try {
			BackfillHttp.HttpResult result = BackfillHttp.get(http,
					domain + "/api/v1/users/" + userId, token);
			if (result.status() == 404) {
				return Optional.of(true);
			}
			if (result.status() != 200) {
				return Optional.empty();
			}
			JsonNode root = BackfillHttp.parseJson(result.body());
			JsonNode status = root.path("status");
			if (!status.isTextual()) {
				return Optional.empty();
			}
			return Optional.of(!"ACTIVE".equals(status.asString()));
		} catch (IOException | RuntimeException failed) {
			return Optional.empty();
		} catch (InterruptedException interrupted) {
			Thread.currentThread().interrupt();
			return Optional.empty();
		}
	}

	private Optional<BackfillResult> readGroups(String token, String domain, String userId) {
		Map<String, String> groups = new LinkedHashMap<>();
		String url = domain + "/api/v1/users/" + userId + "/groups?limit=200";
		for (int page = 0; ; page++) {
			BackfillHttp.HttpResult result;
			try {
				result = BackfillHttp.get(http, url, token);
			} catch (IOException | RuntimeException failed) {
				return Optional.empty();
			} catch (InterruptedException interrupted) {
				Thread.currentThread().interrupt();
				return Optional.empty();
			}
			if (result.status() != 200) {
				return Optional.empty();
			}
			JsonNode root;
			try {
				root = BackfillHttp.parseJson(result.body());
			} catch (IOException malformed) {
				return Optional.empty();
			}
			if (!root.isArray()) {
				return Optional.empty();
			}
			for (JsonNode entry : root) {
				String id = entry.path("id").asString("");
				if (id.isBlank()) {
					continue;
				}
				String name = entry.path("profile").path("name").asString(null);
				if (name == null || name.isBlank()) {
					return Optional.empty();
				}
				groups.putIfAbsent(id, name);
			}
			String next = BackfillHttp.parseLinkNext(result.header("Link"));
			if (next == null) {
				return Optional.of(new BackfillResult(
						Collections.unmodifiableMap(new LinkedHashMap<>(groups)), false));
			}
			if (page + 1 >= MAX_PAGES || !next.startsWith(domain)) {
				return Optional.empty();
			}
			url = next;
		}
	}

	private record CachedToken(String token, Instant expiresAt) {
	}
}
