package io.github.kxng0109.cacherelay.auth;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Proves the CSP extra-origin binding: valid origins bind, malformed values
 * fail startup instead of shipping a broken policy.
 */
@DisplayName("CspProperties binding")
class CspPropertiesBindingTest {

	@Configuration(proxyBeanMethods = false)
	@EnableConfigurationProperties(CspProperties.class)
	static class Config {
	}

	private final ApplicationContextRunner runner = new ApplicationContextRunner()
			.withUserConfiguration(Config.class);

	@Test
	@DisplayName("absolute origins bind")
	void absoluteOriginsBind() {
		runner.withPropertyValues("gateway.csp.extra-connect-src=http://localhost:9091")
				.run(context -> assertThat(context.getBean(CspProperties.class).getExtraConnectSrc())
						.containsExactly("http://localhost:9091"));
	}

	@Test
	@DisplayName("malformed values fail startup")
	void malformedValuesFailStartup() {
		runner.withPropertyValues("gateway.csp.extra-connect-src=https://metrics.internal/api")
				.run(context -> {
					assertThat(context).hasFailed();
					assertThat(context.getStartupFailure())
							.hasStackTraceContaining("extra-connect-src");
				});
	}
}
