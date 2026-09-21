package io.github.kxng0109.cacherelay.security;

import java.util.List;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

/**
 * Development-only CORS allow-list for the Vite operator UI.
 *
 * <p>Registers a {@code CorsConfigurationSource} named {@code corsConfigurationSource}, which Spring Security
 * picks up automatically without any {@code .cors()} call on the chain. The bean — and therefore the entire CORS
 * integration — exists only under the {@code dev} profile; production has no CORS configuration at all and stays
 * default-deny for cross-origin browser traffic.
 *
 * <p>The single allowed origin is explicit. {@code *} combined with credentials is rejected by
 * {@code CorsConfiguration#validateAllowCredentials()}, so a wildcard is never an option here. The same
 * applies to exposed headers: with credentials the browser treats {@code *} as a literal name, so the
 * rate-limit, cache and budget families the UI reads are listed explicitly instead.
 *
 * @since 1.8.0
 */
@Configuration
@Profile("dev")
public class DevCorsConfig {

	/**
	 * CORS policy for the local Vite dev server.
	 *
	 * <p>Covers the versioned API surface ({@code /v1/**}), which is same-origin
	 * ({@code http://localhost:5173}) in dev only. Actuator endpoints are deliberately
	 * not registered: since SEC-15 they are served exclusively on the loopback-published
	 * management port, which browsers must not scrape. Production keeps no CORS
	 * configuration and stays default-deny for cross-origin browser traffic.</p>
	 *
	 * @return source mapping dev paths to the dev policy
	 */
	@Bean
	CorsConfigurationSource corsConfigurationSource() {
		CorsConfiguration config = new CorsConfiguration();
		config.setAllowedOrigins(List.of("http://localhost:5173"));
		config.setAllowCredentials(true);
		config.setAllowedMethods(List.of("GET", "POST", "PUT", "PATCH", "DELETE", "OPTIONS"));
		config.setAllowedHeaders(List.of(
				"Authorization",
				"Content-Type",
				"X-Admin-Key",
				"X-CacheRelay-Refresh",
				"Idempotency-Key",
				"Mcp-Name",
				"Mcp-Session-Id",
				"MCP-Protocol-Version"));
		config.setMaxAge(3600L);
		config.setExposedHeaders(List.of(
				"X-RateLimit-Limit-RPM",
				"X-RateLimit-Remaining-RPM",
				"X-RateLimit-Reset-RPM",
				"X-RateLimit-Limit-TPM",
				"X-RateLimit-Remaining-TPM",
				"X-RateLimit-Reset-TPM",
				"Retry-After",
				"X-Cache",
				"X-CacheRelay-Similarity-Score",
				"Age",
				"X-Budget-Remaining",
				"X-Budget-Reset",
				"X-Budget-Level",
				"X-Budget-Window",
				"X-Budget-Held-Micros",
				"X-Budget-Subject",
				"X-CacheRelay-Provider",
				"X-CacheRelay-Tried",
				"X-CacheRelay-Audit-Receipt",
				"X-No-Storage",
				"Idempotent-Replayed"));
		UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
		source.registerCorsConfiguration("/v1/**", config);
		return source;
	}
}
