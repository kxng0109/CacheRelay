package io.github.kxng0109.cacherelay.mcp.security;

import io.github.kxng0109.cacherelay.contracts.SHA256Hash;
import io.github.kxng0109.cacherelay.contracts.VirtualApiKey;
import io.github.kxng0109.cacherelay.mcp.contracts.McpPromptDefinition;
import io.github.kxng0109.cacherelay.mcp.contracts.McpResourceDefinition;
import io.github.kxng0109.cacherelay.mcp.contracts.McpToolDefinition;
import io.github.kxng0109.cacherelay.mcp.router.McpAggregatedCatalog;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("MCP Tool RBAC Policy Engine Unit Tests")
class McpToolRbacPolicyEngineTest {

	private McpToolRbacPolicyEngine rbacEngine;

	@BeforeEach
	void setUp() {
		rbacEngine = new McpToolRbacPolicyEngine();
	}

	@Test
	@DisplayName("matchesPattern handles wildcards, single character tokens, and regex metacharacters correctly")
	void matchesPatternScenarios() {
		// Global wildcard
		assertThat(McpToolRbacPolicyEngine.matchesPattern("postgres__run_query", "*")).isTrue();

		// Prefix wildcard
		assertThat(McpToolRbacPolicyEngine.matchesPattern("postgres__run_query", "postgres__*")).isTrue();
		assertThat(McpToolRbacPolicyEngine.matchesPattern("github__list_prs", "postgres__*")).isFalse();

		// Suffix wildcard
		assertThat(McpToolRbacPolicyEngine.matchesPattern("postgres__delete_user", "*:delete_*")).isFalse();
		assertThat(McpToolRbacPolicyEngine.matchesPattern("postgres:delete_user", "*:delete_*")).isTrue();

		// Single character wildcard ?
		assertThat(McpToolRbacPolicyEngine.matchesPattern("tool_1", "tool_?")).isTrue();
		assertThat(McpToolRbacPolicyEngine.matchesPattern("tool_12", "tool_?")).isFalse();

		// Metacharacter escaping
		assertThat(McpToolRbacPolicyEngine.matchesPattern("api.v1__query", "api.v1__*")).isTrue();
		assertThat(McpToolRbacPolicyEngine.matchesPattern("apiXv1__query", "api.v1__*")).isFalse();

		// Null/blank handling
		assertThat(McpToolRbacPolicyEngine.matchesPattern("tool", null)).isFalse();
		assertThat(McpToolRbacPolicyEngine.matchesPattern("tool", "   ")).isFalse();
	}

	@Test
	@SuppressWarnings("DataFlowIssue")
	@DisplayName("matchesPattern guards null text, folds ASCII case, and finishes adversarial input fast")
	void matchesPatternLinearAndNullSafe() {
		assertThat(McpToolRbacPolicyEngine.matchesPattern(null, "postgres__*")).isFalse();
		assertThat(McpToolRbacPolicyEngine.matchesPattern("POSTGRES__RUN_QUERY", "postgres__*")).isTrue();
		assertThat(McpToolRbacPolicyEngine.matchesPattern("", "*")).isTrue();
		assertThat(McpToolRbacPolicyEngine.matchesPattern("", "?")).isFalse();
		assertThat(McpToolRbacPolicyEngine.matchesPattern("tool", "tool?")).isFalse();

		String adversarialText = "a".repeat(10_000);
		long startedAt = System.nanoTime();
		boolean matched = McpToolRbacPolicyEngine.matchesPattern(
				adversarialText, "*a*a*a*a*a*a*a*a*b");
		long elapsedMillis = (System.nanoTime() - startedAt) / 1_000_000;
		assertThat(matched).isFalse();
		assertThat(elapsedMillis).as("linear matcher must not backtrack").isLessThan(2_000L);
	}

	@Test
	@DisplayName("isToolAllowed enforces deny list precedence and allow list restrictions")
	void isToolAllowedEvaluations() {
		VirtualApiKey keyWithDeny = new VirtualApiKey(
				SHA256Hash.fromRawKey("gw-key-1"),
				"gw-",
				"tenant-1",
				"key1",
				100,
				1000,
				Set.of(),
				Set.of(),
				Set.of("postgres__*"),
				Set.of("*:delete_*", "*:drop_*"),
				true,
				Instant.now()
		);

		// Allowed by allow-list and not denied
		assertThat(rbacEngine.isToolAllowed("postgres__run_query", keyWithDeny)).isTrue();
		assertThat(rbacEngine.isToolAllowed("postgres__explain_plan", keyWithDeny)).isTrue();

		// Denied by deny-list even though matches postgres__*
		assertThat(rbacEngine.isToolAllowed("postgres:delete_record", keyWithDeny)).isFalse();
		assertThat(rbacEngine.isToolAllowed("postgres:drop_table", keyWithDeny)).isFalse();

		// Not in allow list
		assertThat(rbacEngine.isToolAllowed("github__list_prs", keyWithDeny)).isFalse();

		// Null parameter checks
		assertThat(rbacEngine.isToolAllowed(null, keyWithDeny)).isFalse();
		assertThat(rbacEngine.isToolAllowed("", keyWithDeny)).isFalse();
		assertThat(rbacEngine.isToolAllowed("tool", null)).isFalse();
	}

