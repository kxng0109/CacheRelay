package io.github.kxng0109.cacherelay.mcp.protocol;

import java.time.Duration;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import org.springframework.web.servlet.mvc.method.annotation.ResponseBodyEmitter;

/**
 * Bounded, keyed registry for legacy MCP SSE sessions (2024-11-05 transport).
 *
 * <p>Every {@code GET /v1/mcp/sse} stream is bound to the API key that opened it, counted against a
 * per-key cap (emitters and sender threads are otherwise unbounded per caller), and expired after
 * the emitter TTL. Message POSTs address sessions by id; unknown, foreign, or expired ids are
 * indistinguishable (all miss) so session existence is never oracled. All mutating paths are safe
 * for concurrent servlet threads; creation is synchronized because opens are rare.
 */
public final class LegacySseSessionRegistry {

	/**
	 * Maximum concurrent legacy SSE streams per API key.
	 */
	public static final int MAX_SESSIONS_PER_KEY = 8;

	/**
	 * One registered legacy stream.
	 *
	 * @param id              session id handed to the client
	 * @param keyId           owning key identity (key hash hex)
	 * @param emitter         live SSE emitter for server-initiated delivery
	 * @param expiresAtMillis wall-clock expiry (emitter TTL)
	 */
	public record Session(String id, String keyId, ResponseBodyEmitter emitter, long expiresAtMillis) {
	}

	private final ConcurrentHashMap<String, Session> sessions = new ConcurrentHashMap<>();
	private final long ttlMillis;

	/**
	 * Creates the registry.
	 *
	 * @param ttl session time to live (emitter timeout); non-positive values fall back to 30 minutes
	 */
	public LegacySseSessionRegistry(Duration ttl) {
		long millis = ttl == null ? 0L : ttl.toMillis();
		this.ttlMillis = millis > 0L ? millis : 1_800_000L;
	}

	/**
	 * Registers a new session for the key unless the per-key cap is reached.
	 *
	 * @param keyId   owning key identity
	 * @param emitter live SSE emitter
	 * @return the session, or empty when the key already holds the maximum
	 */
	public synchronized Optional<Session> create(String keyId, ResponseBodyEmitter emitter) {
		evictExpired();
		if (liveForKey(keyId) >= MAX_SESSIONS_PER_KEY) {
			return Optional.empty();
		}
		Session session = new Session(
				UUID.randomUUID().toString().replace("-", ""),
				keyId,
				emitter,
				System.currentTimeMillis() + ttlMillis
		);
		sessions.put(session.id(), session);
		return Optional.of(session);
	}

	/**
	 * Resolves a session for delivery, evicting it when expired.
	 *
	 * @param sessionId session id from the client
	 * @param keyId     calling key identity (must own the session)
	 * @return the live session, or empty when unknown, foreign, or expired
	 */
	public Optional<Session> find(String sessionId, String keyId) {
		if (sessionId == null || keyId == null) {
			return Optional.empty();
		}
		Session session = sessions.get(sessionId);
		if (session == null
				|| !session.keyId().equals(keyId)
				|| session.expiresAtMillis() < System.currentTimeMillis()) {
			if (session != null && session.expiresAtMillis() < System.currentTimeMillis()) {
				sessions.remove(sessionId, session);
			}
			return Optional.empty();
		}
		return Optional.of(session);
	}

	/**
	 * Drops a session (emitter completed, timed out, or delivery failed).
	 *
	 * @param sessionId session id, ignored when unknown
	 */
	public void remove(String sessionId) {
		if (sessionId != null) {
			sessions.remove(sessionId);
		}
	}

	/**
	 * Counts live sessions for the key.
	 *
	 * @param keyId owning key identity
	 * @return live session count
	 */
	public int countForKey(String keyId) {
		return liveForKey(keyId);
	}

	private int liveForKey(String keyId) {
		long now = System.currentTimeMillis();
		int count = 0;
		for (Session session : sessions.values()) {
			if (session.keyId().equals(keyId) && session.expiresAtMillis() >= now) {
				count++;
			}
		}
		return count;
	}

	private void evictExpired() {
		long now = System.currentTimeMillis();
		sessions.values().removeIf(session -> session.expiresAtMillis() < now);
	}
}
