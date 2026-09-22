package io.github.kxng0109.cacherelay.security.ratelimit;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import io.github.kxng0109.cacherelay.auth.JwtService;
import io.github.kxng0109.cacherelay.auth.UserAccount;
import io.github.kxng0109.cacherelay.auth.UserAccountRepository;
import io.github.kxng0109.cacherelay.cache.contracts.CacheScope;
import io.github.kxng0109.cacherelay.contracts.BootstrapKey;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;
import io.github.kxng0109.cacherelay.contracts.GatewayProperties;
import io.github.kxng0109.cacherelay.contracts.SHA256Hash;
import io.github.kxng0109.cacherelay.contracts.VirtualApiKey;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Service;

import lombok.extern.slf4j.Slf4j;

import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.stream.Collectors;

/**
 * Lifecycle and lookup for gateway-managed virtual API keys.
 *
 * <p>Keys are identified by the SHA-256 digest of their plaintext. The plaintext is
 * generated once (for {@link #generateKey(BootstrapKey)}) or supplied by configuration (bootstrap keys) and is never
 * persisted: only its digest and metadata are stored in a Redis hash under {@code apikey:{hex}}. A short-TTL Caffeine
 * cache absorbs hot request-path lookups, including confirmed misses.</p>
 *
 * <p>Boot-time seeding of configured keys is owned by {@link BootstrapKeySeeder}; this
 * service only provides the idempotent {@link #seedBootstrapKeys(GatewayProperties)} operation the seeder invokes. The
 * request path is fail-closed: when Redis is unreachable, {@link #findByHash(SHA256Hash)} lets the underlying
 * {@link org.springframework.dao.DataAccessException} (or connection-pool exception) propagate to the caller, which
 * maps it to HTTP 503. A key is never silently treated as absent merely because the backend was down.</p>
 */
@Service
@Slf4j
public class KeyManagementService {

	private static final String REDIS_KEY_PREFIX = "apikey:";
	private static final String INDEX_KEY = "admin:keys";
	private static final String KEY_PREFIX_RAW = "gw-";
	private static final int RANDOM_SUFFIX_LENGTH = 32;
	private static final char[] URL_SAFE_ALPHABET =
			"abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789-_".toCharArray();

	private static final SecureRandom SECURE_RANDOM = new SecureRandom();

	/**
	 * Codec for the JSON-array persistence of RBAC visibility sets (FS-04). Thread-safe
	 * after construction; shared across all key operations.
	 */
	private static final ObjectMapper SET_CODEC = new ObjectMapper();

	/**
	 * Injection handling persisted for seeded keys. Bootstrap templates carry no injection
	 * knob, so seeds keep the safe default (block); operators relax via the admin API.
	 */
	private static final boolean SEED_INJECTION_BLOCK = true;

	/**
	 * Atomically claims a bootstrap-key slot: stores the full metadata hash plus the index entry only when the
	 * digest key does not exist yet, and reports whether this caller won the claim. The check and the write execute
	 * inside one Lua script, so any number of instances booting concurrently converge on a single deterministic
	 * record instead of last-writer-wins field flapping.
	 *
	 * <p>ARGV layout is self-sizing on purpose: field/value pairs first (always an even count), the digest hex last.
	 * No positional constants may be introduced without updating the pairing below.</p>
	 */
	private static final DefaultRedisScript<Long> SEED_IF_ABSENT_SCRIPT = new DefaultRedisScript<>(
			"if redis.call('EXISTS', KEYS[1]) == 1 then return 0 end "
					+ "redis.call('HSET', KEYS[1], unpack(ARGV, 1, #ARGV - 1)) "
					+ "redis.call('SADD', KEYS[2], ARGV[#ARGV]) "
					+ "return 1",
			Long.class);

	private final StringRedisTemplate redisTemplate;

	/**
	 * Short-TTL local cache of resolved keys, holding {@link Optional}s so a confirmed miss is cached without colliding
	 * with an in-flight load. Exceptions thrown by the loader are never cached: they propagate to the caller (fail
	 * closed). Sized by {@link RateLimitProperties}.
	 */
	private final Cache<SHA256Hash, Optional<VirtualApiKey>> cache;

	/**
	 * Short-TTL cache of owner activity used only as a backstop on the request
	 * path. Immediacy comes from cascade revocation writing the keys themselves;
	 * this cache merely bounds the cost of the per-request owner join.
	 */
	private final Cache<UUID, Boolean> ownerActiveCache = Caffeine.newBuilder()
			.expireAfterWrite(Duration.ofSeconds(60))
			.maximumSize(5000)
			.build();

	private volatile UserAccountRepository userAccountRepository;

	private volatile JwtService jwtService;

	/**
	 * Wires the account repository backing owner validation. Optional on purpose:
	 * unit-constructed services keep working with owner checks in legacy-tolerant
	 * mode; production Spring always wires it.
	 *
	 * @param userAccountRepository the account repository, if available
	 */
	@Autowired(required = false)
	public void setUserAccountRepository(UserAccountRepository userAccountRepository) {
		this.userAccountRepository = userAccountRepository;
	}

	/**
	 * Wires the session-token service backing act-as-self resolution. Optional on
	 * purpose, like the account repository; without it self-resolution reads as
	 * absent.
	 *
	 * @param jwtService the session-token service, if available
	 */
	@Autowired(required = false)
	public void setJwtService(JwtService jwtService) {
		this.jwtService = jwtService;
	}

	/**
	 * Creates the service with default key-cache ceilings.
	 *
	 * @param redisTemplate Redis template
	 */
	public KeyManagementService(StringRedisTemplate redisTemplate) {
		this(redisTemplate, RateLimitProperties.DEFAULTS);
	}

	/**
	 * Creates the service with explicit key-cache ceilings.
	 *
	 * @param redisTemplate Redis template
	 * @param properties    key-cache ceilings
	 */
	@Autowired
	public KeyManagementService(StringRedisTemplate redisTemplate, RateLimitProperties properties) {
		this.redisTemplate = redisTemplate;
		this.cache = Caffeine.newBuilder()
		                     .expireAfterWrite(Duration.ofSeconds(properties.keyCacheTtlSeconds()))
		                     .maximumSize(properties.keyCacheMaximumSize())
		                     .build();
	}

	private static String randomPlaintext() {
		StringBuilder sb = new StringBuilder(KEY_PREFIX_RAW);
		for (int i = 0; i < RANDOM_SUFFIX_LENGTH; i++) {
			sb.append(URL_SAFE_ALPHABET[SECURE_RANDOM.nextInt(URL_SAFE_ALPHABET.length)]);
		}
		return sb.toString();
	}

	private static String prefixOf(String plaintextKey) {
		int idx = plaintextKey.indexOf('-');
		return idx > 0 ? plaintextKey.substring(0, idx + 1) : plaintextKey;
	}

