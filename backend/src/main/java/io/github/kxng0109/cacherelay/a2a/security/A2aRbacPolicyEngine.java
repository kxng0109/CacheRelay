package io.github.kxng0109.cacherelay.a2a.security;

import java.util.Set;

import io.github.kxng0109.cacherelay.contracts.VirtualApiKey;
import io.github.kxng0109.cacherelay.mcp.security.McpToolRbacPolicyEngine;

import org.springframework.stereotype.Component;

/**
 * Agent-level RBAC/ABAC authorization for A2A proxying.
 *
 * <p>Mirrors the MCP tool-policy semantics: the deny list takes absolute precedence and
 * supports glob patterns, and an empty allow list means "all agents allowed". Patterns
 * are matched with the linear glob matcher (no regex), so adversarial patterns cannot
 * trigger catastrophic backtracking.</p>
 */
@Component
public class A2aRbacPolicyEngine {

	/**
	 * Checks whether a virtual API key may invoke a specific agent.
	 *
	 * @param agentName registered agent name from the proxy path
	 * @param apiKey    authenticated virtual API key
	 * @return true if authorized, false if denied
	 */
	public boolean isAgentAllowed(String agentName, VirtualApiKey apiKey) {
		if (agentName == null || agentName.isBlank() || apiKey == null) {
			return false;
		}
		String target = agentName.trim();

		Set<String> denied = apiKey.deniedAgents();
		if (denied != null && !denied.isEmpty()) {
			for (String pattern : denied) {
				if (McpToolRbacPolicyEngine.matchesPattern(target, pattern)) {
					return false;
				}
			}
		}

		Set<String> allowed = apiKey.allowedAgents();
		if (allowed == null || allowed.isEmpty()) {
			return true;
		}
		for (String pattern : allowed) {
			if (McpToolRbacPolicyEngine.matchesPattern(target, pattern)) {
				return true;
			}
		}
		return false;
	}
}
