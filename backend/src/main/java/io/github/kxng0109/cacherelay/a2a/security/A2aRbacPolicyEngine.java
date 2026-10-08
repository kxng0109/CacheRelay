package io.github.kxng0109.cacherelay.a2a.security;

import io.github.kxng0109.cacherelay.contracts.RbacPolicy;
import io.github.kxng0109.cacherelay.contracts.VirtualApiKey;

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
		if (apiKey == null) {
			return false;
		}
		return RbacPolicy.isAllowed(agentName, apiKey.allowedAgents(), apiKey.deniedAgents());
	}
}