	private static String toCsv(Set<String> values) {
		if (values == null || values.isEmpty()) {
			return "";
		}
		return String.join(",", values);
	}

	private static Set<String> parseCsv(String csv) {
		if (csv == null || csv.isBlank()) {
			return Set.of();
		}
		return Arrays.stream(csv.split(","))
		             .map(String::trim)
		             .filter(s -> !s.isEmpty())
		             .collect(Collectors.toUnmodifiableSet());
	}

	/**
	 * Encodes a visibility set as a JSON array (FS-04), sorted for deterministic storage.
	 * URIs and globs may contain commas, quotes, or brackets that CSV cannot represent.
	 *
	 * @param values set elements, possibly {@code null}
	 * @return JSON array string ({@code []} when empty)
	 */
	private static String encodeSet(Set<String> values) {
		try {
			List<String> sorted = values == null
					? List.of()
					: values.stream().filter(Objects::nonNull).sorted().toList();
			return SET_CODEC.writeValueAsString(sorted);
		} catch (JacksonException e) {
			throw new IllegalStateException("Failed to encode key policy set", e);
		}
	}

	/**
	 * Decodes a visibility set: JSON arrays (current format) or legacy CSV (tolerant
	 * reader for keys stored before FS-04). Corrupt JSON fails the whole load via
	 * {@link IllegalArgumentException} so the key resolves to a miss, never allow-all.
	 *
	 * @param raw stored value, possibly {@code null}
	 * @return decoded set (empty when blank)
	 */
	private static Set<String> parseSetField(String raw) {
		if (raw == null || raw.isBlank()) {
			return Set.of();
		}
		String trimmed = raw.trim();
		if (trimmed.startsWith("[")) {
			try {
				String[] elements = SET_CODEC.readValue(trimmed, String[].class);
				return elements == null
						? Set.of()
						: Arrays.stream(elements)
						        .filter(Objects::nonNull)
						        .collect(Collectors.toUnmodifiableSet());
			} catch (JacksonException e) {
				throw new IllegalArgumentException("Corrupt stored key policy set", e);
			}
		}
		return parseCsv(trimmed);
	}

	/**
	 * Writes every governance field shared by the create and seed paths into the given
	 * ordered map (FS-02 divergence guard: new policy fields are added here once, so the
	 * seed path can never silently drop what the store path persists; FS-04 will swap the
	 * set encoding to JSON inside this method only).
	 *
	 * @param fields             ordered destination map
	 * @param allowedModels      allowed model names
	 * @param allowedProviders   allowed provider names
	 * @param allowedTools       allowed tool globs
	 * @param deniedTools        denied tool globs
	 * @param allowedResources   allowed resource URI globs
	 * @param deniedResources    denied resource URI globs
	 * @param allowedPrompts     allowed prompt globs
	 * @param deniedPrompts      denied prompt globs
	 * @param injectionBlock     injection handling flag
	 * @param allowedCacheScopes cache isolation scopes
	 * @param ownerUserId        owning account id, possibly {@code null} for legacy rows
	 * @param revoked            terminal revocation tombstone
	 */
	private static void putPolicyFields(
			Map<String, String> fields,
			Set<String> allowedModels,
			Set<String> allowedProviders,
			Set<String> allowedTools,
			Set<String> deniedTools,
			Set<String> allowedResources,
			Set<String> deniedResources,
			Set<String> allowedPrompts,
			Set<String> deniedPrompts,
			boolean injectionBlock,
			Set<CacheScope> allowedCacheScopes,
			Set<String> allowedAgents,
			Set<String> deniedAgents,
			UUID ownerUserId,
			boolean revoked
	) {
		fields.put("allowedModels", toCsv(allowedModels));
		fields.put("allowedProviders", toCsv(allowedProviders));
		fields.put("allowedTools", toCsv(allowedTools));
		fields.put("deniedTools", toCsv(deniedTools));
		fields.put("allowedResources", encodeSet(allowedResources));
		fields.put("deniedResources", encodeSet(deniedResources));
		fields.put("allowedPrompts", encodeSet(allowedPrompts));
		fields.put("deniedPrompts", encodeSet(deniedPrompts));
		fields.put("injectionBlock", Boolean.toString(injectionBlock));
		fields.put("allowedCacheScopes", scopesToCsv(allowedCacheScopes));
		fields.put("allowedAgents", toCsv(allowedAgents));
		fields.put("deniedAgents", toCsv(deniedAgents));
		if (ownerUserId != null) {
			fields.put("ownerUserId", ownerUserId.toString());
		}
		fields.put("revoked", Boolean.toString(revoked));
	}

	/**
	 * Encodes cache scopes for Redis storage (enum names contain no commas, so the
	 * CSV format is unambiguous here, unlike resource URIs).
	 *
	 * @param scopes scopes to encode, possibly {@code null}
	 * @return comma-joined scope names, or empty when none
	 */
	private static String scopesToCsv(Set<CacheScope> scopes) {
		if (scopes == null || scopes.isEmpty()) {
			return "";
		}
		return scopes.stream()
		             .filter(Objects::nonNull)
		             .map(CacheScope::name)
		             .sorted()
		             .collect(Collectors.joining(","));
	}

	/**
	 * Decodes stored cache scopes, failing closed to TENANT-only when the value is
	 * missing, blank, or carries no recognizable scope (unknown names are dropped).
	 *
	 * @param csv stored scope list, possibly {@code null}
	 * @return non-empty scope set
	 */
	private static Set<CacheScope> parseScopes(String csv) {
		if (csv == null || csv.isBlank()) {
			return Set.of(CacheScope.TENANT);
		}
		EnumSet<CacheScope> scopes = EnumSet.noneOf(CacheScope.class);
		for (String token : csv.split(",")) {
			try {
				scopes.add(CacheScope.valueOf(token.trim().toUpperCase(Locale.ROOT)));
			} catch (IllegalArgumentException ignored) {
			}
		}
		return scopes.isEmpty()
				? Set.of(CacheScope.TENANT)
				: Collections.unmodifiableSet(scopes);
	}

	/**
	 * Decodes a stored owner id, tolerating legacy rows that predate user linkage.
	 * A present-but-malformed value fails the whole load via
	 * {@link IllegalArgumentException} so the key resolves to a miss, never to an
	 * unowned usable key.
	 *
	 * @param raw stored UUID string, possibly {@code null}
	 * @return the owner id, or {@code null} when absent
	 */
	private static UUID parseOwnerUserId(String raw) {
		if (raw == null || raw.isBlank()) {
			return null;
		}
		try {
			return UUID.fromString(raw.trim());
		} catch (IllegalArgumentException malformed) {
			throw new IllegalArgumentException("Corrupt stored key owner", malformed);
		}
	}

