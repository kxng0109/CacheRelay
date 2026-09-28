package io.github.kxng0109.cacherelay.a2a.security;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for {@link A2aTaskOwnershipIndex}: blank and null records are
 * ignored fail-closed, and recorded owners round-trip per agent.
 */
@DisplayName("A2aTaskOwnershipIndex")
class A2aTaskOwnershipIndexTest {

	@Test
	@DisplayName("blank and null records are ignored, ownerOf stays null")
	void blankAndNullRecordsIgnored() {
		A2aTaskOwnershipIndex index = new A2aTaskOwnershipIndex(100, Duration.ofMinutes(5));

		index.record("agent", null, "tenant-a");
		index.record("agent", "   ", "tenant-a");
		index.record("agent", "", "tenant-a");
		index.record("agent", "t-null-owner", null);

		assertThat(index.ownerOf("agent", "t-null-owner")).isNull();
		assertThat(index.ownerOf("agent", "t-unknown")).isNull();
		assertThat(index.ownerOf("other-agent", "t-unknown")).isNull();
	}

	@Test
	@DisplayName("recorded owners round-trip per agent and task")
	void recordedOwnerRoundTrips() {
		A2aTaskOwnershipIndex index = new A2aTaskOwnershipIndex(100, Duration.ofMinutes(5));

		index.record("agent", "t1", "tenant-a");
		index.record("agent", "t2", "tenant-b");

		assertThat(index.ownerOf("agent", "t1")).isEqualTo("tenant-a");
		assertThat(index.ownerOf("agent", "t2")).isEqualTo("tenant-b");
		assertThat(index.ownerOf("other-agent", "t1")).isNull();
	}
}
