package io.github.kxng0109.cacherelay.mcp.router;

import io.github.kxng0109.cacherelay.mcp.config.McpGatewayProperties;
import io.github.kxng0109.cacherelay.mcp.contracts.McpServerConfig;
import io.github.kxng0109.cacherelay.mcp.protocol.McpLogSanitizer;
import io.github.kxng0109.cacherelay.mcp.security.McpToolRbacPolicyEngine;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Deterministic namespacing and L7 routing engine for Model Context Protocol (MCP) invocations. Resolves namespaced
 * identifiers (e.g. {@code postgres__execute_query}) to the target upstream server.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class McpRouter {

	public static final String NAMESPACE_DELIMITER = "__";

	private final McpGatewayProperties properties;

	/**
	 * Formats a canonical namespaced tool identifier: {@code <server_id>__<tool_name>}.
	 *
	 * @param serverName unique upstream server identifier
	 * @param toolName   native tool identifier
	 * @return federated namespaced tool identifier
	 */
	public static String formatNamespacedName(String serverName, String toolName) {
		if (serverName == null || serverName.isBlank()) {
			return toolName;
		}
		return serverName.trim() + NAMESPACE_DELIMITER + toolName.trim();
	}

	/**
	 * Resolves a target tool invocation name to its corresponding upstream server configuration and native tool name.
	 *
	 * @param requestedToolName tool identifier requested by the client
	 * @return resolved route, or empty if target server is unknown or inactive
	 */
	public Optional<McpResolvedRoute> resolveToolRoute(String requestedToolName) {
		if (requestedToolName == null || requestedToolName.isBlank()) {
			return Optional.empty();
		}
		String trimmed = requestedToolName.trim();
		int delimiterIdx = trimmed.indexOf(NAMESPACE_DELIMITER);

		Map<String, McpServerConfig> servers = properties.getServers();

		// 1. Explicit namespaced route (e.g. "postgres__run_query")
		if (delimiterIdx > 0 && delimiterIdx < trimmed.length() - 2) {
			String serverPrefix = trimmed.substring(0, delimiterIdx);
			String rawToolName = trimmed.substring(delimiterIdx + 2);
			McpServerConfig config = servers.get(serverPrefix);
			if (config != null && config.enabled() && isServerPolicyAllowed(config, rawToolName)) {
				return Optional.of(new McpResolvedRoute(config, rawToolName, trimmed));
			}
			log.warn("MCP routing failed: server prefix '{}' is unknown, disabled, or policy-denied",
					McpLogSanitizer.safe(serverPrefix));
			return Optional.empty();
		}

		// 2. Fallback: single server match if un-namespaced
		List<McpResolvedRoute> candidates = new ArrayList<>();
		for (McpServerConfig config : servers.values()) {
			if (!config.enabled()) {
				continue;
			}
			if (isServerPolicyAllowed(config, trimmed)) {
				candidates.add(new McpResolvedRoute(config, trimmed, formatNamespacedName(config.name(), trimmed)));
			}
		}

		if (candidates.size() == 1) {
			return Optional.of(candidates.get(0));
		}
		if (candidates.size() > 1) {
			log.warn(
					"MCP routing collision: un-namespaced tool '{}' matches multiple servers: {}; refusing to guess",
					McpLogSanitizer.safe(requestedToolName),
					candidates.stream().map(c -> c.serverConfig().name()).toList()
			);
			return Optional.empty();
		}
		return Optional.empty();
	}

	/**
	 * Server-level tool policy: deny list wins absolutely (glob matched), then the allow list
	 * admits (glob matched, empty admits all). Shared linear glob matcher, no regex involved.
	 *
	 * @param config        server configuration carrying the policy sets
	 * @param nativeToolName un-namespaced upstream tool name
	 * @return {@code true} when the server policy admits the tool
	 */
	static boolean isServerPolicyAllowed(McpServerConfig config, String nativeToolName) {
		if (nativeToolName == null || nativeToolName.isBlank()) {
			return false;
		}
		String target = nativeToolName.trim();
		if (config.deniedTools() != null) {
			for (String denied : config.deniedTools()) {
				if (McpToolRbacPolicyEngine.matchesPattern(target, denied)) {
					return false;
				}
			}
		}
		if (config.allowedTools() == null || config.allowedTools().isEmpty()) {
			return true;
		}
		for (String allowed : config.allowedTools()) {
			if (McpToolRbacPolicyEngine.matchesPattern(target, allowed)) {
				return true;
			}
		}
		return false;
	}
}