	/**
	 * Decodes the terminal revocation tombstone strictly: only {@code true} revokes.
	 * Absent reads as non-revoked for legacy rows; anything else fails the whole
	 * load so a corrupt flag can never silently disarm the tombstone in either
	 * direction.
	 *
	 * @param raw stored flag, possibly {@code null}
	 * @return whether the key is terminally revoked
	 */
	private static boolean parseRevoked(String raw) {
		if (raw == null) {
			return false;
		}
		if ("true".equalsIgnoreCase(raw)) {
			return true;
		}
		if ("false".equalsIgnoreCase(raw)) {
			return false;
		}
		throw new IllegalArgumentException("Corrupt stored key revocation flag");
	}

	private static String redisKey(SHA256Hash hash) {
		return REDIS_KEY_PREFIX + hash.hex();
	}

	/**
	 * Value object returned only upon key generation, pairing the cryptographic hash with the single-exposure
	 * plaintext.
	 *
	 * @param hash         SHA-256 digest of the key
	 * @param plaintextKey plaintext token (shown only once)
	 * @param key          stored key metadata
	 */
	public record CreatedKey(SHA256Hash hash, String plaintextKey, VirtualApiKey key) {
	}

	/**
	 * Generates a brand-new virtual API key from a {@link BootstrapKey} template and stores only its metadata hash in
	 * Redis.
	 *
	 * @param template key parameters (owner, label, limits, model/provider allow-lists)
	 * @return the plaintext key ({@code gw-} + 32 URL-safe characters); this is the only time the plaintext exists and
	 * it is never logged or stored
	 * @throws IllegalArgumentException when a wired repository cannot resolve the template owner to an active account
	 */
	public String generateKey(BootstrapKey template) {
		String plaintext = randomPlaintext();
		storeKey(
				plaintext,
				template.ownerId(),
				template.name(),
				template.rpmLimit(),
				template.tpmLimit(),
				template.allowedModels(),
				template.allowedProviders(),
				template.allowedTools(),
				template.deniedTools(),
				template.allowedResources(),
				template.deniedResources(),
				template.allowedPrompts(),
				template.deniedPrompts(),
				true,
				template.allowedCacheScopes(),
				Set.of(),
				Set.of(),
				resolveGeneratedOwner(template)
		);
		return plaintext;
	}

	/**
	 * Resolves a generation template owner to an account id. Explicit generation
	 * fails fast on unresolvable owners (unlike the retry-loop seed path); without
	 * a wired repository (unit tests only) resolution reads as unknown.
	 *
	 * @param template generation template
	 * @return the account id, or {@code null} when unwired
	 * @throws IllegalArgumentException when wired and the owner is missing, unknown, or disabled
	 */
	private UUID resolveGeneratedOwner(BootstrapKey template) {
		UserAccountRepository users = this.userAccountRepository;
		String username = template.ownerUsername();
		if (users == null) {
			return null;
		}
		if (username == null || username.isBlank()) {
			throw new IllegalArgumentException("owning account username is required");
		}
		return users.findByUsernameIgnoreCase(username.trim())
				.filter(account -> !account.isDisabled())
				.map(UserAccount::getId)
				.orElseThrow(() -> new IllegalArgumentException("owning account is unknown or disabled"));
	}

	/**
	 * Creates a new virtual API key with full governance rules including resource,
	 * prompt, injection, cache-scope, and A2A agent handling.
	 *
	 * @param ownerId            owner identifier
	 * @param name               label for the key
	 * @param rpmLimit           requests per minute limit (0 = unlimited)
	 * @param tpmLimit           tokens per minute limit (0 = unlimited)
	 * @param allowedModels      allowed model names (empty = all)
	 * @param allowedProviders   allowed provider names (empty = all)
	 * @param allowedTools       allowed tool names or glob patterns (empty = all)
	 * @param deniedTools        denied tool names or glob patterns (empty = none)
	 * @param allowedResources   allowed resource URI globs (empty = all visible)
	 * @param deniedResources    denied resource URI globs (empty = none hidden)
	 * @param allowedPrompts     allowed prompt name globs (empty = all visible)
	 * @param deniedPrompts      denied prompt name globs (empty = none hidden)
	 * @param injectionBlock     whether indirect prompt injection blocks delivery (null = default block)
	 * @param allowedCacheScopes cache isolation scopes (null or empty = TENANT only)
	 * @param ownerUserId       owning account id, or {@code null} when the caller does not supply one
	 * @return the created key object containing the plaintext and metadata
	 */
	public CreatedKey createKey(
			String ownerId,
			String name,
			int rpmLimit,
			int tpmLimit,
			Set<String> allowedModels,
			Set<String> allowedProviders,
			Set<String> allowedTools,
			Set<String> deniedTools,
			Set<String> allowedResources,
			Set<String> deniedResources,
			Set<String> allowedPrompts,
			Set<String> deniedPrompts,
			Boolean injectionBlock,
			Set<CacheScope> allowedCacheScopes,
			Set<String> allowedAgents,
			Set<String> deniedAgents,
			UUID ownerUserId
	) {
		requireActiveOwner(ownerUserId);
		String plaintext = randomPlaintext();
		Instant now = Instant.now();
		SHA256Hash hash = SHA256Hash.fromRawKey(plaintext);
		String keyPrefix = prefixOf(plaintext);
		VirtualApiKey metadata = new VirtualApiKey(
				hash,
				keyPrefix,
				ownerId,
				name,
				rpmLimit,
				tpmLimit,
				allowedModels,
				allowedProviders,
				allowedTools,
				deniedTools,
				allowedResources,
				deniedResources,
				allowedPrompts,
				deniedPrompts,
				injectionBlock == null || injectionBlock,
				true,
				now,
				allowedCacheScopes,
				allowedAgents,
				deniedAgents,
				ownerUserId,
				false
		);
		storeKey(
				plaintext,
				ownerId,
				name,
				rpmLimit,
				tpmLimit,
				allowedModels,
				allowedProviders,
				allowedTools,
				deniedTools,
				allowedResources,
				deniedResources,
				allowedPrompts,
				deniedPrompts,
				injectionBlock == null || injectionBlock,
				allowedCacheScopes,
				allowedAgents,
				deniedAgents,
				ownerUserId
		);
		return new CreatedKey(hash, plaintext, metadata);
	}

