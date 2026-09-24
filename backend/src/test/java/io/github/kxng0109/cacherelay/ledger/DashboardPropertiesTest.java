package io.github.kxng0109.cacherelay.ledger;

import jakarta.validation.Validation;
import jakarta.validation.Validator;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.mock.env.MockPropertySource;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("DashboardProperties")
class DashboardPropertiesTest {

	private static final Validator VALIDATOR = Validation.buildDefaultValidatorFactory().getValidator();

	@Test
	@DisplayName("the defaults constant carries the documented values")
	void defaults() {
		DashboardProperties props = DashboardProperties.DEFAULTS;

		assertThat(props.resultTtlMinutes()).isEqualTo(5);
		assertThat(props.graceOverlapMinutes()).isEqualTo(5);
		assertThat(props.defaultWindowDays()).isEqualTo(7);
		assertThat(props.statementTimeoutSeconds()).isEqualTo(10);
		assertThat(props.rateLimitPerMinute()).isEqualTo(30);
		assertThat(VALIDATOR.validate(props)).isEmpty();
	}

	@Test
	@DisplayName("properties bind from the gateway.dashboard prefix with relaxed naming")
	void bindsFromPrefix() {
		StandardEnvironment environment = new StandardEnvironment();
		environment.getPropertySources().addFirst(new MockPropertySource("test")
				.withProperty("gateway.dashboard.result-ttl-minutes", "10")
				.withProperty("gateway.dashboard.grace-overlap-minutes", "2")
				.withProperty("gateway.dashboard.default-window-days", "30")
				.withProperty("gateway.dashboard.statement-timeout-seconds", "20")
				.withProperty("gateway.dashboard.rate-limit-per-minute", "60"));

		DashboardProperties bound = Binder.get(environment)
				.bind("gateway.dashboard", DashboardProperties.class)
				.get();

		assertThat(bound.resultTtlMinutes()).isEqualTo(10);
		assertThat(bound.graceOverlapMinutes()).isEqualTo(2);
		assertThat(bound.defaultWindowDays()).isEqualTo(30);
		assertThat(bound.statementTimeoutSeconds()).isEqualTo(20);
		assertThat(bound.rateLimitPerMinute()).isEqualTo(60);
		assertThat(VALIDATOR.validate(bound)).isEmpty();
	}

	@Test
	@DisplayName("partial properties fall back to the defaults for the rest")
	void partialPropertiesUseDefaults() {
		StandardEnvironment environment = new StandardEnvironment();
		environment.getPropertySources().addFirst(new MockPropertySource("test")
				.withProperty("gateway.dashboard.default-window-days", "14"));

		DashboardProperties bound = Binder.get(environment)
				.bind("gateway.dashboard", DashboardProperties.class)
				.get();

		assertThat(bound.resultTtlMinutes()).isEqualTo(5);
		assertThat(bound.graceOverlapMinutes()).isEqualTo(5);
		assertThat(bound.defaultWindowDays()).isEqualTo(14);
		assertThat(bound.statementTimeoutSeconds()).isEqualTo(10);
		assertThat(bound.rateLimitPerMinute()).isEqualTo(30);
	}

	@Test
	@DisplayName("out-of-range values violate the constraints")
	void constraints() {
		assertThat(VALIDATOR.validate(new DashboardProperties(0, 5, 7, 10, 30))).isNotEmpty();
		assertThat(VALIDATOR.validate(new DashboardProperties(61, 5, 7, 10, 30))).isNotEmpty();
		assertThat(VALIDATOR.validate(new DashboardProperties(5, 0, 7, 10, 30))).isNotEmpty();
		assertThat(VALIDATOR.validate(new DashboardProperties(5, 5, 0, 10, 30))).isNotEmpty();
		assertThat(VALIDATOR.validate(new DashboardProperties(5, 5, 91, 10, 30))).isNotEmpty();
		assertThat(VALIDATOR.validate(new DashboardProperties(5, 5, 7, 0, 30))).isNotEmpty();
		assertThat(VALIDATOR.validate(new DashboardProperties(5, 5, 7, 10, 0))).isNotEmpty();
		assertThat(VALIDATOR.validate(DashboardProperties.DEFAULTS)).isEmpty();
	}
}
