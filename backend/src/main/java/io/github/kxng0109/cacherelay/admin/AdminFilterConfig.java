package io.github.kxng0109.cacherelay.admin;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import tools.jackson.databind.ObjectMapper;

import io.github.kxng0109.cacherelay.auth.JwtService;
import io.github.kxng0109.cacherelay.auth.UserAccountRepository;
import io.github.kxng0109.cacherelay.auth.AuthAuditService;

/**
 * Registers servlet filters for administrative endpoints under {@code /v1/admin/**}.
 */
@Configuration
public class AdminFilterConfig {

	/**
	 * Registers the admin authentication filter with high precedence.
	 *
	 * @param masterKey    the configured master admin secret
	 * @param objectMapper JSON serializer for RFC 9457 Problem Details responses
	 * @param jwtService   access-token validator for admin sessions
	 * @param users        account lookup for admin sessions
	 * @param audit        mutation audit log
	 * @return the filter registration
	 */
	@Bean
	FilterRegistrationBean<AdminAuthFilter> adminAuthFilterRegistration(
			@Value("${gateway.admin.master-key:}") String masterKey,
			ObjectMapper objectMapper,
			JwtService jwtService,
			UserAccountRepository users,
			AuthAuditService audit
	) {
		FilterRegistrationBean<AdminAuthFilter> registration = new FilterRegistrationBean<>(
				new AdminAuthFilter(masterKey, objectMapper, jwtService, users, audit)
		);
		registration.setOrder(1);
		registration.addUrlPatterns("/v1/admin/*");
		registration.setName("cacherelayAdminAuthFilter");
		return registration;
	}
}
