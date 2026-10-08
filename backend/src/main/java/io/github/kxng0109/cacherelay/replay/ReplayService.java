package io.github.kxng0109.cacherelay.replay;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Base64;
import java.util.Map;
import java.util.UUID;

import io.github.kxng0109.cacherelay.proxy.IdempotencyKeys;
import io.github.kxng0109.cacherelay.security.ratelimit.TenantIds;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

/**
 * Exact replay for idempotent retries, distinct from the semantic cache (similar-prompt reuse).
 *
 * <p>Two tiers: Redis hot (24h TTL, evictable — a hot miss re-proxies and is charged as new spend, the
 * safe over-count direction) and PostgreSQL durable (forensics and re-warm, never on the latency path).
 * A first request claims the fill lock for the whole upstream flight; concurrent duplicates see
 * {@link InFlight} (HTTP 409); the same key with a different body is {@link FingerprintMismatch} (HTTP 422).
 * Stored payloads above {@link #MAX_BODY_BYTES} are skipped, never truncated.</p>
 */
@Service
public class ReplayService {

	/**
	 * Redis key prefix for stored idempotent replays (also purged by the admin cache purge).
	 */
	public static final String PREFIX = "cacherelay:replay:";

	/**
	 * Redis key prefix for in-flight replay fill locks (also purged by the admin cache purge).
	 */
	public static final String FILL_PREFIX = "cacherelay:replay-fill:";

	static final Duration HOT_TTL = Duration.ofHours(24);

	static final Duration FILL_TTL = Duration.ofMinutes(5);

	static final int MAX_BODY_BYTES = 1_048_576;

	/** Lookup result for one idempotency key + body fingerprint. */
	public sealed interface Lookup permits Hit, Miss, FingerprintMismatch, InFlight {
	}

	/** Stored payload exists and the fingerprint matches: serve it with {@code Idempotent-Replayed}. */
	public record Hit(byte[] body, boolean sseFramed, Instant expiresAt) implements Lookup {
	}

	/** No entry and no in-flight fill: the caller proceeds upstream. */
	public record Miss() implements Lookup {
	}

	/** Same key, different body: the client is reusing a key for another request. */
	public record FingerprintMismatch() implements Lookup {
	}

	/** Another pod/thread is serving the first flight: the caller must not start a second. */
	public record InFlight() implements Lookup {
	}

	private static final Logger log = LoggerFactory.getLogger(ReplayService.class);

	private final StringRedisTemplate cacheTemplate;

	private final ReplayRepository repository;

	private final MeterRegistry meterRegistry;

	public ReplayService(@Qualifier("cacheRedisTemplate") StringRedisTemplate cacheTemplate,
	                     ReplayRepository repository,
	                     @Nullable MeterRegistry meterRegistry) {
		this.cacheTemplate = cacheTemplate;
		this.repository = repository;
		this.meterRegistry = meterRegistry != null ? meterRegistry : new SimpleMeterRegistry();
	}

