package io.github.kxng0109.cacherelay.admin;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import tools.jackson.databind.ObjectMapper;

import io.github.kxng0109.cacherelay.auth.AuthAuditService;
import io.github.kxng0109.cacherelay.auth.JwtService;
import io.github.kxng0109.cacherelay.auth.UserAccountRepository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

@DisplayName("AdminFilterConfig")
class AdminFilterConfigTest {

	@Test
	@DisplayName("registers AdminAuthFilter with correct order and url patterns")
	void registersAdminFilterCorrectly() {
		AdminFilterConfig config = new AdminFilterConfig();
		ObjectMapper objectMapper = new ObjectMapper();

		FilterRegistrationBean<AdminAuthFilter> registration = config.adminAuthFilterRegistration(
				"secret-key",
				objectMapper,
				mock(JwtService.class),
				mock(UserAccountRepository.class),
				mock(AuthAuditService.class)
		);

		assertThat(registration.getOrder()).isEqualTo(1);
		assertThat(registration.getUrlPatterns()).containsExactly("/v1/admin/*");
		assertThat(registration.getFilter()).isNotNull();
	}
}