	/**
	 * Lists all virtual API keys registered in the gateway, optionally filtered by owner ID.
	 *
	 * @param ownerId optional owner ID to filter by; if null or blank, returns all keys
	 * @return list of virtual API keys sorted by creation time descending
	 */
	public List<VirtualApiKey> listKeys(String ownerId) {
		Set<String> hexes = redisTemplate.opsForSet().members(INDEX_KEY);
		if (hexes == null || hexes.isEmpty()) {
			return List.of();
		}
		List<VirtualApiKey> keys = new ArrayList<>();
		for (String hex : hexes) {
			try {
				SHA256Hash hash = SHA256Hash.fromHex(hex);
				findByHash(hash).ifPresent(key -> {
					if (ownerId == null || ownerId.isBlank() || ownerId.equals(key.ownerId())) {
						keys.add(key);
					}
				});
			} catch (IllegalArgumentException ignored) {
				// skip invalid hex entry in index
			}
		}
		keys.sort(Comparator.comparing(VirtualApiKey::createdAt).reversed());
		return Collections.unmodifiableList(keys);
	}

	/**
	 * Updates an existing key's metadata, invalidating the local cache.
	 *
	 * @param hash             key hash to update
	 * @param name             new name (or null to keep)
	 * @param rpmLimit         new RPM limit (or null to keep)
	 * @param tpmLimit         new TPM limit (or null to keep)
	 * @param allowedModels    new allowed models (or null to keep)
	 * @param allowedProviders new allowed providers (or null to keep)
	 * @param enabled          new enabled state (or null to keep)
	 * @return the updated key metadata, or empty if key was not found
	 */
	public Optional<VirtualApiKey> updateKey(
			SHA256Hash hash,
			String name,
			Integer rpmLimit,
			Integer tpmLimit,
			Set<String> allowedModels,
			Set<String> allowedProviders,
			Boolean enabled
	) {
		return updateKey(hash, name, rpmLimit, tpmLimit, allowedModels, allowedProviders, null, null, enabled);
	}

	/**
	 * Updates an existing key's metadata including tool governance rules, invalidating the local cache.
	 *
	 * @param hash             key hash to update
	 * @param name             new name (or null to keep)
	 * @param rpmLimit         new RPM limit (or null to keep)
	 * @param tpmLimit         new TPM limit (or null to keep)
	 * @param allowedModels    new allowed models (or null to keep)
	 * @param allowedProviders new allowed providers (or null to keep)
	 * @param allowedTools     new allowed tools (or null to keep)
	 * @param deniedTools      new denied tools (or null to keep)
	 * @param enabled          new enabled state (or null to keep)
	 * @return the updated key metadata, or empty if key was not found
	 */
	public Optional<VirtualApiKey> updateKey(
			SHA256Hash hash,
			String name,
			Integer rpmLimit,
			Integer tpmLimit,
			Set<String> allowedModels,
			Set<String> allowedProviders,
			Set<String> allowedTools,
			Set<String> deniedTools,
			Boolean enabled
	) {
		return updateKey(hash, name, rpmLimit, tpmLimit, allowedModels, allowedProviders,
				allowedTools, deniedTools, null, null, null, null, enabled);
	}

	/**
	 * Updates an existing key's metadata including resource and prompt visibility,
	 * invalidating the local cache.
	 *
	 * @param hash             key hash to update
	 * @param name             new name (or null to keep)
	 * @param rpmLimit         new RPM limit (or null to keep)
	 * @param tpmLimit         new TPM limit (or null to keep)
	 * @param allowedModels    new allowed models (or null to keep)
	 * @param allowedProviders new allowed providers (or null to keep)
	 * @param allowedTools     new allowed tools (or null to keep)
	 * @param deniedTools      new denied tools (or null to keep)
	 * @param allowedResources new allowed resource URI globs (or null to keep)
	 * @param deniedResources  new denied resource URI globs (or null to keep)
	 * @param allowedPrompts   new allowed prompt globs (or null to keep)
	 * @param deniedPrompts    new denied prompt globs (or null to keep)
	 * @param enabled          new enabled state (or null to keep)
	 * @return the updated key metadata, or empty if key was not found
	 */
	public Optional<VirtualApiKey> updateKey(
			SHA256Hash hash,
			String name,
			Integer rpmLimit,
			Integer tpmLimit,
			Set<String> allowedModels,
			Set<String> allowedProviders,
			Set<String> allowedTools,
			Set<String> deniedTools,
			Set<String> allowedResources,
			Set<String> deniedResources,
			Set<String> allowedPrompts,
			Set<String> deniedPrompts,
			Boolean enabled
	) {
		return updateKey(hash, name, rpmLimit, tpmLimit, allowedModels, allowedProviders,
				allowedTools, deniedTools, allowedResources, deniedResources, allowedPrompts,
				deniedPrompts, null, enabled);
	}

	/**
	 * Updates an existing key's metadata including full governance rules, invalidating the local cache.
	 *
	 * @param hash             key hash to update
	 * @param name             new name (or null to keep)
	 * @param rpmLimit         new RPM limit (or null to keep)
	 * @param tpmLimit         new TPM limit (or null to keep)
	 * @param allowedModels    new allowed models (or null to keep)
	 * @param allowedProviders new allowed providers (or null to keep)
	 * @param allowedTools     new allowed tools (or null to keep)
	 * @param deniedTools      new denied tools (or null to keep)
	 * @param allowedResources new allowed resource URI globs (or null to keep)
	 * @param deniedResources  new denied resource URI globs (or null to keep)
	 * @param allowedPrompts   new allowed prompt globs (or null to keep)
	 * @param deniedPrompts    new denied prompt globs (or null to keep)
	 * @param injectionBlock   new injection handling (or null to keep)
	 * @param enabled          new enabled state (or null to keep)
	 * @return the updated key metadata, or empty if key was not found
	 */
	public Optional<VirtualApiKey> updateKey(
			SHA256Hash hash,
			String name,
			Integer rpmLimit,
			Integer tpmLimit,
			Set<String> allowedModels,
			Set<String> allowedProviders,
			Set<String> allowedTools,
			Set<String> deniedTools,
			Set<String> allowedResources,
			Set<String> deniedResources,
			Set<String> allowedPrompts,
			Set<String> deniedPrompts,
			Boolean injectionBlock,
			Boolean enabled
	) {
		return updateKey(hash, name, rpmLimit, tpmLimit, allowedModels, allowedProviders,
				allowedTools, deniedTools, allowedResources, deniedResources, allowedPrompts,
				deniedPrompts, injectionBlock, null, null, null, enabled);
	}

