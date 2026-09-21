package io.github.kxng0109.cacherelay.a2a.protocol;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import io.github.kxng0109.cacherelay.a2a.config.A2aAgentConfig;
import io.github.kxng0109.cacherelay.a2a.config.A2aGatewayProperties;
import io.github.kxng0109.cacherelay.a2a.registry.A2aAgentRegistry;
import io.github.kxng0109.cacherelay.a2a.resilience.A2aAgentCircuitBreakerManager;
import io.github.kxng0109.cacherelay.a2a.security.A2aRbacPolicyEngine;
import io.github.kxng0109.cacherelay.config.SensitiveString;
import io.github.kxng0109.cacherelay.contracts.SHA256Hash;
import io.github.kxng0109.cacherelay.contracts.VirtualApiKey;
import io.github.kxng0109.cacherelay.security.ratelimit.KeyManagementService;
import jakarta.servlet.http.HttpServletRequest;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.RecordedRequest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * Unit and HTTP-level tests for {@link A2aProxyController} against a MockWebServer
 * upstream: authentication, RBAC, breaker admission, verbatim relay, error
 * mapping, size limits, and card rewriting.
 */
@DisplayName("A2aProxyController")
class A2aProxyControllerTest {

	private static final String AGENT = "research-agent";

	private static final String SEND_BODY = "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"message/send\","
			+ "\"params\":{\"message\":{\"role\":\"user\",\"parts\":[{\"kind\":\"text\",\"text\":\"hi\"}],"
			+ "\"messageId\":\"m-1\"}}}";

	private final ObjectMapper objectMapper = new ObjectMapper();

	private MockWebServer upstream;

	private A2aGatewayProperties properties;

	private A2aAgentRegistry registry;

	private A2aAgentCircuitBreakerManager breakers;

	private A2aProxyController controller;

	private VirtualApiKey apiKey;

	@BeforeEach
	void setUp() throws IOException {
		upstream = new MockWebServer();
		upstream.start();

		apiKey = new VirtualApiKey(
				SHA256Hash.fromRawKey("gw-a2a-test"),
				"gw-",
				"tenant-corp",
				"a2a-test",
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
				Set.of(),
				Set.of()
		);
		KeyManagementService keyManagementService = mock(KeyManagementService.class);
		when(keyManagementService.findByHash(any())).thenReturn(Optional.of(apiKey));

		properties = new A2aGatewayProperties();
		properties.setPublicBaseUrl("https://gateway.example.com/");
		Map<String, A2aAgentConfig> agents = new LinkedHashMap<>();
		agents.put(AGENT, new A2aAgentConfig(
				AGENT,
				upstream.url("/a2a").uri(),
				new SensitiveString("agent-secret"),
				null,
				null,
				null));
		properties.setAgents(agents);

		registry = new A2aAgentRegistry(properties);
		breakers = new A2aAgentCircuitBreakerManager(properties);
		controller = new A2aProxyController(
				properties,
				registry,
				new A2aRbacPolicyEngine(),
				breakers,
				keyManagementService,
				objectMapper,
				HttpClient.newBuilder()
						.connectTimeout(Duration.ofSeconds(2))
						.followRedirects(HttpClient.Redirect.NEVER)
						.build());
	}

	@AfterEach
	void tearDown() throws IOException {
		upstream.shutdown();
	}

	@Test
	@DisplayName("rejects a missing key with 401")
	void rejectsMissingKey() throws Exception {
		ResponseEntity<String> response = controller.relay(AGENT, SEND_BODY, null, request(null, -1L));

		assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
		assertThat(upstream.getRequestCount()).isZero();
	}

	@Test
	@DisplayName("relays a supported method verbatim with agent credentials")
	void relaysVerbatim() throws Exception {
		String upstreamBody = "{\"jsonrpc\":\"2.0\",\"id\":1,\"result\":{\"kind\":\"task\",\"id\":\"t-1\"}}";
		upstream.enqueue(new MockResponse()
				.setResponseCode(200)
				.setHeader("Content-Type", "application/json")
				.setBody(upstreamBody));

		ResponseEntity<String> response = controller.relay(AGENT, SEND_BODY, "0.2", request("Bearer gw-a2a-test", -1L));

		assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
		assertThat(response.getBody()).isEqualTo(upstreamBody);

		RecordedRequest recorded = upstream.takeRequest();
		assertThat(recorded.getPath()).isEqualTo("/a2a");
		assertThat(recorded.getHeader("Authorization")).isEqualTo("Bearer agent-secret");
		assertThat(recorded.getHeader("A2A-Version")).isEqualTo("0.2");
		assertThat(recorded.getHeader("Content-Type")).contains("application/json");
	}

