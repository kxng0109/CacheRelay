package io.github.kxng0109.cacherelay.mcp.router;

import io.github.kxng0109.cacherelay.config.SensitiveString;
import io.github.kxng0109.cacherelay.mcp.config.McpGatewayProperties;
import io.github.kxng0109.cacherelay.mcp.contracts.McpServerConfig;
import io.github.kxng0109.cacherelay.mcp.contracts.McpTransportType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("MCP Router Unit Tests")
class McpRouterTest {

	private McpGatewayProperties properties;
	private McpRouter router;

	private McpServerConfig postgresServer;
	private McpServerConfig githubServer;
	private McpServerConfig disabledServer;

	@BeforeEach
	void setUp() {
		properties = new McpGatewayProperties();

		postgresServer = new McpServerConfig(
				"postgres",
				McpTransportType.STREAMABLE_HTTP,
				URI.create("http://localhost:8081"),
				new SensitiveString("secret1"),
				null,
				null,
				Set.of("run_query", "explain_plan", "shared_tool"),
				Set.of(),
				Set.of(),
				100,
				true
		);

		githubServer = new McpServerConfig(
				"github",
				McpTransportType.STREAMABLE_HTTP,
				URI.create("http://localhost:8082"),
				new SensitiveString("secret2"),
				null,
				null,
				Set.of("list_prs", "create_issue", "shared_tool"),
				Set.of(),
				Set.of(),
				100,
				true
		);

		disabledServer = new McpServerConfig(
				"disabled_srv",
				McpTransportType.STREAMABLE_HTTP,
				URI.create("http://localhost:8083"),
				null,
				null,
				null,
				Set.of("disabled_tool"),
				Set.of(),
				Set.of(),
				100,
				false
		);

		properties.setServers(Map.of(
				"postgres", postgresServer,
				"github", githubServer,
				"disabled_srv", disabledServer
		));

		router = new McpRouter(properties);
	}

	@Test
	@DisplayName("ambiguous un-namespaced tool resolves empty instead of first-match")
	void ambiguousToolResolvesEmpty() {
		Optional<McpResolvedRoute> route = router.resolveToolRoute("shared_tool");

		assertThat(route).as("ambiguous tool must not route").isEmpty();
	}

	@Test
	@DisplayName("server-level denied tools are not invocable by name")
	void serverDeniedToolsNotInvocable() {
		McpGatewayProperties props = new McpGatewayProperties();
		McpServerConfig denying = new McpServerConfig(
				"postgres",
				McpTransportType.STREAMABLE_HTTP,
				URI.create("http://localhost:8081"),
				null,
				null,
				null,
				Set.of(),
				Set.of("run_query"),
				Set.of(),
				100,
				true
		);
		props.setServers(Map.of("postgres", denying));
		McpRouter denyingRouter = new McpRouter(props);

		assertThat(denyingRouter.resolveToolRoute("postgres__run_query"))
				.as("explicit denied tool").isEmpty();
		assertThat(denyingRouter.resolveToolRoute("run_query"))
				.as("un-namespaced denied tool").isEmpty();
	}

	@Test
	@DisplayName("server-level deny wins over glob allows")
	void serverDenyWinsOverGlobAllow() {
		McpGatewayProperties props = new McpGatewayProperties();
		McpServerConfig guarded = new McpServerConfig(
				"postgres",
				McpTransportType.STREAMABLE_HTTP,
				URI.create("http://localhost:8081"),
				null,
				null,
				null,
				Set.of("run_*"),
				Set.of("run_query"),
				Set.of(),
				100,
				true
		);
		props.setServers(Map.of("postgres", guarded));
		McpRouter guardedRouter = new McpRouter(props);

		assertThat(guardedRouter.resolveToolRoute("postgres__run_query"))
				.as("deny beats glob allow").isEmpty();
		assertThat(guardedRouter.resolveToolRoute("postgres__run_stats"))
				.as("glob allow admits").isPresent();
	}

	@Test
	@DisplayName("server names containing the namespace delimiter are rejected at config load")
	void doubleUnderscoreServerNameRejected() {
		McpGatewayProperties props = new McpGatewayProperties();
		McpServerConfig badName = new McpServerConfig(
				"bad__server",
				McpTransportType.STREAMABLE_HTTP,
				URI.create("http://localhost:8089"),
				null,
				null,
				null,
				Set.of(),
				Set.of(),
				Set.of(),
				100,
				true
		);

		assertThatThrownBy(() -> props.setServers(Map.of("bad__server", badName)))
				.as("delimiter in map key")
				.isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(() -> props.setServers(Map.of("alias", badName)))
				.as("delimiter in config name")
				.isInstanceOf(IllegalArgumentException.class);
	}

	@Test
	@DisplayName("formatNamespacedName combines server and tool identifiers cleanly")
	void formatNamespacedNameScenarios() {
		assertThat(McpRouter.formatNamespacedName("postgres", "run_query")).isEqualTo("postgres__run_query");
		assertThat(McpRouter.formatNamespacedName("  github  ", "  create_issue  ")).isEqualTo("github__create_issue");
		assertThat(McpRouter.formatNamespacedName(null, "tool")).isEqualTo("tool");
		assertThat(McpRouter.formatNamespacedName("", "tool")).isEqualTo("tool");
	}

	@Test
	@DisplayName("resolveToolRoute resolves explicit namespaced tool routes")
	void resolveExplicitNamespacedRoutes() {
		Optional<McpResolvedRoute> pgRoute = router.resolveToolRoute("postgres__run_query");
		assertThat(pgRoute).isPresent();
		assertThat(pgRoute.get().serverConfig().name()).isEqualTo("postgres");
		assertThat(pgRoute.get().rawTargetName()).isEqualTo("run_query");
		assertThat(pgRoute.get().namespacedName()).isEqualTo("postgres__run_query");

		Optional<McpResolvedRoute> ghRoute = router.resolveToolRoute("github__create_issue");
		assertThat(ghRoute).isPresent();
		assertThat(ghRoute.get().serverConfig().name()).isEqualTo("github");
		assertThat(ghRoute.get().rawTargetName()).isEqualTo("create_issue");
	}

	@Test
	@DisplayName("resolveToolRoute rejects unknown or disabled server prefixes")
	void resolveUnknownOrDisabledPrefixes() {
		assertThat(router.resolveToolRoute("mysql__run_query")).isEmpty();
		assertThat(router.resolveToolRoute("disabled_srv__disabled_tool")).isEmpty();
		assertThat(router.resolveToolRoute(null)).isEmpty();
		assertThat(router.resolveToolRoute("   ")).isEmpty();
	}

	@Test
	@DisplayName("resolveToolRoute resolves un-namespaced tools when exactly one server matches")
	void resolveUnnamespacedSingleMatch() {
		Optional<McpResolvedRoute> route = router.resolveToolRoute("create_issue");
		assertThat(route).isPresent();
		assertThat(route.get().serverConfig().name()).isEqualTo("github");
		assertThat(route.get().rawTargetName()).isEqualTo("create_issue");
	}

	@Test
	@DisplayName("resolveToolRoute refuses to guess on naming collisions")
	void resolveUnnamespacedCollision() {
		// "shared_tool" is declared in both postgres and github: fail closed, not first-match.
		Optional<McpResolvedRoute> route = router.resolveToolRoute("shared_tool");
		assertThat(route).isEmpty();
	}
}