	/**
	 * Backwards-compatible overload omitting the A2A agent policy sets (null = keep).
	 *
	 * @param hash               SHA-256 digest of the key
	 * @param name               new label (or null to keep)
	 * @param rpmLimit           new RPM limit (or null to keep)
	 * @param tpmLimit           new TPM limit (or null to keep)
	 * @param allowedModels      new allowed models (or null to keep)
	 * @param allowedProviders   new allowed providers (or null to keep)
	 * @param allowedTools       new allowed tools (or null to keep)
	 * @param deniedTools        new denied tools (or null to keep)
	 * @param allowedResources   new allowed resource URI globs (or null to keep)
	 * @param deniedResources    new denied resource URI globs (or null to keep)
	 * @param allowedPrompts     new allowed prompt globs (or null to keep)
	 * @param deniedPrompts      new denied prompt globs (or null to keep)
	 * @param injectionBlock     new injection handling (or null to keep)
	 * @param allowedCacheScopes new cache isolation scopes (or null to keep)
	 * @param enabled            new enabled state (or null to keep)
	 * @return the updated key metadata, or empty if key was not found
	 */
	public Optional<VirtualApiKey> updateKey(
			SHA256Hash hash,
			String name,
			Integer rpmLimit,
			Integer tpmLimit,
			Set<String> allowedModels,
			Set<String> allowedProviders,
			Set<String> allowedTools,
			Set<String> deniedTools,
			Set<String> allowedResources,
			Set<String> deniedResources,
			Set<String> allowedPrompts,
			Set<String> deniedPrompts,
			Boolean injectionBlock,
			Set<CacheScope> allowedCacheScopes,
			Boolean enabled
	) {
		return updateKey(hash, name, rpmLimit, tpmLimit, allowedModels, allowedProviders,
				allowedTools, deniedTools, allowedResources, deniedResources, allowedPrompts,
				deniedPrompts, injectionBlock, allowedCacheScopes, null, null, enabled);
	}

	/**
	 * Updates an existing key's metadata including full governance rules, cache
	 * scopes, and A2A agent policies, invalidating the local cache.
	 *
	 * @param hash               key hash to update
	 * @param name               new name (or null to keep)
	 * @param rpmLimit           new RPM limit (or null to keep)
	 * @param tpmLimit           new TPM limit (or null to keep)
	 * @param allowedModels      new allowed models (or null to keep)
	 * @param allowedProviders   new allowed providers (or null to keep)
	 * @param allowedTools       new allowed tools (or null to keep)
	 * @param deniedTools        new denied tools (or null to keep)
	 * @param allowedResources   new allowed resource URI globs (or null to keep)
	 * @param deniedResources    new denied resource URI globs (or null to keep)
	 * @param allowedPrompts     new allowed prompt globs (or null to keep)
	 * @param deniedPrompts      new denied prompt globs (or null to keep)
	 * @param injectionBlock     new injection handling (or null to keep)
	 * @param allowedCacheScopes new cache isolation scopes (or null to keep)
	 * @param enabled            new enabled state (or null to keep)
	 * @return the updated key metadata, or empty if key was not found
	 */
	public Optional<VirtualApiKey> updateKey(
			SHA256Hash hash,
			String name,
			Integer rpmLimit,
			Integer tpmLimit,
			Set<String> allowedModels,
			Set<String> allowedProviders,
			Set<String> allowedTools,
			Set<String> deniedTools,
			Set<String> allowedResources,
			Set<String> deniedResources,
			Set<String> allowedPrompts,
			Set<String> deniedPrompts,
			Boolean injectionBlock,
			Set<CacheScope> allowedCacheScopes,
			Set<String> allowedAgents,
			Set<String> deniedAgents,
			Boolean enabled
	) {
		String key = redisKey(hash);
		if (Boolean.FALSE.equals(redisTemplate.hasKey(key))) {
			return Optional.empty();
		}
		Optional<VirtualApiKey> current = findByHash(hash);
		if (current.isEmpty()) {
			return Optional.empty();
		}
		if (current.get().revoked() && Boolean.TRUE.equals(enabled)) {
			throw new IllegalArgumentException("terminally revoked keys cannot be re-enabled");
		}
		Map<String, String> updates = new LinkedHashMap<>();
		if (name != null) {
			updates.put("name", name);
		}
		if (rpmLimit != null) {
			updates.put("rpmLimit", Integer.toString(rpmLimit));
		}
		if (tpmLimit != null) {
			updates.put("tpmLimit", Integer.toString(tpmLimit));
		}
		if (allowedModels != null) {
			updates.put("allowedModels", toCsv(allowedModels));
		}
		if (allowedProviders != null) {
			updates.put("allowedProviders", toCsv(allowedProviders));
		}
		if (allowedTools != null) {
			updates.put("allowedTools", toCsv(allowedTools));
		}
		if (deniedTools != null) {
			updates.put("deniedTools", toCsv(deniedTools));
		}
		if (allowedResources != null) {
			updates.put("allowedResources", encodeSet(allowedResources));
		}
		if (deniedResources != null) {
			updates.put("deniedResources", encodeSet(deniedResources));
		}
		if (allowedPrompts != null) {
			updates.put("allowedPrompts", encodeSet(allowedPrompts));
		}
		if (deniedPrompts != null) {
			updates.put("deniedPrompts", encodeSet(deniedPrompts));
		}
		if (injectionBlock != null) {
			updates.put("injectionBlock", injectionBlock.toString());
		}
		if (allowedCacheScopes != null) {
			updates.put("allowedCacheScopes", scopesToCsv(allowedCacheScopes));
		}
		if (allowedAgents != null) {
			updates.put("allowedAgents", toCsv(allowedAgents));
		}
		if (deniedAgents != null) {
			updates.put("deniedAgents", toCsv(deniedAgents));
		}
		if (enabled != null) {
			updates.put("enabled", enabled.toString());
		}
		if (!updates.isEmpty()) {
			redisTemplate.opsForHash().putAll(key, updates);
			cache.invalidate(hash);
		}
		return findByHash(hash);
	}

	/**
	 * Permanently deletes a virtual API key from Redis and removes it from the index set.
	 *
	 * @param hash key hash to delete
	 * @return true if key was deleted, false if not found
	 */
	public boolean deleteKey(SHA256Hash hash) {
		String key = redisKey(hash);
		Boolean deleted = redisTemplate.delete(key);
		redisTemplate.opsForSet().remove(INDEX_KEY, hash.hex());
		cache.invalidate(hash);
		return Boolean.TRUE.equals(deleted);
	}

	/**
	 * Terminally revokes a key: sets the revocation tombstone and clears
	 * {@code enabled}, then evicts the local cache entry so the next lookup
	 * observes it. There is no inverse: nothing in this service can unset the
	 * tombstone, and {@link #updateKey} refuses to re-enable revoked keys.
	 * Idempotent.
	 *
	 * @param hash key hash to revoke
	 */
	public void revokeKey(SHA256Hash hash) {
		String key = redisKey(hash);
		redisTemplate.opsForHash().put(key, "revoked", "true");
		redisTemplate.opsForHash().put(key, "enabled", "false");
		cache.invalidate(hash);
	}

