package io.github.kxng0109.cacherelay.a2a.security;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.Set;

import io.github.kxng0109.cacherelay.contracts.SHA256Hash;
import io.github.kxng0109.cacherelay.contracts.VirtualApiKey;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link A2aRbacPolicyEngine}: deny-first precedence, glob
 * matching, allow-all default, and null safety.
 */
@DisplayName("A2aRbacPolicyEngine")
class A2aRbacPolicyEngineTest {

	private final A2aRbacPolicyEngine engine = new A2aRbacPolicyEngine();

	@Test
	@DisplayName("empty allow and deny sets permit every agent")
	void emptySetsPermitAll() {
		VirtualApiKey key = key(Set.of(), Set.of());

		assertThat(engine.isAgentAllowed("research-agent", key)).isTrue();
		assertThat(engine.isAgentAllowed("anything", key)).isTrue();
	}

	@Test
	@DisplayName("deny list takes precedence over the allow list")
	void denyWinsOverAllow() {
		VirtualApiKey key = key(Set.of("research-*"), Set.of("research-agent"));

		assertThat(engine.isAgentAllowed("research-agent", key)).isFalse();
		assertThat(engine.isAgentAllowed("research-other", key)).isTrue();
	}

	@Test
	@DisplayName("non-matching allow list denies the agent")
	void nonMatchingAllowDenies() {
		VirtualApiKey key = key(Set.of("prod-*"), Set.of());

		assertThat(engine.isAgentAllowed("dev-agent", key)).isFalse();
	}

	@Test
	@DisplayName("glob patterns match with ASCII case folding and no regex semantics")
	void globMatching() {
		VirtualApiKey key = key(Set.of("Agent-?"), Set.of());

		assertThat(engine.isAgentAllowed("agent-a", key)).isTrue();
		assertThat(engine.isAgentAllowed("agent-ab", key)).isFalse();
	}

	@Test
	@DisplayName("null inputs are denied")
	void nullInputsDenied() {
		VirtualApiKey key = key(Set.of(), Set.of());

		assertThat(engine.isAgentAllowed(null, key)).isFalse();
		assertThat(engine.isAgentAllowed("  ", key)).isFalse();
		assertThat(engine.isAgentAllowed("research-agent", null)).isFalse();
	}

	private static VirtualApiKey key(Set<String> allowedAgents, Set<String> deniedAgents) {
		return new VirtualApiKey(
				SHA256Hash.fromRawKey("gw-rbac-test"),
				"gw-",
				"tenant-corp",
				"rbac-test",
				100,
				1000,
				Set.of(),
				Set.of(),
				Set.of(),
				Set.of(),
				Set.of(),
				Set.of(),
				Set.of(),
				Set.of(),
				true,
				true,
				Instant.now(),
				VirtualApiKey.normalizeCacheScopes(Set.of()),
				allowedAgents,
				deniedAgents
		);
	}
}
