package io.github.kxng0109.cacherelay.mcp.config;

import io.github.kxng0109.cacherelay.config.SensitiveString;
import io.github.kxng0109.cacherelay.mcp.contracts.McpProtocolVersion;
import io.github.kxng0109.cacherelay.mcp.contracts.McpServerConfig;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

import java.time.Duration;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Configuration properties for the Model Context Protocol (MCP) Security & Tool Governance Gateway.
 *
 * <p>All secrets are injected exclusively from the runtime environment (environment variables, Kubernetes Secrets,
 * AWS Secrets Manager, Azure Key Vault, or HashiCorp Vault). No secret value is ever hardcoded in source. The
 * {@link #hitlSecret} field is validated at startup via {@link Validated}, but only when the gateway is
 * {@link #enabled}: a disabled subsystem never fails startup over its secret (MCP-B36).</p>
 *
 * @since 1.4.0
 */
@Getter
@Setter
@Validated
@HitlSecretRequiredWhenEnabled
@ConfigurationProperties(prefix = "gateway.mcp")
public class McpGatewayProperties {

	/**
	 * Master toggle for the MCP gateway subsystem.
	 */
	private boolean enabled = true;

	/**
	 * Registered upstream MCP servers, keyed by server name/prefix.
	 */
	private Map<String, McpServerConfig> servers = new LinkedHashMap<>();

	/**
	 * Default MCP protocol version for clients omitting explicit version headers.
	 */
	private String defaultProtocolVersion = McpProtocolVersion.LATEST;

	/**
	 * In-memory L0 Caffeine cache TTL for aggregated tool catalogs.
	 */
	private Duration catalogCacheTtl = Duration.ofMinutes(5);

	/**
	 * Maximum entries in the in-memory L0 Caffeine catalog cache.
	 */
	@Min(1)
	@Max(10_000)
	private int catalogCacheMaximumSize = 16;

	/**
	 * Legacy SSE endpoint emitter lifetime in minutes.
	 */
	@Min(1)
	@Max(1_440)
	private int legacySseEmitterTimeoutMinutes = 30;

	/**
	 * Background catalog refresh interval / cron.
	 */
	private String catalogRefreshCron = "0 */5 * * * *";

	/**
	 * Expiration TTL for Human-in-the-Loop (HITL) resumption tokens.
	 */
	private Duration hitlSuspensionTtl = Duration.ofSeconds(300);

	/**
	 * 256-bit secret key used for AEAD encryption of HITL resumption tokens.
	 *
	 * <p>Must be injected via the {@code GATEWAY_MCP_HITL_SECRET} environment variable (or equivalent
	 * secret-manager injection). Must be at least 32 bytes when decoded. Required only when the gateway
	 * is enabled (see {@link HitlSecretRequiredWhenEnabled}); a disabled gateway starts without it.</p>
	 */
	private SensitiveString hitlSecret;

	/**
	 * Maximum bytes buffered for one tools/call upstream result body (PERF-11).
	 * Larger results fail fast instead of OOMing the heap.
	 */
	private int maxResultBytes = 1024 * 1024; // 1 MB

	/**
	 * Whether to enable backwards-compatible legacy dual-endpoint SSE transport (GET /mcp/sse + POST /mcp/message).
	 */
	private boolean allowLegacySse = true;

	/**
	 * Public base URL for absolute elicitation links (MCP-B33): the MCP spec mandates only
	 * "a valid URL" and is silent on absolute vs relative, so absoluteness is an operator
	 * choice, not a spec requirement. When set (e.g. {@code https://gateway.example.com}) the
	 * {@code elicitation/create} payload carries an absolute link for remote clients; blank keeps
	 * the relative path for same-origin console use. Never derived from request headers (cf. ADM-B02).
	 */
	private String publicBaseUrl = "";

	/**
	 * Upstream MCP server circuit breaker failure threshold.
	 */
	private int circuitBreakerFailureThreshold = 3;

	/**
	 * Upstream MCP server circuit breaker cooldown duration.
	 */
	private Duration circuitBreakerCooldown = Duration.ofSeconds(30);

	/**
	 * Upstream HTTP client connect timeout.
	 */
	private Duration clientConnectTimeout = Duration.ofSeconds(5);

	/**
	 * Sets the registered upstream MCP servers. Server map keys and config names must not
	 * contain the {@code __} namespace delimiter: namespaced tool identifiers split on the
	 * first delimiter, so a delimiter inside a name is non-injective and routes ambiguously.
	 *
	 * @param servers servers keyed by name/prefix
	 * @throws IllegalArgumentException when any key or name contains {@code __}
	 */
	public void setServers(Map<String, McpServerConfig> servers) {
		if (servers != null) {
			for (Map.Entry<String, McpServerConfig> entry : servers.entrySet()) {
				if (entry.getKey() != null && entry.getKey().contains("__")) {
					throw new IllegalArgumentException(
							"MCP server key must not contain the '__' namespace delimiter: " + entry.getKey());
				}
				if (entry.getValue() != null && entry.getValue().name() != null
						&& entry.getValue().name().contains("__")) {
					throw new IllegalArgumentException(
							"MCP server name must not contain the '__' namespace delimiter: "
									+ entry.getValue().name());
				}
			}
		}
		this.servers = servers == null
				? Map.of()
				: Collections.unmodifiableMap(new LinkedHashMap<>(servers));
	}
}
