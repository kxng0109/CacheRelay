package io.github.kxng0109.cacherelay.security.compliance;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.HexFormat;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("AuditLedgerConfig Tests")
class AuditLedgerConfigTest {

	@Test
	@DisplayName("decodes hex keys of 32 or more bytes, case-insensitively")
	void decodesValidKeys() {
		byte[] lower = AuditLedgerConfig.decodeKey(
				"0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef");
		byte[] upper = AuditLedgerConfig.decodeKey(
				"0123456789ABCDEF0123456789ABCDEF0123456789ABCDEF0123456789ABCDEF");

		assertThat(lower).hasSize(32);
		assertThat(upper).isEqualTo(lower);
	}

	@Test
	@DisplayName("rejects blank, malformed, and short keys fail-fast")
	void rejectsBadKeys() {
		assertThatThrownBy(() -> AuditLedgerConfig.decodeKey(null))
				.isInstanceOf(IllegalStateException.class)
				.hasMessageContaining("GATEWAY_AUDIT_HMAC_KEY");
		assertThatThrownBy(() -> AuditLedgerConfig.decodeKey("   "))
				.isInstanceOf(IllegalStateException.class)
				.hasMessageContaining("GATEWAY_AUDIT_HMAC_KEY");
		assertThatThrownBy(() -> AuditLedgerConfig.decodeKey("not-hex!!"))
				.isInstanceOf(IllegalStateException.class)
				.hasMessageContaining("hex-encoded");
		assertThatThrownBy(() -> AuditLedgerConfig.decodeKey("abcd"))
				.isInstanceOf(IllegalStateException.class)
				.hasMessageContaining("32 or more bytes");
	}
}
