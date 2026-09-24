package io.github.kxng0109.cacherelay.mcp.config;

import jakarta.annotation.PreDestroy;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.net.http.HttpClient;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * High-performance HTTP/2 connection pooling configuration for downstream MCP server dispatch.
 */
@Configuration
@RequiredArgsConstructor
public class McpHttpClientConfig {

	private final McpGatewayProperties properties;

	private ExecutorService executor;

	/**
	 * Shared HTTP/2 client for all upstream MCP communication. Redirects are never followed (SSRF defense), and virtual
	 * threads carry the I/O.
	 */
	@Bean("mcpHttpClient")
	public HttpClient mcpHttpClient() {
		executor = Executors.newVirtualThreadPerTaskExecutor();
		return HttpClient.newBuilder()
		                 .version(HttpClient.Version.HTTP_2)
		                 .followRedirects(HttpClient.Redirect.NEVER)
		                 .connectTimeout(properties.getClientConnectTimeout())
		                 .executor(executor)
		                 .build();
	}

	/**
	 * Shuts the client executor down on context close (DC-08) so no executor threads outlive the context.
	 */
	@PreDestroy
	void shutdownExecutor() {
		if (executor != null) {
			executor.shutdownNow();
		}
	}
}
