package io.github.kxng0109.cacherelay.admin;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("AdminProperties master-key fail-fast validation")
class AdminPropertiesValidationTest {

	@EnableConfigurationProperties(AdminProperties.class)
	@Configuration(proxyBeanMethods = false)
	static class PropsConfig {
	}

	private final ApplicationContextRunner runner =
			new ApplicationContextRunner().withUserConfiguration(PropsConfig.class);

	@Test
	@DisplayName("Blank master key fails startup")
	void blankMasterKeyFails() {
		runner.withPropertyValues("gateway.admin.master-key:  ").run(ctx -> {
			assertThat(ctx).hasFailed();
			assertThat(ctx.getStartupFailure())
					.hasStackTraceContaining("Value required!");
		});
	}

	@Test
	@DisplayName("Short master key fails startup")
	void shortMasterKeyFails() {
		runner.withPropertyValues("gateway.admin.master-key: short").run(ctx -> {
			assertThat(ctx).hasFailed();
			assertThat(ctx.getStartupFailure())
					.hasStackTraceContaining("at least 32 bytes");
		});
	}

	@Test
	@DisplayName("Known default master key fails startup")
	void knownDefaultMasterKeyFails() {
		runner.withPropertyValues(
				"gateway.admin.master-key: cacherelay_admin_secret_key").run(ctx -> {
			assertThat(ctx).hasFailed();
			assertThat(ctx.getStartupFailure())
					.hasStackTraceContaining("must not be a published default");
		});
	}

	@Test
	@DisplayName("Valid master key starts and masks toString")
	void validMasterKeyStartsAndMasks() {
		runner.withPropertyValues(
				"gateway.admin.master-key: test-only-admin-master-key-32b-min!!").run(ctx -> {
			assertThat(ctx).hasNotFailed();
			assertThat(ctx.getBean(AdminProperties.class)).isNotNull();
			assertThat(ctx.getBean(AdminProperties.class).getMasterKey().toString())
					.isEqualTo("****");
		});
	}
}