	/**
	 * Checks the hot tier for a previous completion of this idempotency key.
	 *
	 * @param idempotencyKey validated client key (bounded length/charset by the caller)
	 * @param bodyHashHex    sha256 of the current request body
	 * @param tenant         owning tenant for key namespacing (unscoped callers pass {@code null},
	 *                       which maps to the reserved {@code unknown} bucket, never a real tenant)
	 * @param keyHashHex     calling key digest hex for key namespacing, possibly {@code null}
	 * @return hit, miss, mismatch, or in-flight
	 */
	public Lookup lookup(String idempotencyKey, String bodyHashHex,
	                     @Nullable String tenant, @Nullable String keyHashHex) {
		String namespacedKey = scopedKey(PREFIX, tenant, keyHashHex, idempotencyKey);
		Map<Object, Object> fields;
		try {
			fields = cacheTemplate.opsForHash().entries(namespacedKey);
		} catch (RuntimeException ex) {
			// Cache tier down: degrade to re-proxy (budget dedupe still prevents double-charge).
			return new Miss();
		}
		if (fields == null || fields.isEmpty()) {
			Boolean filling = cacheTemplate.hasKey(fillKey(tenant, keyHashHex, idempotencyKey));
			return Boolean.TRUE.equals(filling) ? new InFlight() : new Miss();
		}
		if (!bodyHashHex.equals(stringField(fields, "body_hash"))) {
			count("mismatch");
			return new FingerprintMismatch();
		}
		String payload = stringField(fields, "body");
		if (payload.isEmpty()) {
			return new Miss();
		}
		// FIN-B30: the hot tier stores Base64, never a String round-trip (non-UTF8
		// bytes would corrupt). Decode, then verify the SHA-256 against the stored
		// fingerprint before serving: legacy plain-text rows and tampered rows fail
		// closed to a miss (re-proxy, safe over-count) instead of wrong bytes.
		final byte[] decoded;
		try {
			decoded = Base64.getDecoder().decode(payload);
		} catch (IllegalArgumentException corrupt) {
			count("corrupt");
			return new Miss();
		}
		if (!IdempotencyKeys.sha256Hex(decoded).equals(stringField(fields, "body_hash"))) {
			count("corrupt");
			return new Miss();
		}
		count("hit");
		return new Hit(decoded,
				"1".equals(stringField(fields, "sse")),
				resolveExpiresAt(namespacedKey));
	}

	/**
	 * Composes the tenant- and key-scoped Redis key. Segments that are missing map to the
	 * reserved {@code unknown} bucket, which can never collide with a validated tenant.
	 *
	 * @param prefix       key prefix (hot tier or fill locks)
	 * @param tenant       owning tenant, possibly {@code null}
	 * @param keyHashHex   calling key digest hex, possibly {@code null}
	 * @param idempotencyKey validated client key
	 * @return namespaced key
	 */
	private static String scopedKey(String prefix, @Nullable String tenant, @Nullable String keyHashHex,
	                                String idempotencyKey) {
		return prefix + scope(tenant, keyHashHex, idempotencyKey);
	}

	private static String scope(@Nullable String tenant, @Nullable String keyHashHex, String idempotencyKey) {
		String tenantSegment = TenantIds.orUnknown(tenant);
		String hashSegment = keyHashHex == null || keyHashHex.isBlank() ? "unknown" : keyHashHex;
		return tenantSegment + ":" + hashSegment + ":" + idempotencyKey;
	}

	private static String fillKey(@Nullable String tenant, @Nullable String keyHashHex, String idempotencyKey) {
		return scopedKey(FILL_PREFIX, tenant, keyHashHex, idempotencyKey);
	}

	/**
	 * Resolves the hit expiry from the live hot-tier TTL, falling back to the configured horizon only when
	 * Redis reports no usable TTL (eviction races, persistence without expiry).
	 *
	 * @param namespacedKey full hot-tier key
	 * @return instant the hot-tier entry expires
	 */
	private Instant resolveExpiresAt(String namespacedKey) {
		try {
			Long ttlSeconds = cacheTemplate.getExpire(namespacedKey);
			if (ttlSeconds != null && ttlSeconds > 0) {
				return Instant.now().plusSeconds(ttlSeconds);
			}
		} catch (RuntimeException ex) {
			log.debug("Replay TTL probe failed; falling back to the configured horizon");
		}
		return Instant.now().plus(HOT_TTL);
	}

	/**
	 * Claims the fill for one idempotency key before starting upstream.
	 *
	 * @param idempotencyKey validated client key
	 * @param tenant         owning tenant for lock namespacing, possibly {@code null}
	 * @param keyHashHex     calling key digest hex for lock namespacing, possibly {@code null}
	 * @return {@code true} when this caller owns the flight, {@code false} on a concurrent duplicate
	 */
	public boolean beginFill(String idempotencyKey, @Nullable String tenant, @Nullable String keyHashHex) {
		try {
			Boolean won = cacheTemplate.opsForValue()
			                           .setIfAbsent(fillKey(tenant, keyHashHex, idempotencyKey), "1", FILL_TTL);
			return Boolean.TRUE.equals(won);
		} catch (RuntimeException ex) {
			// Cache tier down: allow the flight (no replay coordination, still correct).
			return true;
		}
	}

