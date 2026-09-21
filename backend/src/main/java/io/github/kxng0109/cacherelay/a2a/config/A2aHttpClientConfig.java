package io.github.kxng0109.cacherelay.a2a.config;

import java.net.http.HttpClient;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * HTTP client used exclusively for upstream A2A agent calls.
 *
 * <p>Redirects are never followed: an agent redirect must not be able to steer the
 * gateway to an unintended origin. Connect/response timeouts are applied per request
 * from {@link A2aGatewayProperties}.</p>
 */
@Configuration
public class A2aHttpClientConfig {

	/**
	 * @param properties A2A gateway configuration
	 * @return a dedicated client with redirects disabled
	 */
	@Bean("a2aHttpClient")
	HttpClient a2aHttpClient(A2aGatewayProperties properties) {
		return HttpClient.newBuilder()
				.connectTimeout(properties.getClientConnectTimeout())
				.followRedirects(HttpClient.Redirect.NEVER)
				.build();
	}
}
