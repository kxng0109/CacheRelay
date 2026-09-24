package io.github.kxng0109.cacherelay.mcp.protocol;

import io.github.kxng0109.cacherelay.SharedContainersBase;
import io.github.kxng0109.cacherelay.auth.UserAccount;
import io.github.kxng0109.cacherelay.auth.UserAccountRepository;
import io.github.kxng0109.cacherelay.security.ratelimit.KeyManagementService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Proves the MCP surface passes the default-deny boundary and authenticates itself.
 *
 * <p>The security chain {@code permitAll}s {@code /v1/mcp/**} as a delegated-auth surface (like chat and
 * embeddings); the controller rejects missing or disabled keys with 401 JSON-RPC itself, including the
 * legacy SSE stream, so no unauthenticated caller can hold emitters open. Uses the repo's established
 * live-server pattern with a real key seeded through {@code KeyManagementService}.
 */
@DisplayName("MCP chain pass-through and self-authentication")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class McpChainTest extends SharedContainersBase {

	private static final String TOOLS_LIST = """
			{"jsonrpc":"2.0","id":1,"method":"tools/list","params":{"_meta":\
			{"io.modelcontextprotocol/protocolVersion":"2026-07-28",\
			"io.modelcontextprotocol/clientCapabilities":{"tools":{}}}}}""";

	@DynamicPropertySource
	static void sharedContainers(DynamicPropertyRegistry registry) {
		registry.add("spring.datasource.url", SharedContainersBase::postgresJdbcUrl);
		registry.add("spring.datasource.username", SharedContainersBase::postgresUsername);
		registry.add("spring.datasource.password", SharedContainersBase::postgresPassword);
		registry.add("spring.data.redis.host", SharedContainersBase::redisHost);
		registry.add("spring.data.redis.port", SharedContainersBase::redisPort);
	}

	@LocalServerPort
	private int port;

	@Autowired
	private KeyManagementService keyManagementService;

	@Autowired
	private UserAccountRepository userAccounts;

	@Test
	@DisplayName("tools/list without a key is rejected by the controller, not the chain")
	void toolsListWithoutKeyRejected() throws Exception {
		HttpResponse<String> response = post("/v1/mcp", TOOLS_LIST, null);
		assertThat(response.statusCode()).as("missing-key status").isEqualTo(401);
		assertThat(response.body()).as("missing-key body").contains("-32603").contains("Unauthorized");
	}

	@Test
	@DisplayName("tools/list with an unknown key is rejected")
	void toolsListWithUnknownKeyRejected() throws Exception {
		HttpResponse<String> response = post("/v1/mcp", TOOLS_LIST,
				"Bearer gw-AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA");
		assertThat(response.statusCode()).as("unknown-key status").isEqualTo(401);
	}

	@Test
	@DisplayName("tools/list with a valid key reaches the catalog")
	void toolsListWithValidKeyReachesCatalog() throws Exception {
		UserAccount owner = userAccounts.save(new UserAccount("mcp-chain-owner", null, null, false));
		String plaintext = keyManagementService
				.createKey("mcp-chain-owner", "mcp-chain", 0, 0, Set.of(), Set.of(), Set.of(), Set.of(),
						Set.of(), Set.of(), Set.of(), Set.of(), null, null,
						Set.of(), Set.of(), owner.getId())
				.plaintextKey();
		HttpResponse<String> response = post("/v1/mcp", TOOLS_LIST, "Bearer " + plaintext);
		assertThat(response.statusCode()).as("valid-key status").isEqualTo(200);
		assertThat(response.body()).as("valid-key body").contains("\"tools\"");
	}

	@Test
	@DisplayName("legacy SSE stream without a key is rejected")
	void legacySseWithoutKeyRejected() throws Exception {
		HttpRequest request = HttpRequest.newBuilder()
				.uri(URI.create("http://localhost:" + port + "/v1/mcp/sse"))
				.GET()
				.build();
		HttpResponse<Void> response =
				HttpClient.newHttpClient().send(request, HttpResponse.BodyHandlers.discarding());
		assertThat(response.statusCode()).as("unauthenticated SSE status").isEqualTo(401);
	}

	private HttpResponse<String> post(String path, String json, String authorization) throws Exception {
		HttpRequest.Builder builder = HttpRequest.newBuilder()
				.uri(URI.create("http://localhost:" + port + path))
				.header("Content-Type", "application/json")
				.POST(HttpRequest.BodyPublishers.ofString(json));
		if (authorization != null) {
			builder.header("Authorization", authorization);
		}
		return HttpClient.newHttpClient().send(builder.build(), HttpResponse.BodyHandlers.ofString());
	}
}