	@Test
	@DisplayName("relays upstream JSON-RPC errors verbatim")
	void relaysUpstreamJsonRpcError() throws Exception {
		String errorBody = "{\"jsonrpc\":\"2.0\",\"id\":1,\"error\":{\"code\":-32001,\"message\":\"bad input\"}}";
		upstream.enqueue(new MockResponse().setResponseCode(500).setBody(errorBody));

		ResponseEntity<String> response = controller.relay(AGENT, SEND_BODY, null, request("Bearer gw-a2a-test", -1L));

		assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
		assertThat(response.getBody()).isEqualTo(errorBody);
	}

	@Test
	@DisplayName("maps a non JSON-RPC upstream failure to -32603")
	void mapsUpstreamFailure() throws Exception {
		upstream.enqueue(new MockResponse().setResponseCode(503).setBody("service down"));

		ResponseEntity<String> response = controller.relay(AGENT, SEND_BODY, null, request("Bearer gw-a2a-test", -1L));

		assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
		assertThat(errorCode(response)).isEqualTo(-32603);
		assertThat(errorMessage(response)).contains("HTTP 503");
	}

	@Test
	@DisplayName("maps a dead upstream to -32603 unavailable")
	void mapsDeadUpstream() throws Exception {
		Map<String, A2aAgentConfig> agents = new LinkedHashMap<>(properties.getAgents());
		agents.put(AGENT, new A2aAgentConfig(
				AGENT, URI.create("http://127.0.0.1:1/a2a"), null, null, null, null));
		properties.setAgents(agents);

		ResponseEntity<String> response = controller.relay(AGENT, SEND_BODY, null, request("Bearer gw-a2a-test", -1L));

		assertThat(errorCode(response)).isEqualTo(-32603);
		assertThat(errorMessage(response)).contains("unavailable");
	}

	@Test
	@DisplayName("answers unsupported methods with -32601 without touching the upstream")
	void unsupportedMethod() throws Exception {
		String body = "{\"jsonrpc\":\"2.0\",\"id\":2,\"method\":\"message/stream\",\"params\":{}}";

		ResponseEntity<String> response = controller.relay(AGENT, body, null, request("Bearer gw-a2a-test", -1L));

		assertThat(errorCode(response)).isEqualTo(-32601);
		assertThat(upstream.getRequestCount()).isZero();
	}

	@Test
	@DisplayName("denies unknown and RBAC-hidden agents identically")
	void deniesUnknownAndForbiddenAgents() throws Exception {
		ResponseEntity<String> unknown = controller.relay("ghost", SEND_BODY, null, request("Bearer gw-a2a-test", -1L));

		VirtualApiKey restricted = new VirtualApiKey(
				apiKey.keyHash(), "gw-", "tenant-corp", "restricted", 100, 1000,
				Set.of(), Set.of(), Set.of(), Set.of(), Set.of(), Set.of(), Set.of(), Set.of(),
				true, true, Instant.now(), VirtualApiKey.normalizeCacheScopes(Set.of()),
				Set.of("other-*"), Set.of());
		KeyManagementService restrictedKeys = mock(KeyManagementService.class);
		when(restrictedKeys.findByHash(any())).thenReturn(Optional.of(restricted));
		A2aProxyController restrictedController = new A2aProxyController(
				properties, registry, new A2aRbacPolicyEngine(), breakers, restrictedKeys,
				objectMapper, HttpClient.newHttpClient());
		ResponseEntity<String> forbidden = restrictedController.relay(
				AGENT, SEND_BODY, null, request("Bearer gw-restricted", -1L));

		assertThat(unknown.getStatusCode()).isEqualTo(HttpStatus.OK);
		assertThat(errorCode(unknown)).isEqualTo(-32603);
		assertThat(errorMessage(unknown)).isEqualTo(errorMessage(forbidden));
		assertThat(upstream.getRequestCount()).isZero();
	}

	@Test
	@DisplayName("accepts notifications with 202 without dispatching")
	void acceptsNotification() throws Exception {
		String body = "{\"jsonrpc\":\"2.0\",\"method\":\"message/send\",\"params\":{}}";

		ResponseEntity<String> response = controller.relay(AGENT, body, null, request("Bearer gw-a2a-test", -1L));

		assertThat(response.getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);
		assertThat(upstream.getRequestCount()).isZero();
	}

