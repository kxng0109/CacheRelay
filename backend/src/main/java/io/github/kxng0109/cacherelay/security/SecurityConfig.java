package io.github.kxng0109.cacherelay.security;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.core.annotation.Order;
import org.springframework.security.config.ObjectPostProcessor;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.header.HeaderWriterFilter;
import tools.jackson.databind.ObjectMapper;

import io.github.kxng0109.cacherelay.auth.SsoSuccessHandler;
import io.github.kxng0109.cacherelay.auth.StealthAccessDeniedHandler;
import io.github.kxng0109.cacherelay.web.SpaFallbackController;

/**
 * Fail-closed authorization boundary for the gateway.
 *
 * <p>Prior to this config the gateway had no Spring Security on the classpath and
 * relied entirely on {@code FilterRegistrationBean}-registered {@code OncePerRequestFilter} instances. Every one of
 * those filters pinned its URL pattern to {@code /v1/chat/completions}, so {@code POST /v1/embeddings} (and any future
 * route) executed with <b>no authentication, no rate limit, and no budget gate</b> before reaching the upstream
 * provider. There was no framework-enforced invariant that a route must be covered by a gate; coverage was only as good
 * as the most recently updated {@code shouldNotFilter()}.
 *
 * <h2>Design</h2>
 * <p>This config inverts the burden: <b>deny by default, permit explicitly</b>. A
 * request is allowed only when it matches a declared matcher; every other route receives HTTP 403 before any controller
 * or filter runs. Adding a new route can never silently bypass the boundary  -  it is denied until someone declares
 * it.
 *
 * <h2>Filter ordering (verified)</h2>
 * <p>Spring Boot auto-configures the security chain at order
 * {@code SecurityProperties.DEFAULT_FILTER_ORDER = -100}, which runs
 * <em>before</em> the gateway's own filters at orders 0/1/2. The routes whose real
 * authentication lives in those filters are therefore {@code permitAll}ed here so the security chain passes them
 * through and the delegated filter still executes. The security chain never replaces the delegated filters; it is the
 * backstop.
 *
 * <h2>Permitted routes</h2>
 * <ul>
 *   <li><b>Management-port observability (internal):</b> {@code /actuator/health},
 *       {@code /actuator/health/**} (probe subpaths), and {@code /actuator/prometheus}.
 *       Since SEC-15 these paths exist only on the dedicated management port
 *       ({@code gateway.management-port}, default 9091); the app port serves no actuator
 *       endpoint at all, Docker Compose publishes the management port on host loopback
 *       only, and the Kubernetes Service never maps it. The chain is port-agnostic, so
 *       these matchers simply let the internal scraper and probes through.</li>
 *   <li><b>Public docs:</b> {@code /v3/api-docs}, {@code /swagger-ui.html}
 *       (no secrets).</li>
 *   <li><b>Delegated auth:</b> {@code /v1/chat/completions} and
 *       {@code /v1/embeddings} (authenticated, rate-limited, and budget-gated by
 *       {@code KeyAuthFilter}); {@code /v1/models} (virtual-key authenticated inside
 *       {@code ModelController}, unmetered metadata); {@code /v1/admin/**} (master-key or admin-JWT
 *       authenticated by {@code AdminAuthFilter}, stealth-404 on denial);
 *       {@code /v1/mcp/**} (virtual-key authenticated inside
 *       {@code McpStreamableHttpController} with per-tool RBAC); {@code /v1/a2a/**}
 *       (virtual-key authenticated inside {@code A2aProxyController} with per-key agent RBAC).</li>
 *   <li><b>Public discovery:</b> {@code /.well-known/agent-card.json} (the A2A gateway card;
 *       deliberately discloses no agent inventory).</li>
  *   <li><b>Human authentication:</b> {@code /v1/auth/**} (login, redeem, identity;
  *       refresh rotation lives in its own filter) and Spring's OAuth2 login/code
  *       endpoints (SSO entry points).</li>
 *   <li><b>Operator SPA shell:</b> {@code /}, {@code /index.html}, {@code /assets/**},
 *       {@code /error}, and {@code SpaFallbackController#SPA_PATH_PATTERN} (extensionless
 *       non-API routes forward to the shell; reserved first segments stay denied).</li>
 * </ul>
 *
 * <h2>Security posture</h2>
 * <ul>
 *   <li>CSRF disabled  -  the API is stateless bearer-token; CSRF tokens have no
 *       meaning and would only break legitimate clients.</li>
 * <li>Session creation forced to {@code STATELESS}  -  no session state is held,
 *       so there is no session to hijack or fixate.</li>
 *   <li>Security headers are written eagerly (see spring-security#15510): {@code HeaderWriterFilter} otherwise writes
 *       headers both in its {@code finally} block and on response commit, and concurrent writes to Tomcat's
 *       non-thread-safe {@code MimeHeaders} corrupt the recycled response with {@code NullPointerException}s. Eager
 *       writing commits the headers on the dispatch thread before any async handoff.</li>
 *   <li>Default-deny is fail-closed: an unmatched route is refused, never passed
 *       through.</li>
 * </ul>
 *
 * <p>Human authentication (login UI, SSO) attaches later without touching this chain: local accounts arrive as an
 * implementation of {@link DelegatedUserDetailsService}, external providers as additional chains. This bean only
 * declares the seam and suppresses Boot's generated-password {@code UserDetailsService}.</p>
 *
 * @since 1.8.0
 */
