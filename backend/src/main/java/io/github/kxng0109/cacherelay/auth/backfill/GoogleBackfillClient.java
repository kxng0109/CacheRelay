package io.github.kxng0109.cacherelay.auth.backfill;

import java.io.IOException;
import java.net.http.HttpClient;
import java.security.PrivateKey;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Pattern;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import tools.jackson.databind.JsonNode;

/**
 * Workspace Directory API backfill: short-lived domain-delegated token, one
 * user's suspended/archived state, then group memberships.
 *
 * <p>ID tokens never carry groups, so this mode always backfills on first
 * login. Suspended, archived, and deleted users deny login; every transport,
 * protocol, or payload surprise fails closed. Group pages merge up to a
 * small cap; truncation denies rather than partially provisioning.</p>
 */
public class GoogleBackfillClient {

	static final String DEFAULT_TOKEN_URI = "https://oauth2.googleapis.com/token";

	static final String DEFAULT_DIRECTORY_BASE = "https://admin.googleapis.com";

	private static final Pattern EMAIL_PATTERN = Pattern.compile("^[^@\\s]+@[^@\\s]+$");

	private static final Pattern DIRECTORY_ID_PATTERN = Pattern.compile("^[0-9]{1,32}$");

	private static final int MAX_PAGES = 3;

	private static final String SCOPES = "https://www.googleapis.com/auth/admin.directory.group.readonly"
			+ " https://www.googleapis.com/auth/admin.directory.user.readonly";

	private final String serviceAccountJson;

	private final String adminSubject;

	private final Clock clock;

	private final String directoryBase;

	private final String defaultTokenUri;

	private final HttpClient http;

	private final Cache<String, CachedToken> tokenCache;

	/**
	 * Creates the client against the production endpoints.
	 *
	 * @param serviceAccountJson service-account JSON key, never {@code null} (never logged)
	 * @param adminSubject       delegated admin subject, never {@code null}
	 * @param clock              clock for token expiry, never {@code null}
	 */
	public GoogleBackfillClient(String serviceAccountJson, String adminSubject, Clock clock) {
		this(serviceAccountJson, adminSubject, clock, DEFAULT_DIRECTORY_BASE, null);
	}

	/**
	 * Creates the client against explicit endpoints (tests).
	 *
	 * @param serviceAccountJson service-account JSON key, never {@code null} (never logged)
	 * @param adminSubject       delegated admin subject, never {@code null}
	 * @param clock              clock for token expiry, never {@code null}
	 * @param directoryBase      Directory host without trailing slash, never {@code null}
	 * @param tokenUri           token endpoint, or {@code null} for the key's default
	 */
	public GoogleBackfillClient(String serviceAccountJson, String adminSubject, Clock clock,
			String directoryBase, String tokenUri) {
		this.serviceAccountJson = serviceAccountJson;
		this.adminSubject = adminSubject;
		this.clock = clock;
		this.directoryBase = directoryBase;
		this.defaultTokenUri = tokenUri;
		this.http = BackfillHttp.newClient();
		this.tokenCache = Caffeine.newBuilder().maximumSize(4).build();
	}

	/**
	 * Fetches one user's groups and disabled state.
	 *
	 * @param email  verified user email, never {@code null}
	 * @param domain workspace domain, never {@code null}
	 * @return groups plus disabled flag, or empty to deny on any failure
	 */
	public Optional<BackfillResult> fetch(String email, String domain) {
		if (email == null || domain == null || !EMAIL_PATTERN.matcher(email).matches()
				|| !email.toLowerCase(Locale.ROOT)
						.endsWith("@" + domain.toLowerCase(Locale.ROOT))) {
			return Optional.empty();
		}
		String token = delegatedToken();
		if (token == null) {
			return Optional.empty();
		}
		Optional<Boolean> disabled = userDisabled(token, email);
		if (disabled.isEmpty()) {
			return Optional.empty();
		}
		if (disabled.get()) {
			return Optional.of(BackfillResult.forDisabledAccount());
		}
		return readGroups(token, email, domain);
	}

	/**
	 * Fetches one user's groups by immutable Directory id (revalidation
	 * sweeps carry ids, never emails).
	 *
	 * @param userId immutable Directory user id, never {@code null}
	 * @param domain workspace domain, never {@code null}
	 * @return groups plus disabled flag, or empty to deny on any failure
	 */
	public Optional<BackfillResult> fetchById(String userId, String domain) {
		if (userId == null || domain == null || domain.isBlank()
				|| !DIRECTORY_ID_PATTERN.matcher(userId).matches()) {
			return Optional.empty();
		}
		String token = delegatedToken();
		if (token == null) {
			return Optional.empty();
		}
		Optional<Boolean> disabled = userDisabled(token, userId);
		if (disabled.isEmpty()) {
			return Optional.empty();
		}
		if (disabled.get()) {
			return Optional.of(BackfillResult.forDisabledAccount());
		}
		return readGroups(token, userId, domain);
	}

