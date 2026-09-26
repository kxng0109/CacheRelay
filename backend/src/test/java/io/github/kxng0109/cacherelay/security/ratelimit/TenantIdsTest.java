package io.github.kxng0109.cacherelay.security.ratelimit;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Unit tests for the server-side tenant identity guard (FS-B07).
 */
@DisplayName("TenantIds")
class TenantIdsTest {

	@ParameterizedTest(name = "accepts {0}")
	@ValueSource(strings = {"tenant-1", "owner-1", "local", "load", "a", "a-b_c9"})
	@DisplayName("well-formed tenant ids are accepted")
	void acceptsWellFormed(String tenant) {
		assertThat(TenantIds.isValidTenant(tenant)).isTrue();
		assertThat(TenantIds.requireValidTenant(tenant)).isEqualTo(tenant);
	}

	@Test
	@DisplayName("64-char tenant is the longest accepted")
	void acceptsMaxLength() {
		String longest = "a".repeat(64);
		assertThat(TenantIds.isValidTenant(longest)).isTrue();
		assertThat(TenantIds.isValidTenant(longest + "a")).isFalse();
	}

	@ParameterizedTest(name = "rejects {0}")
	@ValueSource(strings = {
			"global", "unknown", "GLOBAL", "Unknown",
			"Victim Tenant!", "a@b.com", "a:b", "a/b", "a b", "", "-lead", "trail-",
			"_lead", "trail_", "a__b", "UPPER", "MixedCase"
	})
	@DisplayName("malformed or reserved tenant ids are rejected")
	void rejectsMalformedOrReserved(String tenant) {
		assertThat(TenantIds.isValidTenant(tenant)).isFalse();
		assertThatThrownBy(() -> TenantIds.requireValidTenant(tenant))
				.isInstanceOf(IllegalArgumentException.class);
	}

	@Test
	@DisplayName("null tenant is rejected")
	@SuppressWarnings("DataFlowIssue")
	void rejectsNull() {
		assertThat(TenantIds.isValidTenant(null)).isFalse();
		assertThatThrownBy(() -> TenantIds.requireValidTenant(null))
				.isInstanceOf(IllegalArgumentException.class);
	}
}
