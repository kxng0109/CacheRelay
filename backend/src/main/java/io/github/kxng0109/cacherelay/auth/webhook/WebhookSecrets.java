package io.github.kxng0109.cacherelay.auth.webhook;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

/**
 * Constant-time secret comparison for webhook receivers.
 *
 * <p>Both sides are digested with SHA-256 before comparison, so the
 * {@link MessageDigest#isEqual} call always runs over fixed 32-byte arrays:
 * neither content nor length leaks through timing. A blank expected secret
 * (unconfigured receiver) never matches; a {@code null} presented value never
 * matches.</p>
 */
public final class WebhookSecrets {

	private WebhookSecrets() {
	}

	/**
	 * Compares a presented webhook secret against the configured value.
	 *
	 * @param expected configured secret, possibly blank when unconfigured
	 * @param presented presented secret, possibly {@code null}
	 * @return {@code true} only on an exact match with a non-blank expected value
	 */
	public static boolean constantTimeEquals(String expected, String presented) {
		if (expected == null || expected.isBlank() || presented == null) {
			return false;
		}
		return MessageDigest.isEqual(sha256(expected), sha256(presented));
	}

	private static byte[] sha256(String value) {
		try {
			return MessageDigest.getInstance("SHA-256")
					.digest(value.getBytes(StandardCharsets.UTF_8));
		} catch (NoSuchAlgorithmException missing) {
			throw new IllegalStateException("SHA-256 unavailable", missing);
		}
	}
}
