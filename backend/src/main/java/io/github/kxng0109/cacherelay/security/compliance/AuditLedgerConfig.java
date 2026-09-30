package io.github.kxng0109.cacherelay.security.compliance;

import java.util.HexFormat;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Provides the audit ledger only when its trust anchor is configured.
 *
 * <p>No {@code gateway.audit.hmac-key} means no ledger bean: the proxy already
 * tolerates its absence, so gateways without a configured key emit no receipts
 * instead of unverifiable ones. A present-but-malformed key fails fast.</p>
 */
@Configuration
public class AuditLedgerConfig {

	/**
	 * Creates the ledger from the configured hex-encoded HMAC key.
	 *
	 * @param hexKey hex-encoded key, 64 or more hex chars (32 or more bytes)
	 * @return the ledger
	 * @throws IllegalStateException for undecodable or short keys
	 */
	@Bean
	@ConditionalOnProperty(name = "gateway.audit.hmac-key")
	public MerkleAuditLedger auditLedger(@Value("${gateway.audit.hmac-key}") String hexKey) {
		return new MerkleAuditLedger(decodeKey(hexKey));
	}

	/**
	 * Decodes a hex-encoded HMAC key.
	 *
	 * @param hexKey hex text, case-insensitive
	 * @return decoded key bytes
	 * @throws IllegalStateException for blank, undecodable, or short keys
	 */
	static byte[] decodeKey(String hexKey) {
		if (hexKey == null || hexKey.isBlank()) {
			throw new IllegalStateException(
					"gateway.audit.hmac-key (GATEWAY_AUDIT_HMAC_KEY) is required for audit receipts");
		}
		final byte[] decoded;
		try {
			decoded = HexFormat.of().parseHex(hexKey.trim());
		} catch (IllegalArgumentException malformed) {
			throw new IllegalStateException(
					"gateway.audit.hmac-key (GATEWAY_AUDIT_HMAC_KEY) must be hex-encoded");
		}
		if (decoded.length < 32) {
			throw new IllegalStateException(
					"gateway.audit.hmac-key (GATEWAY_AUDIT_HMAC_KEY) must decode to 32 or more bytes");
		}
		return decoded;
	}
}