	private String delegatedToken() {
		CachedToken cached = tokenCache.getIfPresent("google");
		if (cached != null && clock.instant().isBefore(cached.expiresAt())) {
			return cached.token();
		}
		ServiceAccountKey key = serviceKey();
		if (key == null) {
			return null;
		}
		String assertion = JwtAssertions.signRs256(key.privateKey(), null, key.clientEmail(),
				adminSubject, key.tokenUri(), clock.instant(), Duration.ofSeconds(600L),
				Map.of("scope", SCOPES));
		if (assertion == null) {
			return null;
		}
		String form = "grant_type=" + BackfillHttp.encodeForm(
				"urn:ietf:params:oauth:grant-type:jwt-bearer")
				+ "&assertion=" + BackfillHttp.encodeForm(assertion);
		try {
			BackfillHttp.HttpResult result = BackfillHttp.postForm(http, key.tokenUri(), form);
			if (result.status() != 200) {
				return null;
			}
			JsonNode root = BackfillHttp.parseJson(result.body());
			String token = root.path("access_token").asString(null);
			long expiresIn = root.path("expires_in").asLong(3600L);
			if (token == null || token.isEmpty()) {
				return null;
			}
			tokenCache.put("google", new CachedToken(token,
					clock.instant().plusSeconds(Math.max(60L, expiresIn - 300L))));
			return token;
		} catch (IOException | RuntimeException failed) {
			return null;
		} catch (InterruptedException interrupted) {
			Thread.currentThread().interrupt();
			return null;
		}
	}

	private ServiceAccountKey serviceKey() {
		try {
			JsonNode root = BackfillHttp.parseJson(serviceAccountJson);
			String clientEmail = root.path("client_email").asString(null);
			String privateKey = root.path("private_key").asString(null);
			if (clientEmail == null || clientEmail.isBlank()
					|| privateKey == null || privateKey.isBlank()) {
				return null;
			}
			PrivateKey key = JwtAssertions.parseRsaPrivateKey(privateKey);
			if (key == null) {
				return null;
			}
			String tokenUri = root.path("token_uri").asString(null);
			if (tokenUri == null || tokenUri.isBlank()) {
				tokenUri = defaultTokenUri != null ? defaultTokenUri : DEFAULT_TOKEN_URI;
			}
			return new ServiceAccountKey(clientEmail, key, tokenUri);
		} catch (IOException | RuntimeException failed) {
			return null;
		}
	}

	private Optional<Boolean> userDisabled(String token, String email) {
		try {
			BackfillHttp.HttpResult result = BackfillHttp.get(http, directoryBase
					+ "/admin/directory/v1/users/" + BackfillHttp.encodeForm(email)
					+ "?fields=suspended,archived", token);
			if (result.status() == 404) {
				return Optional.of(true);
			}
			if (result.status() != 200) {
				return Optional.empty();
			}
			JsonNode root = BackfillHttp.parseJson(result.body());
			JsonNode suspended = root.path("suspended");
			JsonNode archived = root.path("archived");
			if (!suspended.isBoolean() && !archived.isBoolean()) {
				return Optional.empty();
			}
			return Optional.of(suspended.asBoolean(false) || archived.asBoolean(false));
		} catch (IOException | RuntimeException failed) {
			return Optional.empty();
		} catch (InterruptedException interrupted) {
			Thread.currentThread().interrupt();
			return Optional.empty();
		}
	}

	private Optional<BackfillResult> readGroups(String token, String email, String domain) {
		Map<String, String> groups = new LinkedHashMap<>();
		String url = directoryBase + "/admin/directory/v1/groups?userKey="
				+ BackfillHttp.encodeForm(email) + "&domain=" + BackfillHttp.encodeForm(domain)
				+ "&maxResults=200&fields=" + BackfillHttp.encodeForm("groups(id,email,name),nextPageToken");
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
			JsonNode entries = root.path("groups");
			if (!entries.isArray()) {
				return Optional.empty();
			}
			for (JsonNode entry : entries) {
				String id = entry.path("id").asString("");
				if (id.isBlank()) {
					continue;
				}
				String name = entry.path("name").asString(null);
				if (name == null || name.isBlank()) {
					return Optional.empty();
				}
				groups.putIfAbsent(id, name);
			}
			String next = root.path("nextPageToken").asString(null);
			if (next == null || next.isBlank()) {
				return Optional.of(new BackfillResult(
						Collections.unmodifiableMap(new LinkedHashMap<>(groups)), false));
			}
			if (page + 1 >= MAX_PAGES) {
				return Optional.empty();
			}
			url = directoryBase + "/admin/directory/v1/groups?userKey="
					+ BackfillHttp.encodeForm(email) + "&domain=" + BackfillHttp.encodeForm(domain)
					+ "&maxResults=200&pageToken=" + BackfillHttp.encodeForm(next)
					+ "&fields=" + BackfillHttp.encodeForm("groups(id,email,name),nextPageToken");
		}
	}

	private record ServiceAccountKey(String clientEmail, PrivateKey privateKey, String tokenUri) {
	}

	private record CachedToken(String token, Instant expiresAt) {
	}
}
