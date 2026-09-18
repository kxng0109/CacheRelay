package io.github.kxng0109.cacherelay.auth;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.Base64;
import java.util.HexFormat;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Opaque refresh-token lifecycle: mint, rotate, revoke. Tokens are 256-bit random values
 * presented by the client and stored as SHA-256 hashes; rotation replaces the presented
 * row with a successor in the same family. Presenting an already-rotated token revokes
 * the entire family (reuse detection). Per-token single-flight serialization prevents
 * concurrent legitimate refreshes from triggering false-positive revocation.
 */
@Service
public class RefreshService {

	/** Rotation succeeded; use the successor token. */
	public record RotationSuccess(String token, UUID userId, boolean admin) implements RotationResult {
	}

	/** A rotated token was replayed; the family was revoked. */
	public record RotationReuse() implements RotationResult {
	}

	/** The token is unknown, expired, revoked, or lost a rotation race. */
	public record RotationInvalid() implements RotationResult {
	}

	/** Outcome of a refresh attempt. */
	public sealed interface RotationResult permits RotationSuccess, RotationReuse, RotationInvalid {
	}

	private final RefreshTokenRepository repository;
	private final UserAccountRepository users;
	private final AuthProperties properties;
	private final SecureRandom random = new SecureRandom();
	private final ConcurrentHashMap<String, Object> inflight = new ConcurrentHashMap<>();

	/**
	 * Creates the service.
	 *
	 * @param repository refresh persistence
	 * @param users      account lookup for privilege stamping
	 * @param properties auth tuning surface
	 */
	public RefreshService(RefreshTokenRepository repository, UserAccountRepository users,
			AuthProperties properties) {
		this.repository = repository;
		this.users = users;
		this.properties = properties;
	}

	/**
	 * Mints a fresh refresh family for an account.
	 *
	 * @param userId owning account
	 * @param admin  whether strict admin lifetimes apply
	 * @return presented opaque token (single exposure)
	 */
	@Transactional
	public String mint(UUID userId, boolean admin) {
		String token = randomToken();
		Instant now = Instant.now();
		repository.save(new RefreshToken(
				UUID.randomUUID(),
				sha256Hex(token),
				userId,
				now.plus(idleTtl(admin)),
				now.plus(absoluteTtl(admin))));
		return token;
	}

	/**
	 * Rotates a presented token: exactly one concurrent caller wins; losers and replays
	 * resolve to invalid or reuse. Success extends the idle expiry; the absolute ceiling
	 * never moves.
	 *
	 * @param presented opaque token from the client
	 * @return rotation outcome
	 */
	@Transactional
	public RotationResult rotate(String presented) {
		String hash = sha256Hex(presented);
		Object lock = inflight.computeIfAbsent(hash, key -> new Object());
		synchronized (lock) {
			try {
				return rotateOnce(hash);
			} finally {
				inflight.remove(hash, lock);
			}
		}
	}

	private RotationResult rotateOnce(String hash) {
		Optional<RefreshToken> found = repository.findByTokenHash(hash);
		if (found.isEmpty()) {
			return new RotationInvalid();
		}
		RefreshToken current = found.get();
		Instant now = Instant.now();
		if (current.getRevokedAt() != null
				|| now.isAfter(current.getExpiresAt())
				|| now.isAfter(current.getAbsoluteExpiresAt())) {
			return new RotationInvalid();
		}
		if (current.getReplacedBy() != null) {
			repository.revokeFamily(current.getFamilyId(), now);
			return new RotationReuse();
		}
		Optional<UserAccount> account = users.findById(current.getUserId());
		if (account.isEmpty() || account.get().isDisabled()) {
			repository.revokeFamily(current.getFamilyId(), now);
			return new RotationInvalid();
		}
		boolean admin = account.get().isAdmin();
		String successorPlain = randomToken();
		RefreshToken successor = new RefreshToken(
				current.getFamilyId(),
				sha256Hex(successorPlain),
				current.getUserId(),
				now.plus(idleTtl(admin)),
				current.getAbsoluteExpiresAt());
		int won = repository.markReplaced(current.getId(), successor.getId());
		if (won == 0) {
			return new RotationInvalid();
		}
		repository.save(successor);
		return new RotationSuccess(successorPlain, current.getUserId(), admin);
	}

	/**
	 * Revokes every family of an account (logout everywhere / disable).
	 *
	 * @param userId owning account
	 */
	@Transactional
	public void revokeAll(UUID userId) {
		Instant now = Instant.now();
		repository.findAll().stream()
				.filter(row -> row.getUserId().equals(userId) && row.getRevokedAt() == null)
				.map(RefreshToken::getFamilyId)
				.distinct()
				.forEach(family -> repository.revokeFamily(family, now));
	}

	private java.time.Duration idleTtl(boolean admin) {
		return admin ? properties.adminRefreshTtl() : properties.refreshTtl();
	}

	private java.time.Duration absoluteTtl(boolean admin) {
		return admin ? properties.adminAbsoluteMaxTtl() : properties.absoluteMaxTtl();
	}

	private String randomToken() {
		byte[] bytes = new byte[32];
		random.nextBytes(bytes);
		return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
	}

	static String sha256Hex(String value) {
		try {
			MessageDigest digest = MessageDigest.getInstance("SHA-256");
			return HexFormat.of().formatHex(digest.digest(value.getBytes(StandardCharsets.UTF_8)));
		} catch (NoSuchAlgorithmException e) {
			throw new IllegalStateException("SHA-256 unavailable", e);
		}
	}
}
