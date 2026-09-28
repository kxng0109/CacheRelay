package io.github.kxng0109.cacherelay.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * FS-B17 capacity profiles: floor defaults with fail-fast validation.
 */
@DisplayName("CapacityProfileProperties")
class CapacityProfilePropertiesTest {

	@Test
	@DisplayName("defaults select the floor profile with unit weight")
	void defaultsAreFloor() {
		assertThat(CapacityProfileProperties.DEFAULTS.profile()).isEqualTo("floor");
		assertThat(CapacityProfileProperties.DEFAULTS.instanceWeight()).isEqualTo(1);
		assertThat(new CapacityProfileProperties("standard", 3).profile()).isEqualTo("standard");
	}

	@Test
	@DisplayName("unknown profiles and weights below one fail fast")
	void invalidProfilesRejected() {
		assertThatThrownBy(() -> new CapacityProfileProperties("xlarge", 1))
				.isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(() -> new CapacityProfileProperties("floor", 0))
				.isInstanceOf(IllegalArgumentException.class);
	}
}