	/**
	 * Resolves a key by its hash, using the local short-TTL cache first.
	 *
	 * <p>Fail-closed: a Redis outage ({@link org.springframework.dao.DataAccessException}
	 * or connection-pool exception) is <em>not</em> swallowed here  -  it propagates to the caller (which maps it to
	 * HTTP 503) and is never cached. Only malformed or incomplete stored data degrades to an empty result, and a
	 * confirmed miss is negatively cached for the TTL.</p>
	 *
	 * @param hash key hash
	 * @return the key if present and parsable, otherwise empty
	 */
	public Optional<VirtualApiKey> findByHash(SHA256Hash hash) {
		return cache.get(hash, this::loadFromRedis);
	}

	/**
	 * Validates an owner for key creation or reassignment: present, known, and
	 * active. Unknown or disabled accounts fail fast so no key can ever be born
	 * orphaned. Without a wired repository (unit tests only) only presence is
	 * checked; production Spring always wires it.
	 *
	 * @param ownerUserId owning account id
	 * @throws IllegalArgumentException when the owner is missing, unknown, or disabled
	 */
	private void requireActiveOwner(UUID ownerUserId) {
		if (ownerUserId == null) {
			throw new IllegalArgumentException("owning account is required");
		}
		UserAccountRepository users = this.userAccountRepository;
		if (users == null) {
			return;
		}
		boolean active = users.findById(ownerUserId).map(account -> !account.isDisabled()).orElse(false);
		if (!active) {
			throw new IllegalArgumentException("owning account is unknown or disabled");
		}
	}

	/**
	 * Reports whether an owner id currently resolves to an active account. Used
	 * as a backstop on the request path; immediacy comes from cascade revocation
	 * writing the keys themselves. Short-TTL cached to bound the per-request join
	 * cost; unwired repositories (unit tests only) read as active.
	 *
	 * @param ownerUserId owning account id, possibly {@code null}
	 * @return true when the owner is present and active
	 */
	public boolean isOwnerActive(UUID ownerUserId) {
		if (ownerUserId == null) {
			return false;
		}
		UserAccountRepository users = this.userAccountRepository;
		if (users == null) {
			return true;
		}
		Boolean cached = ownerActiveCache.getIfPresent(ownerUserId);
		if (cached != null) {
			return cached;
		}
		boolean active = users.findById(ownerUserId).map(account -> !account.isDisabled()).orElse(false);
		ownerActiveCache.put(ownerUserId, active);
		return active;
	}

	/**
	 * Central usability verdict for every request-path gate: enabled,
	 * non-revoked, and owned by an active account. A single predicate keeps the
	 * five gates consistent; revoked, unowned, or orphaned keys read exactly like
	 * unknown keys (fail closed, no existence oracle).
	 *
	 * @param key resolved key metadata, possibly {@code null}
	 * @return true only when the key may serve traffic
	 */
	public boolean isUsable(VirtualApiKey key) {
		if (key == null || !key.enabled() || key.revoked()) {
			return false;
		}
		return isOwnerActive(key.ownerUserId());
	}

	/**
	 * Evicts a cached owner-activity verdict, for example after an account is
	 * disabled outside the cascade path.
	 *
	 * @param ownerUserId owning account id, possibly {@code null}
	 */
	public void invalidateOwnerCache(UUID ownerUserId) {
		if (ownerUserId != null) {
			ownerActiveCache.invalidate(ownerUserId);
		}
	}

	/**
	 * Reassigns a key to another active account. Terminal revocation is
	 * unaffected: moving a revoked key keeps the tombstone.
	 *
	 * @param hash      key hash to move
	 * @param ownerUserId new owning account id; must resolve to an active account
	 * @return the updated key metadata, or empty if the key was not found
	 * @throws IllegalArgumentException when the new owner is missing, unknown, or disabled
	 */
	public Optional<VirtualApiKey> assignOwner(SHA256Hash hash, UUID ownerUserId) {
		requireActiveOwner(ownerUserId);
		String key = redisKey(hash);
		if (Boolean.FALSE.equals(redisTemplate.hasKey(key))) {
			return Optional.empty();
		}
		redisTemplate.opsForHash().put(key, "ownerUserId", ownerUserId.toString());
		cache.invalidate(hash);
		return findByHash(hash);
	}

	/**
	 * Lists all keys owned by one account, newest first.
	 *
	 * @param ownerUserId owning account id; {@code null} reads as empty
	 * @return owned keys sorted by creation time descending
	 */
	public List<VirtualApiKey> listKeysByUser(UUID ownerUserId) {
		if (ownerUserId == null) {
			return List.of();
		}
		Set<String> hexes = redisTemplate.opsForSet().members(INDEX_KEY);
		if (hexes == null || hexes.isEmpty()) {
			return List.of();
		}
		List<VirtualApiKey> keys = new ArrayList<>();
		for (String hex : hexes) {
			try {
				SHA256Hash hash = SHA256Hash.fromHex(hex);
				findByHash(hash).ifPresent(key -> {
					if (ownerUserId.equals(key.ownerUserId())) {
						keys.add(key);
					}
				});
			} catch (IllegalArgumentException ignored) {
				// skip invalid hex entry in index
			}
		}
		keys.sort(Comparator.comparing(VirtualApiKey::createdAt).reversed());
		return Collections.unmodifiableList(keys);
	}

	/**
	 * Terminally revokes every key owned by one account: the user-deactivation
	 * cascade. Each key gets the irreversible tombstone; already-revoked keys
	 * are idempotent no-ops that still count.
	 *
	 * @param ownerUserId owning account id; {@code null} revokes nothing
	 * @return how many keys carry the tombstone afterwards
	 */
	public int revokeUserKeys(UUID ownerUserId) {
		if (ownerUserId == null) {
			return 0;
		}
		int revoked = 0;
		for (VirtualApiKey key : listKeysByUser(ownerUserId)) {
			revokeKey(key.keyHash());
			revoked++;
		}
		return revoked;
	}

	/**
	 * Redis key holding one account's default key selection for act-as-self flows.
	 */
	private static String defaultKeyName(UUID ownerUserId) {
		return "userkey:" + ownerUserId + ":default";
	}

	/**
	 * Sets the default key used when an account acts as itself without naming a
	 * key. The key must exist and belong to the account; usability is checked at
	 * request time, not here.
	 *
	 * @param ownerUserId owning account id
	 * @param hash        default key hash, owned by the account
	 * @throws IllegalArgumentException when the key is unknown or owned by someone else
	 */
	public void setDefaultKey(UUID ownerUserId, SHA256Hash hash) {
		Optional<VirtualApiKey> key = findByHash(hash);
		if (key.isEmpty() || !ownerUserId.equals(key.get().ownerUserId())) {
			throw new IllegalArgumentException("default key must be owned by the account");
		}
		redisTemplate.opsForValue().set(defaultKeyName(ownerUserId), hash.hex());
	}

