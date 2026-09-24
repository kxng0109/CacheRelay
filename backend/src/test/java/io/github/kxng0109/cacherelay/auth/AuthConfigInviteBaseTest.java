package io.github.kxng0109.cacherelay.auth;

import java.util.HashMap;
import java.util.stream.Stream;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.core.env.Environment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

@DisplayName("AuthConfig invite-base-url startup gate")
class AuthConfigInviteBaseTest {

	@ParameterizedTest(name = "blank stays unset: [{index}]")
	@MethodSource("blanks")
	@DisplayName("blank values keep the request-derived fallback")
	void blankKeepsFallback(String blank) {
		AuthConfig.validateInviteBaseUrl(blank);
	}

	@ParameterizedTest(name = "accept: {0}")
	@MethodSource("validBases")
	@DisplayName("absolute http(s) bases pass in any profile")
	void validBasesPass(String base) {
		AuthConfig.validateInviteBaseUrl(base);
	}

	@ParameterizedTest(name = "reject: {0}")
	@MethodSource("malformedBases")
	@DisplayName("malformed bases fail fast naming the property")
	void malformedBasesFail(String base) {
		assertThatThrownBy(() -> AuthConfig.validateInviteBaseUrl(base))
				.isInstanceOf(IllegalStateException.class)
				.hasMessageContaining("GATEWAY_AUTH_INVITE_BASE_URL");
	}

	@Test
	@DisplayName("AuthConfig bean rejects a malformed base outside dev/test")
	void beanRejectsMalformed() {
		AuthProperties configured = new AuthProperties(null, null, null, null, null, null, null,
				null, null, null, "test-only-jwt-secret-32-bytes-min!!", 180, new HashMap<>(), 5,
				null, null, "javascript:alert(1)");
		Environment prodEnv = mock(Environment.class);
		when(prodEnv.getActiveProfiles()).thenReturn(new String[]{"prod"});

		assertThatThrownBy(() -> new AuthConfig(configured, prodEnv))
				.isInstanceOf(IllegalStateException.class)
				.hasMessageContaining("GATEWAY_AUTH_INVITE_BASE_URL");
	}

	@Test
	@DisplayName("AuthConfig bean accepts a valid base and skips the fallback warning")
	void beanAcceptsValidBase() {
		AuthProperties configured = new AuthProperties(null, null, null, null, null, null, null,
				null, null, null, "test-only-jwt-secret-32-bytes-min!!", 180, new HashMap<>(), 5,
				null, null, "https://app.example.com/");
		Environment prodEnv = mock(Environment.class);
		when(prodEnv.getActiveProfiles()).thenReturn(new String[]{"prod"});

		assertThat(new AuthConfig(configured, prodEnv)).isNotNull();
	}

	private static Stream<Arguments> blanks() {
		return Stream.of(
				Arguments.of((String) null),
				Arguments.of(""),
				Arguments.of("   "));
	}

	private static Stream<Arguments> validBases() {
		return Stream.of(
				Arguments.of("https://app.example.com/"),
				Arguments.of("https://app.example.com"),
				Arguments.of("http://localhost:5173"),
				Arguments.of("  https://app.example.com  "),
				Arguments.of("HTTPS://App.Example.COM:8443/cr/"),
				Arguments.of("http://[::1]:5173"));
	}

	private static Stream<Arguments> malformedBases() {
		return Stream.of(
				Arguments.of("javascript:alert(1)"),
				Arguments.of("ftp://files.example.com"),
				Arguments.of("nota-url"),
				Arguments.of("//app.example.com/x"),
				Arguments.of("http://user:pass@app.example.com"),
				Arguments.of("https://app.example.com/?q=1"),
				Arguments.of("https://app.example.com/#f"),
				Arguments.of("mailto:op@example.com"),
				Arguments.of("http:///no-host"),
				Arguments.of("https://app.example.com/ /x"));
	}
}