	@Test
	@DisplayName("filterCatalog prunes unauthorized tools and handles null catalogs cleanly")
	void filterCatalogScenarios() {
		McpToolDefinition tool1 = new McpToolDefinition("postgres__query", "Query DB", null, null);
		McpToolDefinition tool2 = new McpToolDefinition("github__create_issue", "Create issue", null, null);
		McpAggregatedCatalog globalCatalog = new McpAggregatedCatalog(
				List.of(tool1, tool2),
				List.of(),
				List.of(),
				Instant.now()
		);

		VirtualApiKey pgOnlyKey = new VirtualApiKey(
				SHA256Hash.fromRawKey("gw-key-pg"),
				"gw-",
				"tenant-1",
				"pg-key",
				100,
				1000,
				Set.of(),
				Set.of(),
				Set.of("postgres__*"),
				Set.of(),
				true,
				Instant.now()
		);

		McpAggregatedCatalog filtered = rbacEngine.filterCatalog(globalCatalog, pgOnlyKey);
		assertThat(filtered.tools()).hasSize(1);
		assertThat(filtered.tools().getFirst().name()).isEqualTo("postgres__query");

		// Null handling
		assertThat(rbacEngine.filterCatalog(null, pgOnlyKey).tools()).isEmpty();
		assertThat(rbacEngine.filterCatalog(globalCatalog, null).tools()).isEmpty();
		assertThat(rbacEngine.filterCatalog(globalCatalog, null).resources()).isEmpty();
		assertThat(rbacEngine.filterCatalog(globalCatalog, null).prompts()).isEmpty();
	}

	@Test
	@DisplayName("resource visibility follows URI globs with deny precedence")
	void resourceVisibility() {
		VirtualApiKey key = keyWithResources(Set.of("postgres://*"), Set.of("postgres://secret/*"));

		assertThat(rbacEngine.isResourceVisible("postgres://table/orders", key)).isTrue();
		assertThat(rbacEngine.isResourceVisible("postgres://secret/keys", key)).isFalse();
		assertThat(rbacEngine.isResourceVisible("github://repo/main", key)).isFalse();
		assertThat(rbacEngine.isResourceVisible(null, key)).isFalse();
		assertThat(rbacEngine.isResourceVisible("postgres://table/orders", null)).isFalse();
	}

	@Test
	@DisplayName("prompt visibility follows name globs with deny precedence")
	void promptVisibility() {
		VirtualApiKey key = keyWithPrompts(Set.of(), Set.of("admin_*"));

		assertThat(rbacEngine.isPromptVisible("review_code", key)).isTrue();
		assertThat(rbacEngine.isPromptVisible("admin_reset", key)).isFalse();
		assertThat(rbacEngine.isPromptVisible(null, key)).isFalse();
	}

	@Test
	@DisplayName("filterCatalog prunes hidden resources and prompts")
	void filterCatalogPrunesResourcesAndPrompts() {
		McpResourceDefinition open = new McpResourceDefinition("postgres://table/orders",
				"orders", null, null, null);
		McpResourceDefinition secret = new McpResourceDefinition("postgres://secret/keys",
				"keys", null, null, null);
		McpPromptDefinition review = new McpPromptDefinition("review_code", null, List.of(), null);
		McpPromptDefinition admin = new McpPromptDefinition("admin_reset", null, List.of(), null);
		McpAggregatedCatalog catalog = new McpAggregatedCatalog(
				List.of(),
				List.of(open, secret),
				List.of(review, admin),
				Instant.now());
		VirtualApiKey key = new VirtualApiKey(
				SHA256Hash.fromRawKey("gw-key-rbac"),
				"gw-",
				"tenant-1",
				"rbac-key",
				100,
				1000,
				Set.of(),
				Set.of(),
				Set.of(),
				Set.of(),
				Set.of("postgres://*"),
				Set.of("postgres://secret/*"),
				Set.of(),
				Set.of("admin_*"),
				true,
				true,
				Instant.now());

		McpAggregatedCatalog filtered = rbacEngine.filterCatalog(catalog, key);

		assertThat(filtered.resources()).containsExactly(open);
		assertThat(filtered.prompts()).containsExactly(review);
	}

	@Test
	@DisplayName("blank inputs deny and keys without lists permit everything")
	void blankAndDefaultPermit() {
		VirtualApiKey openKey = new VirtualApiKey(
				SHA256Hash.fromRawKey("gw-key-open"),
				"gw-",
				"tenant-1",
				"open-key",
				100,
				1000,
				Set.of(),
				Set.of(),
				Set.of(),
				Set.of(),
				null,
				null,
				null,
				null,
				true,
				true,
				Instant.now());

		assertThat(rbacEngine.isResourceVisible("   ", openKey)).isFalse();
		assertThat(rbacEngine.isPromptVisible("", openKey)).isFalse();
		assertThat(rbacEngine.isResourceVisible("postgres://table/orders", openKey)).isTrue();
		assertThat(rbacEngine.isPromptVisible("review_code", openKey)).isTrue();
		assertThat(openKey.allowedResources()).isEmpty();
		assertThat(openKey.deniedPrompts()).isEmpty();
	}

	private VirtualApiKey keyWithResources(Set<String> allowed, Set<String> denied) {
		return new VirtualApiKey(
				SHA256Hash.fromRawKey("gw-key-res"),
				"gw-",
				"tenant-1",
				"res-key",
				100,
				1000,
				Set.of(),
				Set.of(),
				Set.of(),
				Set.of(),
				allowed,
				denied,
				Set.of(),
				Set.of(),
				true,
				true,
				Instant.now());
	}

	private VirtualApiKey keyWithPrompts(Set<String> allowed, Set<String> denied) {
		return new VirtualApiKey(
				SHA256Hash.fromRawKey("gw-key-prompt"),
				"gw-",
				"tenant-1",
				"prompt-key",
				100,
				1000,
				Set.of(),
				Set.of(),
				Set.of(),
				Set.of(),
				Set.of(),
				Set.of(),
				allowed,
				denied,
				true,
				true,
				Instant.now());
	}
}