	/**
	 * Reads an account's default key selection.
	 *
	 * @param ownerUserId owning account id, possibly {@code null}
	 * @return the selected key hash, or empty when none is set or parsable
	 */
	public Optional<SHA256Hash> defaultKey(UUID ownerUserId) {
		if (ownerUserId == null) {
			return Optional.empty();
		}
		String hex = redisTemplate.opsForValue().get(defaultKeyName(ownerUserId));
		if (hex == null || hex.isBlank()) {
			return Optional.empty();
		}
		try {
			return Optional.of(SHA256Hash.fromHex(hex.trim()));
		} catch (IllegalArgumentException malformed) {
			return Optional.empty();
		}
	}

	/**
	 * Clears an account's default key selection, for example after the key is
	 * deleted or the account is removed.
	 *
	 * @param ownerUserId owning account id, possibly {@code null}
	 */
	public void clearDefaultKey(UUID ownerUserId) {
		if (ownerUserId == null) {
			return;
		}
		redisTemplate.delete(defaultKeyName(ownerUserId));
	}

	/**
	 * Resolves an act-as-self request to the caller's own key: validates the
	 * session token, resolves the account, then resolves the named (or default)
	 * key and admits it only when owned by the account and currently usable.
	 * Every failure reads as empty so callers answer uniformly, revealing
	 * nothing about which step failed.
	 *
	 * @param sessionJwt  session token from the Authorization header
	 * @param keySelector key hash hex, {@code default}, or blank for the default
	 * @return the caller's usable key, or empty
	 */
	public Optional<VirtualApiKey> resolveActAsSelf(String sessionJwt, String keySelector) {
		JwtService sessions = this.jwtService;
		if (sessions == null || sessionJwt == null || sessionJwt.isBlank()) {
			return Optional.empty();
		}
		final Jwt decoded;
		try {
			decoded = sessions.validate(sessionJwt);
		} catch (RuntimeException invalid) {
			return Optional.empty();
		}
		final UUID userId;
		try {
			userId = UUID.fromString(decoded.getSubject());
		} catch (RuntimeException malformed) {
			return Optional.empty();
		}
		if (!isOwnerActive(userId)) {
			return Optional.empty();
		}
		String selector = keySelector == null ? "" : keySelector.trim();
		String hex;
		if (selector.isEmpty() || "default".equalsIgnoreCase(selector)) {
			Optional<SHA256Hash> def = defaultKey(userId);
			if (def.isEmpty()) {
				return Optional.empty();
			}
			hex = def.get().hex();
		} else {
			hex = selector;
		}
		final SHA256Hash hash;
		try {
			hash = SHA256Hash.fromHex(hex);
		} catch (IllegalArgumentException malformed) {
			return Optional.empty();
		}
		Optional<VirtualApiKey> key = findByHash(hash);
		if (key.isEmpty() || !userId.equals(key.get().ownerUserId())) {
			return Optional.empty();
		}
		return isUsable(key.get()) ? key : Optional.empty();
	}

	/**
	 * Resolves an owning account id to its login name for admin attribution.
	 * Best-effort: unknown accounts and unwired repositories read as
	 * {@code null} rather than failing reads.
	 *
	 * @param ownerUserId owning account id, possibly {@code null}
	 * @return the login name, or {@code null}
	 */
	public String usernameOf(UUID ownerUserId) {
		UserAccountRepository users = this.userAccountRepository;
		if (ownerUserId == null || users == null) {
			return null;
		}
		try {
			return users.findById(ownerUserId).map(UserAccount::getUsername).orElse(null);
		} catch (RuntimeException ex) {
			log.debug("Dropping owner username lookup: {}", ex.getMessage());
			return null;
		}
	}

	/**
	 * Seeds configured bootstrap keys so they exist at runtime. Idempotent: entries with a null or blank
	 * {@code plaintextKey} are skipped, and a key whose hash is already present is never overwritten. Never logs
	 * plaintexts.
	 *
	 * <p>Race-safe across any number of concurrently booting instances: each key is claimed by the atomic
	 * {@code SEED_IF_ABSENT_SCRIPT}, so simultaneous boots converge instead of overwriting each other's fields.</p>
	 *
	 * <p>Fail-closed: a Redis failure propagates to the caller; {@link BootstrapKeySeeder}
	 * catches it and defers seeding to its scheduled retry.</p>
	 *
	 * @param properties gateway configuration providing the bootstrap keys
	 */
	public void seedBootstrapKeys(GatewayProperties properties) {
		for (BootstrapKey bootstrapKey : properties.getBootstrapKeys()) {
			if (bootstrapKey.plaintextKey() == null || bootstrapKey.plaintextKey().isBlank()) {
				continue;
			}
			UUID seedOwner = resolveSeedOwner(bootstrapKey.ownerUsername(), bootstrapKey.name());
			if (seedOwner == null && userAccountRepository != null) {
				continue;
			}
			trySeedKey(
					bootstrapKey.plaintextKey(),
					bootstrapKey.ownerId(),
					bootstrapKey.name(),
					bootstrapKey.rpmLimit(),
					bootstrapKey.tpmLimit(),
					bootstrapKey.allowedModels(),
					bootstrapKey.allowedProviders(),
					bootstrapKey.allowedTools(),
					bootstrapKey.deniedTools(),
					bootstrapKey.allowedResources(),
					bootstrapKey.deniedResources(),
					bootstrapKey.allowedPrompts(),
					bootstrapKey.deniedPrompts(),
					SEED_INJECTION_BLOCK,
					bootstrapKey.allowedCacheScopes(),
					Set.of(),
					Set.of(),
					seedOwner
			);
		}
	}

	/**
	 * Resolves a bootstrap owner username to an account id. Unresolvable owners
	 * skip the seed with an error log instead of birthing orphaned keys; the
	 * seeder retry picks the key up once the account exists. Without a wired
	 * repository (unit tests only) resolution reads as unknown.
	 *
	 * @param username configured owner username, possibly {@code null}
	 * @param keyName  key label for log context
	 * @return the account id, or {@code null} when unknown
	 */
	private UUID resolveSeedOwner(String username, String keyName) {
		UserAccountRepository users = this.userAccountRepository;
		if (username == null || username.isBlank()) {
			if (users != null) {
				log.error("Skipping bootstrap key '{}': owner-username is required", keyName);
			}
			return null;
		}
		if (users == null) {
			return null;
		}
		Optional<UserAccount> account = users.findByUsernameIgnoreCase(username.trim());
		if (account.isEmpty() || account.get().isDisabled()) {
			log.error("Skipping bootstrap key '{}': owning account '{}' is unknown or disabled",
					keyName, username);
			return null;
		}
		return account.get().getId();
	}

