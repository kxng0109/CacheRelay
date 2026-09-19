package io.github.kxng0109.cacherelay.mcp.security;

import io.github.kxng0109.cacherelay.contracts.VirtualApiKey;
import io.github.kxng0109.cacherelay.mcp.contracts.McpPromptDefinition;
import io.github.kxng0109.cacherelay.mcp.contracts.McpResourceDefinition;
import io.github.kxng0109.cacherelay.mcp.contracts.McpToolDefinition;
import io.github.kxng0109.cacherelay.mcp.router.McpAggregatedCatalog;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Set;

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
	 * <p>Matching is against the namespaced prompt name (e.g. {@code server__review_code}),
	 * which is what the catalog emits; key patterns must use the same form.</p>
	 *
	 * @param promptName namespaced prompt name
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
	 * Evaluates standard glob pattern syntax ({@code *} and {@code ?}) with a linear
	 * two-pointer matcher (SEC-06): no regex is built or compiled, so adversarial patterns
	 * cannot trigger catastrophic backtracking (worst case O(n*m), no exponential blowup).
	 *
	 * <p>Case folding is ASCII-only and locale-independent: {@code A}-{@code Z} match their
	 * lowercase forms; every other character (including non-ASCII) must match exactly. All
	 * other characters are literals — {@code .[]()%} carry no special meaning.</p>
	 *
	 * @param text        text to test, possibly {@code null} (never matches)
	 * @param globPattern glob pattern, possibly {@code null} or blank (never matches)
	 * @return true when the whole text matches the pattern
	 */
	public static boolean matchesPattern(String text, String globPattern) {
		if (text == null || globPattern == null || globPattern.isBlank()) {
			return false;
		}
		String pattern = globPattern.trim();
		if ("*".equals(pattern)) {
			return true;
		}
		int textIndex = 0;
		int patternIndex = 0;
		int starIndex = -1;
		int resumeIndex = 0;
		while (textIndex < text.length()) {
			if (patternIndex < pattern.length()
					&& (pattern.charAt(patternIndex) == '?'
					|| asciiEqual(pattern.charAt(patternIndex), text.charAt(textIndex)))) {
				textIndex++;
				patternIndex++;
			} else if (patternIndex < pattern.length() && pattern.charAt(patternIndex) == '*') {
				starIndex = patternIndex++;
				resumeIndex = textIndex;
			} else if (starIndex != -1) {
				patternIndex = starIndex + 1;
				textIndex = ++resumeIndex;
			} else {
				return false;
			}
		}
		while (patternIndex < pattern.length() && pattern.charAt(patternIndex) == '*') {
			patternIndex++;
		}
		return patternIndex == pattern.length();
	}

	/**
	 * Compares two characters with ASCII-only case folding.
	 *
	 * @param patternChar pattern character
	 * @param textChar    text character
	 * @return true when equal ignoring ASCII case
	 */
	private static boolean asciiEqual(char patternChar, char textChar) {
		return patternChar == textChar
				|| asciiLower(patternChar) == asciiLower(textChar);
	}

	/**
	 * Lowercase-folds ASCII uppercase only; every other character is returned unchanged
	 * so non-ASCII text never folds into an ASCII allow rule (homoglyph safety).
	 *
	 * @param value character to fold
	 * @return folded character
	 */
	private static char asciiLower(char value) {
		return value >= 'A' && value <= 'Z' ? (char) (value + ('a' - 'A')) : value;
	}
}