	@Test
	@DisplayName("opens the breaker after the failure threshold and denies admission")
	void breakerOpensAfterThreshold() throws Exception {
		properties.setCircuitBreakerFailureThreshold(2);
		upstream.enqueue(new MockResponse().setResponseCode(500).setBody("boom"));
		upstream.enqueue(new MockResponse().setResponseCode(500).setBody("boom"));

		controller.relay(AGENT, SEND_BODY, null, request("Bearer gw-a2a-test", -1L));
		controller.relay(AGENT, SEND_BODY, null, request("Bearer gw-a2a-test", -1L));
		ResponseEntity<String> denied = controller.relay(AGENT, SEND_BODY, null, request("Bearer gw-a2a-test", -1L));

		assertThat(errorCode(denied)).isEqualTo(-32603);
		assertThat(errorMessage(denied)).contains("circuit breaker open");
		assertThat(upstream.getRequestCount()).isEqualTo(2);
	}

	@Test
	@DisplayName("relays the gateway configuration as 503 when disabled")
	void disabledReturnsServiceUnavailable() {
		properties.setEnabled(false);

		ResponseEntity<String> response = controller.relay(AGENT, SEND_BODY, null, request("Bearer gw-a2a-test", -1L));

		assertThat(response.getStatusCode()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
	}

	@Test
	@DisplayName("rewrites the agent card URL to the gateway address")
	void rewritesAgentCard() throws Exception {
		String card = "{\"protocolVersion\":\"0.3\",\"name\":\"Research\",\"url\":\"http://upstream/a2a\","
				+ "\"version\":\"1.0\",\"additionalInterfaces\":[{\"url\":\"http://upstream/a2a\",\"transport\":\"JSONRPC\"}]}";
		upstream.enqueue(new MockResponse().setResponseCode(200).setBody(card));

		ResponseEntity<String> response = controller.agentCard(AGENT, request("Bearer gw-a2a-test", -1L));

		assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
		JsonNode rewritten = objectMapper.readTree(response.getBody());
		assertThat(rewritten.get("url").asString()).isEqualTo("https://gateway.example.com/v1/a2a/" + AGENT);
		assertThat(rewritten.get("additionalInterfaces").get(0).get("url").asString())
				.isEqualTo("https://gateway.example.com/v1/a2a/" + AGENT);
		assertThat(upstream.takeRequest().getHeader("Authorization")).isEqualTo("Bearer agent-secret");
	}

	@Test
	@DisplayName("hides the card of unknown agents and requires a key")
	void cardAccessControl() {
		ResponseEntity<String> anonymous = controller.agentCard(AGENT, request(null, -1L));
		ResponseEntity<String> unknown = controller.agentCard("ghost", request("Bearer gw-a2a-test", -1L));

		assertThat(anonymous.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
		assertThat(unknown.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
	}

	@Test
	@DisplayName("maps an unreachable upstream card to 502")
	void cardUpstreamFailure() throws Exception {
		upstream.enqueue(new MockResponse().setResponseCode(500).setBody("boom"));

		ResponseEntity<String> response = controller.agentCard(AGENT, request("Bearer gw-a2a-test", -1L));

		assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_GATEWAY);
	}

	@Test
	@DisplayName("rejects malformed JSON with -32700 and batches with -32600")
	void rejectsMalformedAndBatch() throws Exception {
		ResponseEntity<String> malformed = controller.relay(
				AGENT, "{not json", null, request("Bearer gw-a2a-test", -1L));
		ResponseEntity<String> batch = controller.relay(
				AGENT, "[{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"message/send\"}]", null,
				request("Bearer gw-a2a-test", -1L));

		assertThat(malformed.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
		assertThat(errorCode(malformed)).isEqualTo(-32700);
		assertThat(batch.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
		assertThat(errorCode(batch)).isEqualTo(-32600);
		assertThat(upstream.getRequestCount()).isZero();
	}

	@Test
	@DisplayName("rejects a body over the configured size limit")
	void rejectsOversizedBody() throws Exception {
		properties.setMaxRequestBytes(16);

		ResponseEntity<String> response = controller.relay(
				AGENT, SEND_BODY, null, request("Bearer gw-a2a-test", -1L));

		assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
		assertThat(errorCode(response)).isEqualTo(-32600);
		assertThat(upstream.getRequestCount()).isZero();
	}

	@Test
	@DisplayName("rejects a non-Bearer and a blank Bearer authorization")
	void rejectsBadAuthorizationShapes() throws Exception {
		ResponseEntity<String> basic = controller.relay(
				AGENT, SEND_BODY, null, request("Basic abc", -1L));
		ResponseEntity<String> blank = controller.relay(
				AGENT, SEND_BODY, null, request("Bearer   ", -1L));

		assertThat(basic.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
		assertThat(blank.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
		assertThat(upstream.getRequestCount()).isZero();
	}

	@Test
	@DisplayName("rejects a request without a method with -32600")
	void rejectsMissingMethod() throws Exception {
		ResponseEntity<String> response = controller.relay(
				AGENT, "{\"jsonrpc\":\"2.0\",\"id\":1}", null, request("Bearer gw-a2a-test", -1L));

		assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
		assertThat(errorCode(response)).isEqualTo(-32600);
	}

	@Test
	@DisplayName("accepts an explicit null id as a notification")
	void acceptsExplicitNullId() throws Exception {
		ResponseEntity<String> response = controller.relay(
				AGENT, "{\"jsonrpc\":\"2.0\",\"id\":null,\"method\":\"message/send\",\"params\":{}}",
				null, request("Bearer gw-a2a-test", -1L));

		assertThat(response.getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);
		assertThat(upstream.getRequestCount()).isZero();
	}

	@Test
	@DisplayName("maps a non-JSON upstream success body to -32603")
	void mapsNonJsonUpstreamBody() throws Exception {
		upstream.enqueue(new MockResponse().setResponseCode(200).setBody("not-json"));

		ResponseEntity<String> response = controller.relay(
				AGENT, SEND_BODY, null, request("Bearer gw-a2a-test", -1L));

		assertThat(errorCode(response)).isEqualTo(-32603);
		assertThat(errorMessage(response)).contains("non-JSON");
	}

	@Test
	@DisplayName("forwards the agent protocol version when the caller omits it")
	void forwardsDefaultVersion() throws Exception {
		upstream.enqueue(new MockResponse().setResponseCode(200)
				.setBody("{\"jsonrpc\":\"2.0\",\"id\":1,\"result\":{}}"));

		controller.relay(AGENT, SEND_BODY, null, request("Bearer gw-a2a-test", -1L));

		assertThat(upstream.takeRequest().getHeader("A2A-Version")).isEqualTo("0.3");
	}

	@Test
	@DisplayName("card URLs are correct without a trailing slash on the base URL")
	void cardUrlWithoutTrailingSlash() throws Exception {
		properties.setPublicBaseUrl("https://gateway.example.com");
		upstream.enqueue(new MockResponse().setResponseCode(200)
				.setBody("{\"protocolVersion\":\"0.3\",\"name\":\"R\",\"url\":\"http://u/a2a\",\"version\":\"1\"}"));

		ResponseEntity<String> response = controller.agentCard(AGENT, request("Bearer gw-a2a-test", -1L));

		assertThat(objectMapper.readTree(response.getBody()).get("url").asString())
				.isEqualTo("https://gateway.example.com/v1/a2a/" + AGENT);
	}

	@Test
	@DisplayName("maps a non-object card body to 502")
	void cardNonObjectBody() throws Exception {
		upstream.enqueue(new MockResponse().setResponseCode(200).setBody("[]"));

		ResponseEntity<String> response = controller.agentCard(AGENT, request("Bearer gw-a2a-test", -1L));

		assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_GATEWAY);
	}

	@Test
	@DisplayName("maps a dead upstream card fetch to 502")
	void cardDeadAgent() {
		Map<String, A2aAgentConfig> agents = new LinkedHashMap<>(properties.getAgents());
		agents.put(AGENT, new A2aAgentConfig(
				AGENT, URI.create("http://127.0.0.1:1/a2a"), null, null, null, null));
		properties.setAgents(agents);

		ResponseEntity<String> response = controller.agentCard(AGENT, request("Bearer gw-a2a-test", -1L));

		assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_GATEWAY);
	}

	private HttpServletRequest request(String authorizationHeader, long contentLength) {
		HttpServletRequest request = mock(HttpServletRequest.class);
		if (authorizationHeader != null) {
			when(request.getHeader("Authorization")).thenReturn(authorizationHeader);
		}
		when(request.getContentLengthLong()).thenReturn(contentLength);
		return request;
	}

	private int errorCode(ResponseEntity<String> response) throws Exception {
		return objectMapper.readTree(response.getBody()).get("error").get("code").asInt();
	}

	private String errorMessage(ResponseEntity<String> response) throws Exception {
		return objectMapper.readTree(response.getBody()).get("error").get("message").asString();
	}
}
