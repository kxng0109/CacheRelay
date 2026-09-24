package io.github.kxng0109.cacherelay.auth.backfill;

import java.io.IOException;
import java.net.http.HttpClient;
import java.time.Clock;
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
 * Microsoft Graph backfill for Entra ID group overage: resolves one user's
 * transitive group memberships plus the account-enabled flag using
 * application permissions.
 *
 * <p>Triggering belongs to the orchestrator (overage marker, absent groups
 * claim). Every transport, protocol, or payload surprise fails closed. A
 * {@code null} display name signals a permission deficit, never an empty
 * name, and denies the whole fetch. Pagination follows same-host follow-ups
 * up to a small cap; anything beyond denies rather than partially
 * provisioning.</p>
 */
public class EntraGraphBackfillClient {

	private static final Pattern OID_PATTERN = Pattern.compile("^[A-Za-z0-9-]{1,64}$");

	private static final Pattern TENANT_PATTERN = Pattern.compile("^[A-Za-z0-9.-]{1,128}$");

	private static final int MAX_PAGES = 3;

	private final String clientId;

	private final String clientSecret;

	private final String loginBase;

	private final String graphBase;

	private final Clock clock;

	private final HttpClient http;

	private final Cache<String, CachedToken> tokenCache;

	/**
	 * Creates the client.
	 *
	 * @param clientId     Entra app id, never {@code null}
	 * @param clientSecret Entra app secret, never {@code null} (never logged)
	 * @param loginBase    login host (sovereign-aware), no trailing slash, never {@code null}
	 * @param graphBase    Graph host (sovereign-aware), no trailing slash, never {@code null}
	 * @param clock        clock for token expiry, never {@code null}
	 */
	public EntraGraphBackfillClient(String clientId, String clientSecret, String loginBase,
			String graphBase, Clock clock) {
		this.clientId = clientId;
		this.clientSecret = clientSecret;
		this.loginBase = loginBase;
		this.graphBase = graphBase;
		this.clock = clock;
		this.http = BackfillHttp.newClient();
		this.tokenCache = Caffeine.newBuilder().maximumSize(16).build();
	}

	/**
	 * Fetches one user's groups and disabled state.
	 *
	 * @param oid    Entra object id, never {@code null}
	 * @param tenant Entra tenant id or domain, never {@code null}
	 * @return groups plus disabled flag, or empty to deny on any failure
	 */
	public Optional<BackfillResult> fetch(String oid, String tenant) {
		if (oid == null || tenant == null || !OID_PATTERN.matcher(oid).matches()
				|| !TENANT_PATTERN.matcher(tenant).matches()) {
			return Optional.empty();
		}
		String token = appToken(tenant);
		if (token == null) {
			return Optional.empty();
		}
		Optional<Boolean> disabled = accountDisabled(token, oid, tenant);
		if (disabled.isEmpty()) {
			return Optional.empty();
		}
		if (disabled.get()) {
			return Optional.of(BackfillResult.forDisabledAccount());
		}
		return readGroups(token, oid);
	}

	private String appToken(String tenant) {
		CachedToken cached = tokenCache.getIfPresent(tenant);
		if (cached != null && clock.instant().isBefore(cached.expiresAt())) {
			return cached.token();
		}
		String form = "client_id=" + BackfillHttp.encodeForm(clientId)
				+ "&scope=" + BackfillHttp.encodeForm("https://graph.microsoft.com/.default")
				+ "&client_secret=" + BackfillHttp.encodeForm(clientSecret)
				+ "&grant_type=client_credentials";
		try {
			BackfillHttp.HttpResult result = BackfillHttp.postForm(http,
					loginBase + "/" + tenant + "/oauth2/v2.0/token", form);
			if (result.status() != 200) {
				return null;
			}
			JsonNode root = BackfillHttp.parseJson(result.body());
			String token = root.path("access_token").asString(null);
			long expiresIn = root.path("expires_in").asLong(3600L);
			if (token == null || token.isEmpty()) {
				return null;
			}
			tokenCache.put(tenant, new CachedToken(token,
					clock.instant().plusSeconds(Math.max(60L, expiresIn - 300L))));
			return token;
		} catch (IOException | RuntimeException failed) {
			return null;
		} catch (InterruptedException interrupted) {
			Thread.currentThread().interrupt();
			return null;
		}
	}

	private Optional<Boolean> accountDisabled(String token, String oid, String tenant) {
		try {
			BackfillHttp.HttpResult result = BackfillHttp.get(http,
					graphBase + "/v1.0/users/" + oid + "?$select=accountEnabled", token);
			if (result.status() == 404) {
				return Optional.of(true);
			}
			if (result.status() != 200) {
				return Optional.empty();
			}
			JsonNode root = BackfillHttp.parseJson(result.body());
			JsonNode flag = root.path("accountEnabled");
			if (!flag.isBoolean()) {
				return Optional.empty();
			}
			return Optional.of(!flag.asBoolean());
		} catch (IOException | RuntimeException failed) {
			return Optional.empty();
		} catch (InterruptedException interrupted) {
			Thread.currentThread().interrupt();
			return Optional.empty();
		}
	}

	private Optional<BackfillResult> readGroups(String token, String oid) {
		Map<String, String> groups = new LinkedHashMap<>();
		String url = graphBase + "/v1.0/users/" + oid
				+ "/transitiveMemberOf/microsoft.graph.group?$select=id,displayName&$top=999";
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
			for (JsonNode entry : root.path("value")) {
				String id = entry.path("id").asString("");
				if (id.isBlank()) {
					continue;
				}
				JsonNode name = entry.path("displayName");
				if (name.isNull() || name.asString("").isBlank()) {
					return Optional.empty();
				}
				groups.putIfAbsent(id, name.asString());
			}
			String next = root.path("@odata.nextLink").asString(null);
			if (next == null || next.isBlank()) {
				return Optional.of(new BackfillResult(
						Collections.unmodifiableMap(new LinkedHashMap<>(groups)), false));
			}
			if (page + 1 >= MAX_PAGES || !next.startsWith(graphBase)) {
				return Optional.empty();
			}
			url = next;
		}
	}

	private record CachedToken(String token, Instant expiresAt) {
	}
}
