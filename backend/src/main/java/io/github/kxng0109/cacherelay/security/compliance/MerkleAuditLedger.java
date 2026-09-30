package io.github.kxng0109.cacherelay.security.compliance;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.HexFormat;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Hash-chained audit ledger with per-receipt HMAC authenticity.
 *
 * <p>Each receipt binds timestamp, tenant, key hash, prompt hash, and response
 * hash into a leaf, advances a forward hash chain
 * ({@code Chain_i = SHA-256(Chain_{i-1} || Leaf_i)}), and authenticates the
 * pair with HMAC-SHA256 under the configured key. Anyone holding the key can
 * recompute both digests and verify a receipt standalone.</p>
 *
 * <p>The chain is in-memory and restarts from a fresh random genesis on every
 * boot (an epoch): receipts self-verify within and across epochs, but
 * cross-epoch linkage is not preserved. This is tamper-evidence with a
 * configured trust anchor, not third-party non-repudiation.</p>
 */
public class MerkleAuditLedger {

	private static final String HMAC_ALGO = "HmacSHA256";

	private static final int MIN_KEY_BYTES = 32;

	private static final byte[] GENESIS_CHAIN_STATE = new byte[32];

	static {
		new SecureRandom().nextBytes(GENESIS_CHAIN_STATE);
	}

	private final byte[] hmacKeyBytes;
	private final AtomicReference<byte[]> cumulativeChainState = new AtomicReference<>(GENESIS_CHAIN_STATE.clone());

	/**
	 * Creates the ledger with an operator-supplied HMAC key.
	 *
	 * @param hmacKey HMAC key bytes, 32 or more; defensively copied, never {@code null}
	 * @throws IllegalArgumentException for missing or short keys
	 */
	public MerkleAuditLedger(byte[] hmacKey) {
		if (hmacKey == null || hmacKey.length < MIN_KEY_BYTES) {
			throw new IllegalArgumentException("Audit HMAC key must hold 32 or more bytes");
		}
		this.hmacKeyBytes = hmacKey.clone();
	}

	/**
	 * Records a proxy transaction and generates an HMAC-authenticated receipt.
	 *
	 * @param tenantId     tenant or owner ID
	 * @param keyHash      hashed virtual key identifier
	 * @param promptBytes  raw bytes of the prompt payload
	 * @param responseHash SHA-256 hash of the generated response
	 * @return audit receipt verifiable with the configured HMAC key
	 */
	public AuditReceipt recordTransaction(
			String tenantId,
			String keyHash,
			byte[] promptBytes,
			String responseHash
	) {
		long timestamp = Instant.now().toEpochMilli();
		byte[] promptHash = sha256(promptBytes != null ? promptBytes : new byte[0]);

		// 1. Calculate Leaf = SHA-256(Timestamp || TenantId || KeyHash || H(Prompt) || H(Response))
		MessageDigest digest = getSha256Digest();
		digest.update(Long.toString(timestamp).getBytes(StandardCharsets.UTF_8));
		digest.update((byte) ':');
		digest.update((tenantId != null ? tenantId : "anonymous").getBytes(StandardCharsets.UTF_8));
		digest.update((byte) ':');
		digest.update((keyHash != null ? keyHash : "unauthenticated").getBytes(StandardCharsets.UTF_8));
		digest.update((byte) ':');
		digest.update(promptHash);
		digest.update((byte) ':');
		digest.update((responseHash != null ? responseHash : "none").getBytes(StandardCharsets.UTF_8));
		byte[] leafBytes = digest.digest();

		// 2. Advance Forward-Secure Hash Chain: Chain_i = SHA-256(Chain_{i-1} || Leaf_i)
		byte[] newChainState;
		while (true) {
			byte[] currentChain = cumulativeChainState.get();
			MessageDigest chainDigest = getSha256Digest();
			chainDigest.update(currentChain);
			chainDigest.update(leafBytes);
			newChainState = chainDigest.digest();
			if (cumulativeChainState.compareAndSet(currentChain, newChainState)) {
				break;
			}
		}

		// 3. HMAC signature over Leaf and Chain state
		String leafHex = HexFormat.of().formatHex(leafBytes);
		String chainHex = HexFormat.of().formatHex(newChainState);
		String signatureHex = computeHmacHex(leafHex + ":" + chainHex);

		String receiptHeader =
				leafHex.substring(0, 16) + ":" + chainHex.substring(0, 16) + ":" + signatureHex.substring(0, 16);

		return new AuditReceipt(receiptHeader, leafHex, chainHex, signatureHex);
	}

	private String computeHmacHex(String data) {
		try {
			Mac mac = Mac.getInstance(HMAC_ALGO);
			mac.init(new SecretKeySpec(hmacKeyBytes, HMAC_ALGO));
			byte[] hmacBytes = mac.doFinal(data.getBytes(StandardCharsets.UTF_8));
			return HexFormat.of().formatHex(hmacBytes);
		} catch (Exception e) {
			throw new IllegalStateException("Failed to calculate HMAC signature", e);
		}
	}

	private static byte[] sha256(byte[] data) {
		return getSha256Digest().digest(data);
	}

	private static MessageDigest getSha256Digest() {
		try {
			return MessageDigest.getInstance("SHA-256");
		} catch (NoSuchAlgorithmException e) {
			throw new IllegalStateException("SHA-256 not available", e);
		}
	}

	public record AuditReceipt(
			String receiptHeaderValue,
			String leafHash,
			String chainHash,
			String signature
	) {
	}
}
