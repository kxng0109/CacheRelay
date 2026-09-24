package io.github.kxng0109.cacherelay.auth;

import java.time.LocalDateTime;

import jakarta.validation.Validation;
import jakarta.validation.Validator;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.mock.env.MockPropertySource;
import org.springframework.scheduling.support.CronExpression;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("SsoRevalidationProperties")
class SsoRevalidationPropertiesTest {

	private static final Validator VALIDATOR = Validation.buildDefaultValidatorFactory().getValidator();

	@Test
	@DisplayName("the defaults constant carries the documented values")
	void defaults() {
		SsoRevalidationProperties props = SsoRevalidationProperties.DEFAULTS;

		assertThat(props.enabled()).isTrue();
		assertThat(props.hotCron()).isEqualTo("0 */15 * * * *");
		assertThat(props.coldCron()).isEqualTo("0 0 2 * * *");
		assertThat(props.batchSize()).isEqualTo(200);
		assertThat(props.hotStaleMinutes()).isEqualTo(15);
		assertThat(props.coldStaleHours()).isEqualTo(24);
		assertThat(VALIDATOR.validate(props)).isEmpty();
	}

	@Test
	@DisplayName("properties bind from the gateway.sso.revalidation prefix")
	void bindsFromPrefix() {
		StandardEnvironment environment = new StandardEnvironment();
		environment.getPropertySources().addFirst(new MockPropertySource("test")
				.withProperty("gateway.sso.revalidation.enabled", "false")
				.withProperty("gateway.sso.revalidation.hot-cron", "0 */5 * * * *")
				.withProperty("gateway.sso.revalidation.cold-cron", "0 0 3 * * *")
				.withProperty("gateway.sso.revalidation.batch-size", "50")
				.withProperty("gateway.sso.revalidation.hot-stale-minutes", "5")
				.withProperty("gateway.sso.revalidation.cold-stale-hours", "12"));

		SsoRevalidationProperties bound = Binder.get(environment)
				.bind("gateway.sso.revalidation", SsoRevalidationProperties.class)
				.get();

		assertThat(bound.enabled()).isFalse();
		assertThat(bound.hotCron()).isEqualTo("0 */5 * * * *");
		assertThat(bound.coldCron()).isEqualTo("0 0 3 * * *");
		assertThat(bound.batchSize()).isEqualTo(50);
		assertThat(bound.hotStaleMinutes()).isEqualTo(5);
		assertThat(bound.coldStaleHours()).isEqualTo(12);
		assertThat(VALIDATOR.validate(bound)).isEmpty();
	}

	@Test
	@DisplayName("partial properties fall back to the defaults for the rest")
	void partialPropertiesUseDefaults() {
		StandardEnvironment environment = new StandardEnvironment();
		environment.getPropertySources().addFirst(new MockPropertySource("test")
				.withProperty("gateway.sso.revalidation.batch-size", "50"));

		SsoRevalidationProperties bound = Binder.get(environment)
				.bind("gateway.sso.revalidation", SsoRevalidationProperties.class)
				.get();

		assertThat(bound.enabled()).isTrue();
		assertThat(bound.batchSize()).isEqualTo(50);
		assertThat(bound.hotStaleMinutes()).isEqualTo(15);
	}

	@Test
	@DisplayName("out-of-range values violate the constraints")
	void constraints() {
		assertThat(VALIDATOR.validate(
				new SsoRevalidationProperties(true, "x", "y", 0, 15, 24))).isNotEmpty();
		assertThat(VALIDATOR.validate(
				new SsoRevalidationProperties(true, "x", "y", 5001, 15, 24))).isNotEmpty();
		assertThat(VALIDATOR.validate(
				new SsoRevalidationProperties(true, "x", "y", 200, 0, 24))).isNotEmpty();
		assertThat(VALIDATOR.validate(
				new SsoRevalidationProperties(true, "x", "y", 200, 15, 0))).isNotEmpty();
		assertThat(VALIDATOR.validate(
				new SsoRevalidationProperties(true, "", "y", 200, 15, 24))).isNotEmpty();
		assertThat(VALIDATOR.validate(SsoRevalidationProperties.DEFAULTS)).isEmpty();
	}

	@Test
	@DisplayName("cron expressions are syntactically valid")
	void cronExpressionsValid() {
		CronExpression hot = CronExpression.parse(
				SsoRevalidationProperties.DEFAULTS.hotCron());
		CronExpression cold = CronExpression.parse(
				SsoRevalidationProperties.DEFAULTS.coldCron());

		assertThat(hot.next(LocalDateTime.now())).isNotNull();
		assertThat(cold.next(LocalDateTime.now())).isNotNull();
	}
}
