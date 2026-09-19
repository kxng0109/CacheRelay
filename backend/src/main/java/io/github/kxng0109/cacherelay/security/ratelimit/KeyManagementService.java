package io.github.kxng0109.cacherelay.security.ratelimit;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
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
import org.springframework.stereotype.Service;

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
			Set<CacheScope> allowedCacheScopes
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
				template.allowedCacheScopes()
		);
		return plaintext;
	}

	/**
	 * Creates a new virtual API key with the given parameters, persists it in Redis, adds it to the admin index, and
	 * returns the single-exposure plaintext along with metadata.
	 *
	 * @param ownerId          owner identifier
	 * @param name             label for the key
	 * @param rpmLimit         requests per minute limit (0 = unlimited)
	 * @param tpmLimit         tokens per minute limit (0 = unlimited)
	 * @param allowedModels    allowed model names (empty = all)
	 * @param allowedProviders allowed provider names (empty = all)
	 * @return the created key object containing the plaintext and metadata
	 */
	public CreatedKey createKey(
			String ownerId,
			String name,
			int rpmLimit,
			int tpmLimit,
			Set<String> allowedModels,
			Set<String> allowedProviders
	) {
		return createKey(ownerId, name, rpmLimit, tpmLimit, allowedModels, allowedProviders, Set.of(), Set.of());
	}

	/**
	 * Creates a new virtual API key with model, provider, and tool-level RBAC/ABAC rules.
	 *
	 * @param ownerId          owner identifier
	 * @param name             label for the key
	 * @param rpmLimit         requests per minute limit (0 = unlimited)
	 * @param tpmLimit         tokens per minute limit (0 = unlimited)
	 * @param allowedModels    allowed model names (empty = all)
	 * @param allowedProviders allowed provider names (empty = all)
	 * @param allowedTools     allowed tool names or glob patterns (empty = all)
	 * @param deniedTools      denied tool names or glob patterns (empty = none)
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
			Set<String> deniedTools
	) {
		return createKey(ownerId, name, rpmLimit, tpmLimit, allowedModels, allowedProviders,
				allowedTools, deniedTools, Set.of(), Set.of(), Set.of(), Set.of());
	}

	/**
	 * Creates a new virtual API key with full governance rules including resource and
	 * prompt visibility.
	 *
	 * @param ownerId          owner identifier
	 * @param name             label for the key
	 * @param rpmLimit         requests per minute limit (0 = unlimited)
	 * @param tpmLimit         tokens per minute limit (0 = unlimited)
	 * @param allowedModels    allowed model names (empty = all)
	 * @param allowedProviders allowed provider names (empty = all)
	 * @param allowedTools     allowed tool names or glob patterns (empty = all)
	 * @param deniedTools      denied tool names or glob patterns (empty = none)
	 * @param allowedResources allowed resource URI globs (empty = all visible)
	 * @param deniedResources  denied resource URI globs (empty = none hidden)
	 * @param allowedPrompts   allowed prompt name globs (empty = all visible)
	 * @param deniedPrompts    denied prompt name globs (empty = none hidden)
	 * @param injectionBlock   whether indirect prompt injection blocks delivery (null = keep default block)
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
			Set<String> deniedPrompts
	) {
		return createKey(ownerId, name, rpmLimit, tpmLimit, allowedModels, allowedProviders,
				allowedTools, deniedTools, allowedResources, deniedResources, allowedPrompts,
				deniedPrompts, null);
	}

	/**
	 * Creates a new virtual API key with full governance rules including resource,
	 * prompt, and injection handling.
	 *
	 * @param ownerId          owner identifier
	 * @param name             label for the key
	 * @param rpmLimit         requests per minute limit (0 = unlimited)
	 * @param tpmLimit         tokens per minute limit (0 = unlimited)
	 * @param allowedModels    allowed model names (empty = all)
	 * @param allowedProviders allowed provider names (empty = all)
	 * @param allowedTools     allowed tool names or glob patterns (empty = all)
	 * @param deniedTools      denied tool names or glob patterns (empty = none)
	 * @param allowedResources allowed resource URI globs (empty = all visible)
	 * @param deniedResources  denied resource URI globs (empty = none hidden)
	 * @param allowedPrompts   allowed prompt name globs (empty = all visible)
	 * @param deniedPrompts    denied prompt name globs (empty = none hidden)
	 * @param injectionBlock   whether indirect prompt injection blocks delivery (null = default block)
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
			Boolean injectionBlock
	) {
		return createKey(ownerId, name, rpmLimit, tpmLimit, allowedModels, allowedProviders,
				allowedTools, deniedTools, allowedResources, deniedResources, allowedPrompts,
				deniedPrompts, injectionBlock, null);
	}

	/**
	 * Creates a new virtual API key with full governance rules including resource,
	 * prompt, injection, and cache-scope handling.
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
			Set<CacheScope> allowedCacheScopes
	) {
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
				allowedCacheScopes
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
				allowedCacheScopes
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
				deniedPrompts, injectionBlock, null, enabled);
	}

	/**
	 * Updates an existing key's metadata including full governance rules and cache
	 * scopes, invalidating the local cache.
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
			Boolean enabled
	) {
		String key = redisKey(hash);
		if (Boolean.FALSE.equals(redisTemplate.hasKey(key))) {
			return Optional.empty();
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
	 * Disables a key by flipping its {@code enabled} flag in Redis and evicting the local cache entry so the next
	 * lookup observes the revocation.
	 *
	 * @param hash key hash to revoke
	 */
	public void revokeKey(SHA256Hash hash) {
		redisTemplate.opsForHash().put(redisKey(hash), "enabled", "false");
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
					bootstrapKey.allowedCacheScopes()
			);
		}
	}

	/**
	 * Attempts to claim one bootstrap-key slot atomically, storing metadata plus index entry only on success.
	 *
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
			Set<CacheScope> allowedCacheScopes
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
				injectionBlock, allowedCacheScopes);
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
			Set<CacheScope> allowedCacheScopes
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
				injectionBlock, allowedCacheScopes);
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
					allowedCacheScopes
			));
		} catch (RuntimeException ignored) {
			// Malformed or incomplete stored metadata: treat as absent, never throw.
			return Optional.empty();
		}
	}
}