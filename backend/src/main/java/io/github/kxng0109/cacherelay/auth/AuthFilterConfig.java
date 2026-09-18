package io.github.kxng0109.cacherelay.auth;

import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import tools.jackson.databind.ObjectMapper;

/**
 * Registers the refresh-rotation filter on {@code POST /v1/auth/refresh}.
 */
@Configuration
public class AuthFilterConfig {

	/**
	 * Registers the refresh filter after the security chain passes the public auth routes.
	 *
	 * @param refresh      refresh lifecycle
	 * @param jwt          access-token issuer
	 * @param users        account lookup
	 * @param cookies      cookie handling
	 * @param properties   auth tuning surface
	 * @param audit        audit log
	 * @param objectMapper JSON serializer
	 * @return the filter registration
	 */
	@Bean
	FilterRegistrationBean<RefreshFilter> refreshFilterRegistration(
			RefreshService refresh,
			JwtService jwt,
			UserAccountRepository users,
			AuthCookieService cookies,
			AuthProperties properties,
			AuthAuditService audit,
			ObjectMapper objectMapper
	) {
		FilterRegistrationBean<RefreshFilter> registration = new FilterRegistrationBean<>(
				new RefreshFilter(refresh, jwt, users, cookies, properties, audit, objectMapper)
		);
		registration.setOrder(2);
		registration.addUrlPatterns("/v1/auth/refresh");
		registration.setName("cacherelayRefreshFilter");
		return registration;
	}

	/**
	 * Registers the per-response CSP nonce filter ahead of the security chain.
	 *
	 * @return the filter registration
	 */
	@Bean
	FilterRegistrationBean<CspNonceFilter> cspNonceFilterRegistration() {
		FilterRegistrationBean<CspNonceFilter> registration = new FilterRegistrationBean<>(
				new CspNonceFilter()
		);
		registration.setOrder(0);
		registration.addUrlPatterns("/*");
		registration.setName("cacherelayCspNonceFilter");
		return registration;
	}
}
