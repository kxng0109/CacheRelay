package io.github.kxng0109.cacherelay.capture;

import java.util.List;

import jakarta.validation.Validation;
import jakarta.validation.Validator;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.mock.env.MockPropertySource;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("CaptureProperties")
class CapturePropertiesTest {

	private static final Validator VALIDATOR = Validation.buildDefaultValidatorFactory().getValidator();

	@Test
	@DisplayName("the defaults keep capture off with documented ceilings")
	void defaults() {
		CaptureProperties props = CaptureProperties.DEFAULTS;

		assertThat(props.enabled()).isFalse();
		assertThat(props.samplePerMille()).isEqualTo(1);
		assertThat(props.maxPersistsPerSecond()).isEqualTo(100);
		assertThat(props.captureDir()).isEqualTo("./data/capture");
		assertThat(props.maxCharsPerField()).isEqualTo(32768);
		assertThat(props.maxSegmentBytes()).isEqualTo(268435456L);
		assertThat(props.defaultTtlDays()).isEqualTo(90);
		assertThat(props.strictTtlDays()).isEqualTo(7);
		assertThat(props.allowlist()).isEmpty();
		assertThat(props.ruleFor(null)).isEmpty();
		assertThat(props.ruleFor("nobody")).isEmpty();
		assertThat(VALIDATOR.validate(props)).isEmpty();
	}

	@Test
	@DisplayName("properties bind from the gateway.capture prefix with relaxed naming")
	void bindsFromPrefix() {
		StandardEnvironment environment = new StandardEnvironment();
		environment.getPropertySources().addFirst(new MockPropertySource("test")
				.withProperty("gateway.capture.enabled", "true")
				.withProperty("gateway.capture.sample-per-mille", "10")
				.withProperty("gateway.capture.max-persists-per-second", "50")
				.withProperty("gateway.capture.capture-dir", "/tmp/cap")
				.withProperty("gateway.capture.max-chars-per-field", "4096")
				.withProperty("gateway.capture.max-segment-bytes", "2097152")
				.withProperty("gateway.capture.default-ttl-days", "30")
				.withProperty("gateway.capture.strict-ttl-days", "7")
				.withProperty("gateway.capture.allowlist[0].owner-or-key", "owner-1")
				.withProperty("gateway.capture.allowlist[0].jurisdiction", "eu")
				.withProperty("gateway.capture.allowlist[0].ttl-days", "14")
				.withProperty("gateway.capture.allowlist[0].strict", "true"));

		CaptureProperties bound = Binder.get(environment)
				.bind("gateway.capture", CaptureProperties.class)
				.get();

		assertThat(bound.enabled()).isTrue();
		assertThat(bound.samplePerMille()).isEqualTo(10);
		assertThat(bound.maxCharsPerField()).isEqualTo(4096);
		assertThat(bound.allowlist()).hasSize(1);
		CaptureProperties.CaptureRule rule = bound.ruleFor("owner-1").orElseThrow();
		assertThat(rule.jurisdiction()).isEqualTo("eu");
		assertThat(rule.effectiveTtlDays(bound.defaultTtlDays(), bound.strictTtlDays()))
				.isEqualTo(7);
		assertThat(VALIDATOR.validate(bound)).isEmpty();
	}

	@Test
	@DisplayName("partial properties fall back to the defaults for the rest")
	void partialPropertiesUseDefaults() {
		StandardEnvironment environment = new StandardEnvironment();
		environment.getPropertySources().addFirst(new MockPropertySource("test")
				.withProperty("gateway.capture.enabled", "true"));

		CaptureProperties bound = Binder.get(environment)
				.bind("gateway.capture", CaptureProperties.class)
				.get();

		assertThat(bound.enabled()).isTrue();
		assertThat(bound.samplePerMille()).isEqualTo(1);
		assertThat(bound.allowlist()).isEmpty();
	}

	@Test
	@DisplayName("rule TTLs resolve against defaults with clamping")
	void ruleTtlResolution() {
		CaptureProperties.CaptureRule plain =
				new CaptureProperties.CaptureRule("owner-1", null, null, false);
		assertThat(plain.jurisdiction()).isEqualTo("default");
		assertThat(plain.effectiveTtlDays(90, 7)).isEqualTo(90);

		CaptureProperties.CaptureRule strict =
				new CaptureProperties.CaptureRule("owner-2", "eu", null, true);
		assertThat(strict.effectiveTtlDays(90, 7)).isEqualTo(7);

		CaptureProperties.CaptureRule custom =
				new CaptureProperties.CaptureRule("owner-3", "eu", 14, false);
		assertThat(custom.effectiveTtlDays(90, 7)).isEqualTo(14);

		CaptureProperties.CaptureRule over =
				new CaptureProperties.CaptureRule("owner-4", "eu", 5000, false);
		assertThat(over.effectiveTtlDays(90, 7)).isEqualTo(90);
	}

	@Test
	@DisplayName("out-of-range values violate the constraints")
	void constraints() {
		assertThat(VALIDATOR.validate(new CaptureProperties(true, -1, 100, "./d", 32768,
				268435456L, 90, 7, List.of()))).isNotEmpty();
		assertThat(VALIDATOR.validate(new CaptureProperties(true, 1001, 100, "./d", 32768,
				268435456L, 90, 7, List.of()))).isNotEmpty();
		assertThat(VALIDATOR.validate(new CaptureProperties(true, 1, 0, "./d", 32768,
				268435456L, 90, 7, List.of()))).isNotEmpty();
		assertThat(VALIDATOR.validate(new CaptureProperties(true, 1, 100, "  ", 32768,
				268435456L, 90, 7, List.of()))).isNotEmpty();
		assertThat(VALIDATOR.validate(new CaptureProperties(true, 1, 100, "./d", 512,
				268435456L, 90, 7, List.of()))).isNotEmpty();
		assertThat(VALIDATOR.validate(new CaptureProperties(true, 1, 100, "./d", 32768,
				100L, 90, 7, List.of()))).isNotEmpty();
		assertThat(VALIDATOR.validate(new CaptureProperties(true, 1, 100, "./d", 32768,
				268435456L, 0, 7, List.of()))).isNotEmpty();
		assertThat(VALIDATOR.validate(new CaptureProperties.CaptureRule("", "eu", null,
				false))).isNotEmpty();
		assertThat(VALIDATOR.validate(CaptureProperties.DEFAULTS)).isEmpty();
	}
}