	/**
	 * Attempts to claim one bootstrap-key slot atomically, storing metadata plus index entry only on success.
	 *
	 * @param ownerUserId owning account id, possibly {@code null} for legacy seeds
	 * @return {@code true} when this caller won the claim (losers change nothing)
	 */
	private boolean trySeedKey(
			String plaintextKey,
			String ownerId,
			String name,
			int rpmLimit,
			int tpmLimit,
			Set<String> allowedModels,
			Set<String> allowedProviders,
			Set<String> allowedTools,
			Set<String> deniedTools,
			Set<String> allowedResources,
			Set<String> deniedResources,
			Set<String> allowedPrompts,
			Set<String> deniedPrompts,
			boolean injectionBlock,
			Set<CacheScope> allowedCacheScopes,
			Set<String> allowedAgents,
			Set<String> deniedAgents,
			UUID ownerUserId
	) {
		SHA256Hash hash = SHA256Hash.fromRawKey(plaintextKey);
		Map<String, String> fields = new LinkedHashMap<>();
		fields.put("ownerId", ownerId);
		fields.put("name", name);
		fields.put("rpmLimit", Integer.toString(rpmLimit));
		fields.put("tpmLimit", Integer.toString(tpmLimit));
		fields.put("enabled", "true");
		putPolicyFields(fields, allowedModels, allowedProviders, allowedTools, deniedTools,
				allowedResources, deniedResources, allowedPrompts, deniedPrompts,
				injectionBlock, allowedCacheScopes, allowedAgents, deniedAgents, ownerUserId, false);
		fields.put("createdAt", Instant.now().toString());
		fields.put("keyPrefix", prefixOf(plaintextKey));
		List<Object> args = new ArrayList<>();
		for (Map.Entry<String, String> field : fields.entrySet()) {
			args.add(field.getKey());
			args.add(field.getValue());
		}
		args.add(hash.hex());
		Long claimed = redisTemplate.execute(
				SEED_IF_ABSENT_SCRIPT, List.of(redisKey(hash), INDEX_KEY), args.toArray());
		if (Long.valueOf(1L).equals(claimed)) {
			cache.invalidate(hash);
			return true;
		}
		return false;
	}

	private SHA256Hash storeKey(
			String plaintextKey,
			String ownerId,
			String name,
			int rpmLimit,
			int tpmLimit,
			Set<String> allowedModels,
			Set<String> allowedProviders,
			Set<String> allowedTools,
			Set<String> deniedTools,
			Set<String> allowedResources,
			Set<String> deniedResources,
			Set<String> allowedPrompts,
			Set<String> deniedPrompts,
			boolean injectionBlock,
			Set<CacheScope> allowedCacheScopes,
			Set<String> allowedAgents,
			Set<String> deniedAgents,
			UUID ownerUserId
	) {
		SHA256Hash hash = SHA256Hash.fromRawKey(plaintextKey);
		Map<String, String> fields = new LinkedHashMap<>();
		fields.put("ownerId", ownerId);
		fields.put("name", name);
		fields.put("rpmLimit", Integer.toString(rpmLimit));
		fields.put("tpmLimit", Integer.toString(tpmLimit));
		fields.put("enabled", "true");
		putPolicyFields(fields, allowedModels, allowedProviders, allowedTools, deniedTools,
				allowedResources, deniedResources, allowedPrompts, deniedPrompts,
				injectionBlock, allowedCacheScopes, allowedAgents, deniedAgents, ownerUserId, false);
		fields.put("createdAt", Instant.now().toString());
		fields.put("keyPrefix", prefixOf(plaintextKey));
		redisTemplate.opsForHash().putAll(redisKey(hash), fields);
		redisTemplate.opsForSet().add(INDEX_KEY, hash.hex());
		cache.invalidate(hash);
		return hash;
	}

	private Optional<VirtualApiKey> loadFromRedis(SHA256Hash hash) {
		String key = redisKey(hash);
		if (Boolean.FALSE.equals(redisTemplate.hasKey(key))) {
			return Optional.empty();
		}
		Map<Object, Object> raw = redisTemplate.opsForHash().entries(key);
		if (raw.isEmpty()) {
			return Optional.empty();
		}
		// Redis access stays outside the try: connectivity failures must propagate
		// (fail closed). Only malformed or incomplete stored data degrades to a miss.
		try {
			String ownerId = (String) raw.get("ownerId");
			String name = (String) raw.get("name");
			int rpmLimit = Integer.parseInt((String) raw.get("rpmLimit"));
			int tpmLimit = Integer.parseInt((String) raw.get("tpmLimit"));
			boolean enabled = Boolean.parseBoolean((String) raw.get("enabled"));
			Set<String> allowedModels = parseCsv((String) raw.get("allowedModels"));
			Set<String> allowedProviders = parseCsv((String) raw.get("allowedProviders"));
			Set<String> allowedTools = parseCsv((String) raw.get("allowedTools"));
			Set<String> deniedTools = parseCsv((String) raw.get("deniedTools"));
			Set<String> allowedResources = parseSetField((String) raw.get("allowedResources"));
			Set<String> deniedResources = parseSetField((String) raw.get("deniedResources"));
			Set<String> allowedPrompts = parseSetField((String) raw.get("allowedPrompts"));
			Set<String> deniedPrompts = parseSetField((String) raw.get("deniedPrompts"));
			boolean injectionBlock = !"false".equalsIgnoreCase((String) raw.get("injectionBlock"));
			Set<CacheScope> allowedCacheScopes = parseScopes((String) raw.get("allowedCacheScopes"));
			Set<String> allowedAgents = parseCsv((String) raw.get("allowedAgents"));
			Set<String> deniedAgents = parseCsv((String) raw.get("deniedAgents"));
			UUID ownerUserId = parseOwnerUserId((String) raw.get("ownerUserId"));
			boolean revoked = parseRevoked((String) raw.get("revoked"));
			Instant createdAt = Instant.parse((String) raw.get("createdAt"));
			String keyPrefix = (String) raw.getOrDefault("keyPrefix", KEY_PREFIX_RAW);
			return Optional.of(new VirtualApiKey(
					hash,
					keyPrefix,
					ownerId,
					name,
					rpmLimit,
					tpmLimit,
					allowedModels,
					allowedProviders,
					allowedTools,
					deniedTools,
					allowedResources,
					deniedResources,
					allowedPrompts,
					deniedPrompts,
					injectionBlock,
					enabled,
					createdAt,
					allowedCacheScopes,
					allowedAgents,
					deniedAgents,
					ownerUserId,
					revoked
			));
		} catch (RuntimeException ignored) {
			// Malformed or incomplete stored metadata: treat as absent, never throw.
			return Optional.empty();
		}
	}
}