@Configuration
@EnableWebSecurity
public class SecurityConfig {

	/**
	 * SSO chain: the OAuth2 Authorization Code + PKCE dance needs a session for the
	 * authorization request, so these endpoints run stateful while the API chain below
	 * stays stateless. Success mints a CacheRelay session (refresh cookie) and redirects
	 * to the SPA with the access token in the URL fragment.
	 *
	 * @param http         security builder
	 * @param successHandler SSO completion handler
	 * @return the SSO chain (evaluated before the fail-closed chain)
	 */
	@Bean
	@Order(1)
	@ConditionalOnExpression("'${SSO_GOOGLE_CLIENT_ID:}${SSO_GITHUB_CLIENT_ID:}${SSO_AZURE_CLIENT_ID:}${SSO_AZURE_B2C_CLIENT_ID:}${SSO_OKTA_CLIENT_ID:}${SSO_GENERIC_CLIENT_ID:}'.length() > 0")
	SecurityFilterChain ssoFilterChain(HttpSecurity http, SsoSuccessHandler successHandler)
			throws Exception {
		http
				.securityMatcher("/oauth2/**", "/login/oauth2/**")
				.authorizeHttpRequests(auth -> auth.anyRequest().authenticated())
				.oauth2Login(oauth2 -> oauth2.successHandler(successHandler))
				.logout(logout -> logout.logoutSuccessUrl("/"))
				.csrf(AbstractHttpConfigurer::disable)
				.sessionManagement(sm -> sm.sessionCreationPolicy(SessionCreationPolicy.IF_REQUIRED));
		return http.build();
	}

	/**
	 * Single fail-closed filter chain. {@code anyRequest().denyAll()} is the terminal rule: every route not listed
	 * above is refused before reaching any controller.
	 */
	@Bean
	SecurityFilterChain filterChain(HttpSecurity http, ObjectMapper objectMapper) throws Exception {
		http
				.authorizeHttpRequests(auth -> auth
						.requestMatchers(
								"/actuator/health",
								"/actuator/health/**",
								"/actuator/prometheus",
								"/.well-known/agent-card.json",
								"/v3/api-docs",
								"/swagger-ui.html"
						).permitAll()
						.requestMatchers(
								"/v1/chat/completions",
								"/v1/embeddings",
								"/v1/models",
								"/v1/admin/**",
								"/v1/mcp/**",
								"/v1/a2a/**"
						).permitAll()
						.requestMatchers(
								"/v1/auth/**",
								"/oauth2/**",
								"/login/oauth2/**"
						).permitAll()
						.requestMatchers(
								"/",
								"/index.html",
								"/assets/**",
								"/error",
								SpaFallbackController.SPA_PATH_PATTERN
						).permitAll()
						.anyRequest().denyAll()
				)
				.exceptionHandling(handling -> handling
						.accessDeniedHandler(new StealthAccessDeniedHandler(objectMapper)))
				.csrf(AbstractHttpConfigurer::disable)
				.sessionManagement(sm -> sm.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
				.headers(headers -> headers.withObjectPostProcessor(new ObjectPostProcessor<HeaderWriterFilter>() {
					@Override
					public HeaderWriterFilter postProcess(HeaderWriterFilter filter) {
						filter.setShouldWriteHeadersEagerly(true);
						return filter;
					}
				}));
		return http.build();
	}
}