	/**
	 * Releases a fill claim without storing (abort, upstream failure, over-cap payload).
	 *
	 * @param idempotencyKey validated client key
	 * @param tenant         owning tenant for lock namespacing, possibly {@code null}
	 * @param keyHashHex     calling key digest hex for lock namespacing, possibly {@code null}
	 */
	public void releaseFill(String idempotencyKey, @Nullable String tenant, @Nullable String keyHashHex) {
		try {
			cacheTemplate.delete(fillKey(tenant, keyHashHex, idempotencyKey));
		} catch (RuntimeException ex) {
			log.debug("Replay fill release failed for key; TTL bounds the stale claim");
		}
	}

	/**
	 * Stores a completed payload in both tiers and releases the fill claim. First completer wins; oversized
	 * payloads are skipped (never truncated).
	 *
	 * @param idempotencyKey validated client key
	 * @param bodyHashHex    sha256 of the request body (fingerprint for future retries)
	 * @param body           exact bytes to re-deliver
	 * @param sseFramed      whether the payload needs SSE re-framing on serve
	 * @param tenant         owning tenant for entry namespacing, possibly {@code null}
	 * @param keyHashHex     calling key digest hex for entry namespacing, possibly {@code null}
	 * @return {@code true} when at least one tier persisted, {@code false} when neither did (a retry will
	 *         re-proxy and be charged as new spend). Never throws: persistence failure must not fail the
	 *         already-completed response.
	 */
	public boolean store(String idempotencyKey, String bodyHashHex, byte[] body, boolean sseFramed,
	                     @Nullable String tenant, @Nullable String keyHashHex) {
		if (body.length > MAX_BODY_BYTES) {
			count("oversized");
			releaseFill(idempotencyKey, tenant, keyHashHex);
			return false;
		}
		String namespacedKey = scopedKey(PREFIX, tenant, keyHashHex, idempotencyKey);
		Instant expiresAt = Instant.now().plus(HOT_TTL).truncatedTo(ChronoUnit.MILLIS);
		boolean hotOk = false;
		try {
			// FIN-B30: Base64 preserves arbitrary bytes exactly; a String
			// round-trip would corrupt non-UTF8 payloads while the durable tier
			// keeps the raw bytes, leaving the tiers in permanent disagreement.
			cacheTemplate.opsForHash().putAll(namespacedKey, Map.of(
					"body", Base64.getEncoder().encodeToString(body),
					"body_hash", bodyHashHex,
					"sse", sseFramed ? "1" : "0"));
			cacheTemplate.expire(namespacedKey, HOT_TTL);
			hotOk = true;
		} catch (RuntimeException ex) {
			log.debug("Replay hot-tier store failed; falling back to durable tier only");
		}
		boolean durableOk = false;
		try {
			UUID id = UUID.nameUUIDFromBytes(
					("replay:" + scope(tenant, keyHashHex, idempotencyKey)).getBytes(StandardCharsets.UTF_8));
			repository.save(new ReplayRecord(new ReplayId(id, expiresAt), body, bodyHashHex));
			durableOk = true;
		} catch (RuntimeException ex) {
			log.warn("Replay durable-tier store failed for key");
		} finally {
			releaseFill(idempotencyKey, tenant, keyHashHex);
		}
		if (hotOk || durableOk) {
			count("stored");
			return true;
		}
		count("store_failed");
		return false;
	}

	private static String stringField(Map<Object, Object> fields, String name) {
		Object value = fields.get(name);
		return value == null ? "" : value.toString();
	}

	private void count(String outcome) {
		try {
			Counter.builder("cacherelay.replay.lookups")
			       .tag("outcome", outcome)
			       .register(meterRegistry)
			       .increment();
		} catch (Exception ignored) {
		}
	}
}
