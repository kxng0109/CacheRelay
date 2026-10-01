package io.github.kxng0109.cacherelay.security.filter;

import io.github.kxng0109.cacherelay.contracts.GatewayProperties;
import io.github.kxng0109.cacherelay.security.guardrail.common.GuardrailProperties;
import io.github.kxng0109.cacherelay.security.guardrail.injection.PromptInjectionScanner;
import io.github.kxng0109.cacherelay.security.guardrail.pii.PiiAnonymizer;
import io.github.kxng0109.cacherelay.security.guardrail.secret.IngressSecretScanner;
import io.github.kxng0109.cacherelay.security.guardrail.vendor.GuardrailVendorClient;
import io.github.kxng0109.cacherelay.security.ratelimit.KeyManagementService;
import io.github.kxng0109.cacherelay.security.ratelimit.RateLimitEngine;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import tools.jackson.databind.ObjectMapper;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

@DisplayName("SecurityFilterConfig Tests")
class SecurityFilterConfigTest {

	private final SecurityFilterConfig config = new SecurityFilterConfig();

	@Test
	@DisplayName("registers RequestBodyCachingFilter at order 0 for chat, embeddings, MCP, and A2A")
	void registersRequestBodyCachingFilter() {
		FilterRegistrationBean<RequestBodyCachingFilter> reg =
				config.requestBodyCachingFilterRegistration(1_048_576);

		assertThat(reg.getOrder()).isEqualTo(0);
		assertThat(reg.getUrlPatterns())
				.containsExactlyInAnyOrder(
						RequestBodyCachingFilter.TARGET_PATH_CHAT,
						RequestBodyCachingFilter.TARGET_PATH_EMBEDDINGS,
						RequestBodyCachingFilter.TARGET_PATH_MCP,
						RequestBodyCachingFilter.TARGET_PATH_MCP + "/*",
						RequestBodyCachingFilter.TARGET_PATH_A2A + "/*");
		assertThat(reg.getFilter()).isInstanceOf(RequestBodyCachingFilter.class);
	}

	@Test
	@DisplayName("registers KeyAuthFilter at order 1 for chat and embeddings")
	void registersKeyAuthFilter() {
		KeyManagementService keyManagementService = mock(KeyManagementService.class);
		RateLimitEngine rateLimitEngine = mock(RateLimitEngine.class);
		ObjectMapper objectMapper = new ObjectMapper();

		FilterRegistrationBean<KeyAuthFilter> reg = config.keyAuthFilterRegistration(
				keyManagementService, rateLimitEngine, objectMapper, new GatewayProperties()
		);

		assertThat(reg.getOrder()).isEqualTo(1);
		assertThat(reg.getUrlPatterns())
				.containsExactlyInAnyOrder(
						KeyAuthFilter.TARGET_PATH_CHAT,
						KeyAuthFilter.TARGET_PATH_EMBEDDINGS);
		assertThat(reg.getFilter()).isInstanceOf(KeyAuthFilter.class);
	}

	@Test
	@DisplayName("registers IngressSecurityFilter at order 2 for chat and embeddings")
	void registersIngressSecurityFilter() {
		IngressSecretScanner secretScanner = mock(IngressSecretScanner.class);
		PromptInjectionScanner injectionScanner = mock(PromptInjectionScanner.class);
		PiiAnonymizer piiAnonymizer = mock(PiiAnonymizer.class);
		GuardrailProperties properties = new GuardrailProperties();
		ObjectMapper objectMapper = new ObjectMapper();

		FilterRegistrationBean<IngressSecurityFilter> reg = config.ingressSecurityFilterRegistration(
				secretScanner, injectionScanner, piiAnonymizer, properties, objectMapper,
				mock(ObjectProvider.class)
		);

		assertThat(reg.getOrder()).isEqualTo(2);
		assertThat(reg.getUrlPatterns()).containsExactlyInAnyOrder(
				IngressSecurityFilter.TARGET_PATH_CHAT,
				IngressSecurityFilter.TARGET_PATH_EMBEDDINGS);
		assertThat(reg.getFilter()).isInstanceOf(IngressSecurityFilter.class);
	}

	@Test
	@DisplayName("gateway filters cover chat and embeddings, nothing else")
	void filtersCoverDeclaredRoutes() {
		assertThat(List.of(
				RequestBodyCachingFilter.TARGET_PATH_CHAT,
				RequestBodyCachingFilter.TARGET_PATH_EMBEDDINGS,
				KeyAuthFilter.TARGET_PATH_CHAT,
				KeyAuthFilter.TARGET_PATH_EMBEDDINGS,
				IngressSecurityFilter.TARGET_PATH_CHAT,
				IngressSecurityFilter.TARGET_PATH_EMBEDDINGS))
				.contains("/v1/chat/completions", "/v1/embeddings")
				.doesNotContain("/v1/admin", "/actuator", "/v3/api-docs");
	}
}
