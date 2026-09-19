package io.github.kxng0109.cacherelay.mcp.security;

import io.github.kxng0109.cacherelay.contracts.VirtualApiKey;
import io.github.kxng0109.cacherelay.mcp.contracts.McpPromptDefinition;
import io.github.kxng0109.cacherelay.mcp.contracts.McpResourceDefinition;
import io.github.kxng0109.cacherelay.mcp.contracts.McpToolDefinition;
import io.github.kxng0109.cacherelay.mcp.router.McpAggregatedCatalog;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Tool-level RBAC/ABAC authorization engine for Model Context Protocol (MCP) tool execution and catalog filtering.
 */
@Component
public class McpToolRbacPolicyEngine {

	/**
	 * Checks whether a virtual API key has permission to invoke a specific tool.
	 *
	 * @param toolName namespaced tool name (e.g. {@code postgres__run_query})
	 * @param apiKey   authenticated virtual API key
	 * @return true if authorized, false if denied
	 */
	public boolean isToolAllowed(String toolName, VirtualApiKey apiKey) {
		if (toolName == null || toolName.isBlank() || apiKey == null) {
			return false;
		}
		String target = toolName.trim();

		// 1. Deny list evaluation takes absolute precedence
		Set<String> denied = apiKey.deniedTools();
		if (denied != null && !denied.isEmpty()) {
			for (String pattern : denied) {
				if (matchesPattern(target, pattern)) {
					return false;
				}
			}
		}

		// 2. Allow list evaluation (empty set = permit all)
		Set<String> allowed = apiKey.allowedTools();
		if (allowed == null || allowed.isEmpty()) {
			return true;
		}

		for (String pattern : allowed) {
			if (matchesPattern(target, pattern)) {
				return true;
			}
		}
		return false;
	}

	/**
	 * Filters the federated MCP catalog, pruning any tools, resources, or prompts that the
	 * virtual key is not authorized to access. Tools match on namespaced tool name, resources
	 * on URI (records carry no server attribution; URI space is server-scoped in practice),
	 * prompts on prompt name.
	 *
	 * @param catalog global aggregated catalog
	 * @param apiKey  authenticated virtual API key
	 * @return filtered catalog containing only permitted entries
	 */
	public McpAggregatedCatalog filterCatalog(McpAggregatedCatalog catalog, VirtualApiKey apiKey) {
		if (catalog == null) {
			return McpAggregatedCatalog.empty();
		}
		if (apiKey == null) {
			return McpAggregatedCatalog.empty();
		}

		List<McpToolDefinition> authorizedTools = catalog.tools()
		                                                 .stream()
		                                                 .filter(t -> isToolAllowed(t.name(), apiKey))
		                                                 .toList();
		List<McpResourceDefinition> visibleResources = catalog.resources()
		                                                      .stream()
		                                                      .filter(r -> isResourceVisible(r.uri(), apiKey))
		                                                      .toList();
		List<McpPromptDefinition> visiblePrompts = catalog.prompts()
		                                                  .stream()
		                                                  .filter(p -> isPromptVisible(p.name(), apiKey))
		                                                  .toList();

		return new McpAggregatedCatalog(
				authorizedTools,
				visibleResources,
				visiblePrompts,
				catalog.fetchedAt()
		);
	}

	/**
	 * Checks whether a virtual API key may see an upstream resource (matched on URI).
	 *
	 * @param uri    resource URI (e.g. {@code postgres://table/schema})
	 * @param apiKey authenticated virtual API key
	 * @return true if visible, false if hidden
	 */
	public boolean isResourceVisible(String uri, VirtualApiKey apiKey) {
		return isAllowed(uri, apiKey, true);
	}

	/**
	 * Checks whether a virtual API key may see a prompt definition.
	 *
	 * @param promptName prompt name
	 * @param apiKey     authenticated virtual API key
	 * @return true if visible, false if hidden
	 */
	public boolean isPromptVisible(String promptName, VirtualApiKey apiKey) {
		return isAllowed(promptName, apiKey, false);
	}

	private boolean isAllowed(String target, VirtualApiKey apiKey, boolean resource) {
		if (target == null || target.isBlank() || apiKey == null) {
			return false;
		}
		String trimmed = target.trim();
		Set<String> denied = resource ? apiKey.deniedResources() : apiKey.deniedPrompts();
		if (!denied.isEmpty()) {
			for (String pattern : denied) {
				if (matchesPattern(trimmed, pattern)) {
					return false;
				}
			}
		}
		Set<String> allowed = resource ? apiKey.allowedResources() : apiKey.allowedPrompts();
		if (allowed.isEmpty()) {
			return true;
		}
		for (String pattern : allowed) {
			if (matchesPattern(trimmed, pattern)) {
				return true;
			}
		}
		return false;
	}

	/**
	 * Evaluates standard glob pattern syntax (* and ?).
	 */
	public static boolean matchesPattern(String text, String globPattern) {
		if (globPattern == null || globPattern.isBlank()) {
			return false;
		}
		String trimmedGlob = globPattern.trim();
		if ("*".equals(trimmedGlob)) {
			return true;
		}
		if (text.equals(trimmedGlob)) {
			return true;
		}

		// Convert glob to regex
		StringBuilder regex = new StringBuilder("^");
		for (int i = 0; i < trimmedGlob.length(); i++) {
			char c = trimmedGlob.charAt(i);
			switch (c) {
				case '*' -> regex.append(".*");
				case '?' -> regex.append(".");
				case '.', '(', ')', '+', '|', '^', '$', '@', '%', '[', ']', '{', '}', '\\' -> {
					regex.append("\\").append(c);
				}
				default -> regex.append(c);
			}
		}
		regex.append("$");
		return Pattern.compile(regex.toString(), Pattern.CASE_INSENSITIVE).matcher(text).matches();
	}
}
