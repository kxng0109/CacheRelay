package io.github.kxng0109.cacherelay.a2a.protocol;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpHeaders;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import io.github.kxng0109.cacherelay.a2a.config.A2aAgentConfig;
import io.github.kxng0109.cacherelay.a2a.config.A2aGatewayProperties;
import io.github.kxng0109.cacherelay.a2a.registry.A2aAgentRegistry;
import io.github.kxng0109.cacherelay.a2a.resilience.A2aAgentCircuitBreakerManager;
import io.github.kxng0109.cacherelay.a2a.security.A2aRbacPolicyEngine;
import io.github.kxng0109.cacherelay.config.SensitiveString;
import io.github.kxng0109.cacherelay.contracts.RateLimitDecision;
import io.github.kxng0109.cacherelay.contracts.RejectionReason;
import io.github.kxng0109.cacherelay.contracts.SHA256Hash;
import io.github.kxng0109.cacherelay.contracts.VirtualApiKey;
import io.github.kxng0109.cacherelay.security.ratelimit.KeyManagementService;
import io.github.kxng0109.cacherelay.security.ratelimit.RateLimitEngine;
import io.github.kxng0109.cacherelay.security.ratelimit.RateLimitUnavailableException;
import jakarta.servlet.http.HttpServletRequest;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.RecordedRequest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * Unit and HTTP-level tests for {@link A2aProxyController} against a MockWebServer
 * upstream: authentication, RBAC, breaker admission, RPM budget, verbatim JSON and
 * SSE relay, error mapping, size limits, and card rewriting.
 */
@DisplayName("A2aProxyController")
class A2aProxyControllerTest {

	private static final String AGENT = "research-agent";

	private static final String SEND_BODY = "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"message/send\","
			+ "\"params\":{\"message\":{\"role\":\"user\",\"parts\":[{\"kind\":\"text\",\"text\":\"hi\"}],"
			+ "\"messageId\":\"m-1\"}}}";

	private static final String STREAM_BODY = "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"message/stream\","
			+ "\"params\":{\"message\":{\"role\":\"user\",\"parts\":[{\"kind\":\"text\",\"text\":\"hi\"}],"
			+ "\"messageId\":\"m-1\"}}}";

	private static final String SSE_PAYLOAD =
			"data: {\"jsonrpc\":\"2.0\",\"id\":1,\"result\":{\"kind\":\"task\",\"id\":\"t-1\"}}\n\n"
					+ "data: {\"jsonrpc\":\"2.0\",\"id\":1,\"result\":{\"kind\":\"status-update\","
					+ "\"taskId\":\"t-1\",\"final\":true}}\n\n";

	private final ObjectMapper objectMapper = new ObjectMapper();

	private MockWebServer upstream;

	private A2aGatewayProperties properties;

	private A2aAgentRegistry registry;

	private A2aAgentCircuitBreakerManager breakers;

	private RateLimitEngine rateLimitEngine;

	private KeyManagementService keyManagementService;

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
		keyManagementService = mock(KeyManagementService.class);
		when(keyManagementService.findByHash(any())).thenReturn(Optional.of(apiKey));
		when(keyManagementService.isUsable(any(VirtualApiKey.class))).thenReturn(true);

		rateLimitEngine = mock(RateLimitEngine.class);

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
				rateLimitEngine,
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
		ResponseEntity<StreamingResponseBody> response = controller.relay(AGENT, SEND_BODY, null, request(null, -1L));

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

		ResponseEntity<StreamingResponseBody> response = controller.relay(
				AGENT, SEND_BODY, "0.2", request("Bearer gw-a2a-test", -1L));

		assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
		assertThat(body(response)).isEqualTo(upstreamBody);

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

		ResponseEntity<StreamingResponseBody> response = controller.relay(
				AGENT, SEND_BODY, null, request("Bearer gw-a2a-test", -1L));

		assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
		assertThat(body(response)).isEqualTo(errorBody);
	}

	@Test
	@DisplayName("maps a non JSON-RPC upstream failure to -32603")
	void mapsUpstreamFailure() throws Exception {
		upstream.enqueue(new MockResponse().setResponseCode(503).setBody("service down"));

		ResponseEntity<StreamingResponseBody> response = controller.relay(
				AGENT, SEND_BODY, null, request("Bearer gw-a2a-test", -1L));

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

		ResponseEntity<StreamingResponseBody> response = controller.relay(
				AGENT, SEND_BODY, null, request("Bearer gw-a2a-test", -1L));

		assertThat(errorCode(response)).isEqualTo(-32603);
		assertThat(errorMessage(response)).contains("unavailable");
	}

	@Test
	@DisplayName("answers unsupported methods with -32601 without touching the upstream")
	void unsupportedMethod() throws Exception {
		String body = "{\"jsonrpc\":\"2.0\",\"id\":2,\"method\":\"tasks/resubscribe\",\"params\":{}}";

		ResponseEntity<StreamingResponseBody> response = controller.relay(
				AGENT, body, null, request("Bearer gw-a2a-test", -1L));

		assertThat(errorCode(response)).isEqualTo(-32601);
		assertThat(upstream.getRequestCount()).isZero();
	}

	@Test
	@DisplayName("denies unknown and RBAC-hidden agents identically")
	void deniesUnknownAndForbiddenAgents() throws Exception {
		ResponseEntity<StreamingResponseBody> unknown = controller.relay(
				"ghost", SEND_BODY, null, request("Bearer gw-a2a-test", -1L));

		VirtualApiKey restricted = new VirtualApiKey(
				apiKey.keyHash(), "gw-", "tenant-corp", "restricted", 100, 1000,
				Set.of(), Set.of(), Set.of(), Set.of(), Set.of(), Set.of(), Set.of(), Set.of(),
				true, true, Instant.now(), VirtualApiKey.normalizeCacheScopes(Set.of()),
				Set.of("other-*"), Set.of());
		KeyManagementService restrictedKeys = mock(KeyManagementService.class);
		when(restrictedKeys.findByHash(any())).thenReturn(Optional.of(restricted));
		when(restrictedKeys.isUsable(any(VirtualApiKey.class))).thenReturn(true);
		A2aProxyController restrictedController = new A2aProxyController(
				properties, registry, new A2aRbacPolicyEngine(), breakers, restrictedKeys,
				rateLimitEngine, objectMapper, HttpClient.newHttpClient());
		ResponseEntity<StreamingResponseBody> forbidden = restrictedController.relay(
				AGENT, SEND_BODY, null, request("Bearer gw-restricted", -1L));

		assertThat(unknown.getStatusCode()).isEqualTo(HttpStatus.OK);
		assertThat(errorCode(unknown)).isEqualTo(-32603);
		assertThat(errorMessage(unknown)).isEqualTo(errorMessage(forbidden));
		assertThat(upstream.getRequestCount()).isZero();
	}

	@Test
	@DisplayName("revoked keys are indistinguishable from unknown keys")
	void revokedMatchesUnknown() throws Exception {
		when(keyManagementService.findByHash(any())).thenReturn(Optional.empty());
		ResponseEntity<StreamingResponseBody> unknown = controller.relay(
				AGENT, SEND_BODY, null, request("Bearer gw-a2a-test", -1L));

		VirtualApiKey revoked = new VirtualApiKey(
				apiKey.keyHash(), "gw-", "tenant-corp", "revoked", 100, 1000,
				Set.of(), Set.of(), Set.of(), Set.of(), Set.of(), Set.of(), Set.of(), Set.of(),
				true, false, Instant.now(), VirtualApiKey.normalizeCacheScopes(Set.of()),
				Set.of(), Set.of(), UUID.randomUUID(), true);
		when(keyManagementService.findByHash(any())).thenReturn(Optional.of(revoked));
		when(keyManagementService.isUsable(any(VirtualApiKey.class))).thenReturn(false);
		ResponseEntity<StreamingResponseBody> denied = controller.relay(
				AGENT, SEND_BODY, null, request("Bearer gw-a2a-test", -1L));

		assertThat(denied.getStatusCode()).isEqualTo(unknown.getStatusCode());
		assertThat(body(denied)).isEqualTo(body(unknown));
		assertThat(upstream.getRequestCount()).isZero();
	}

	@Test
	@DisplayName("accepts notifications with 202 without dispatching")
	void acceptsNotification() throws Exception {
		String body = "{\"jsonrpc\":\"2.0\",\"method\":\"message/send\",\"params\":{}}";

		ResponseEntity<StreamingResponseBody> response = controller.relay(
				AGENT, body, null, request("Bearer gw-a2a-test", -1L));

		assertThat(response.getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);
		assertThat(upstream.getRequestCount()).isZero();
	}

	@Test
	@DisplayName("accepts an explicit null id as a notification")
	void acceptsExplicitNullId() throws Exception {
		ResponseEntity<StreamingResponseBody> response = controller.relay(
				AGENT, "{\"jsonrpc\":\"2.0\",\"id\":null,\"method\":\"message/send\",\"params\":{}}",
				null, request("Bearer gw-a2a-test", -1L));

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
		ResponseEntity<StreamingResponseBody> denied = controller.relay(
				AGENT, SEND_BODY, null, request("Bearer gw-a2a-test", -1L));

		assertThat(errorCode(denied)).isEqualTo(-32603);
		assertThat(errorMessage(denied)).contains("circuit breaker open");
		assertThat(upstream.getRequestCount()).isEqualTo(2);
	}

	@Test
	@DisplayName("relays the gateway configuration as 503 when disabled")
	void disabledReturnsServiceUnavailable() {
		properties.setEnabled(false);

		ResponseEntity<StreamingResponseBody> response = controller.relay(
				AGENT, SEND_BODY, null, request("Bearer gw-a2a-test", -1L));

		assertThat(response.getStatusCode()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
	}

	@Test
	@DisplayName("rejects a non-Bearer and a blank Bearer authorization")
	void rejectsBadAuthorizationShapes() throws Exception {
		ResponseEntity<StreamingResponseBody> basic = controller.relay(
				AGENT, SEND_BODY, null, request("Basic abc", -1L));
		ResponseEntity<StreamingResponseBody> blank = controller.relay(
				AGENT, SEND_BODY, null, request("Bearer   ", -1L));

		assertThat(basic.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
		assertThat(blank.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
		assertThat(upstream.getRequestCount()).isZero();
	}

	@Test
	@DisplayName("rejects a request without a method with -32600")
	void rejectsMissingMethod() throws Exception {
		ResponseEntity<StreamingResponseBody> response = controller.relay(
				AGENT, "{\"jsonrpc\":\"2.0\",\"id\":1}", null, request("Bearer gw-a2a-test", -1L));

		assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
		assertThat(errorCode(response)).isEqualTo(-32600);
	}

	@Test
	@DisplayName("maps a non-JSON upstream success body to -32603")
	void mapsNonJsonUpstreamBody() throws Exception {
		upstream.enqueue(new MockResponse().setResponseCode(200).setBody("not-json"));

		ResponseEntity<StreamingResponseBody> response = controller.relay(
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
	@DisplayName("streams upstream SSE byte-for-byte with pass-through headers")
	void streamsUpstreamSseVerbatim() throws Exception {
		upstream.enqueue(new MockResponse()
				.setResponseCode(200)
				.setHeader("Content-Type", "text/event-stream")
				.setBody(SSE_PAYLOAD));

		ResponseEntity<StreamingResponseBody> response = controller.relay(
				AGENT, STREAM_BODY, null, request("Bearer gw-a2a-test", -1L));

		assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
		assertThat(response.getHeaders().getContentType()).isEqualTo(MediaType.TEXT_EVENT_STREAM);
		assertThat(response.getHeaders().getFirst("Cache-Control")).isEqualTo("no-cache");
		assertThat(body(response)).isEqualTo(SSE_PAYLOAD);

		RecordedRequest recorded = upstream.takeRequest();
		assertThat(recorded.getHeader("Accept")).isEqualTo("text/event-stream");
	}

	@Test
	@DisplayName("falls back to JSON relay when the stream answer is not SSE")
	void streamNonSseAnswerFallsBackToJson() throws Exception {
		String jsonBody = "{\"jsonrpc\":\"2.0\",\"id\":1,\"result\":{\"kind\":\"message\"}}";
		upstream.enqueue(new MockResponse()
				.setResponseCode(200)
				.setHeader("Content-Type", "application/json")
				.setBody(jsonBody));

		ResponseEntity<StreamingResponseBody> response = controller.relay(
				AGENT, STREAM_BODY, null, request("Bearer gw-a2a-test", -1L));

		assertThat(response.getHeaders().getContentType()).isEqualTo(MediaType.APPLICATION_JSON);
		assertThat(body(response)).isEqualTo(jsonBody);
	}

	@Test
	@DisplayName("relays a pre-stream failure as a JSON-RPC error, not an SSE stream")
	void streamPreFailureIsJson() throws Exception {
		String errorBody = "{\"jsonrpc\":\"2.0\",\"id\":1,\"error\":{\"code\":-32004,\"message\":\"no streaming\"}}";
		upstream.enqueue(new MockResponse()
				.setResponseCode(400)
				.setHeader("Content-Type", "application/json")
				.setBody(errorBody));

		ResponseEntity<StreamingResponseBody> response = controller.relay(
				AGENT, STREAM_BODY, null, request("Bearer gw-a2a-test", -1L));

		assertThat(response.getHeaders().getContentType()).isEqualTo(MediaType.APPLICATION_JSON);
		assertThat(body(response)).isEqualTo(errorBody);
	}

	@Test
	@DisplayName("a stream exceeding the byte cap is truncated, never buffered whole")
	void streamOverCapTruncates() throws Exception {
		properties.setMaxResultBytes(8);
		upstream.enqueue(new MockResponse()
				.setResponseCode(200)
				.setHeader("Content-Type", "text/event-stream")
				.setBody(SSE_PAYLOAD));

		ResponseEntity<StreamingResponseBody> response = controller.relay(
				AGENT, STREAM_BODY, null, request("Bearer gw-a2a-test", -1L));

		assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
		assertThat(body(response)).isEmpty();
	}

	@Test
	@DisplayName("rejects a body over the configured size limit")
	void rejectsOversizedBody() throws Exception {
		properties.setMaxRequestBytes(16);

		ResponseEntity<StreamingResponseBody> response = controller.relay(
				AGENT, SEND_BODY, null, request("Bearer gw-a2a-test", -1L));

		assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
		assertThat(errorCode(response)).isEqualTo(-32600);
		assertThat(upstream.getRequestCount()).isZero();
	}

	@Test
	@DisplayName("rejects malformed JSON with -32700 and batches with -32600")
	void rejectsMalformedAndBatch() throws Exception {
		ResponseEntity<StreamingResponseBody> malformed = controller.relay(
				AGENT, "{not json", null, request("Bearer gw-a2a-test", -1L));
		ResponseEntity<StreamingResponseBody> batch = controller.relay(
				AGENT, "[{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"message/send\"}]", null,
				request("Bearer gw-a2a-test", -1L));

		assertThat(malformed.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
		assertThat(errorCode(malformed)).isEqualTo(-32700);
		assertThat(batch.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
		assertThat(errorCode(batch)).isEqualTo(-32600);
		assertThat(upstream.getRequestCount()).isZero();
	}

	@Test
	@DisplayName("enforces the key RPM budget on messaging methods with 429 and Retry-After")
	void rateLimitedMessaging() throws Exception {
		when(rateLimitEngine.checkRequestRate(any(), any()))
				.thenReturn(new RateLimitDecision.Rejected(RejectionReason.RPM_EXCEEDED, 7L));

		ResponseEntity<StreamingResponseBody> response = controller.relay(
				AGENT, SEND_BODY, null, request("Bearer gw-a2a-test", -1L));

		assertThat(response.getStatusCode()).isEqualTo(HttpStatus.TOO_MANY_REQUESTS);
		assertThat(response.getHeaders().getFirst("Retry-After")).isEqualTo("7");
		assertThat(errorCode(response)).isEqualTo(-32603);
		assertThat(errorMessage(response)).contains("Rate limit exceeded");
		assertThat(upstream.getRequestCount()).isZero();
	}

	@Test
	@DisplayName("does not rate-limit tasks/get, which is a local read")
	void tasksGetIsNotRateLimited() throws Exception {
		when(rateLimitEngine.checkRequestRate(any(), any()))
				.thenReturn(new RateLimitDecision.Rejected(RejectionReason.RPM_EXCEEDED, 7L));
		upstream.enqueue(new MockResponse().setResponseCode(200)
				.setHeader("Content-Type", "application/json")
				.setBody("{\"jsonrpc\":\"2.0\",\"id\":1,\"result\":{\"kind\":\"task\"}}"));

		ResponseEntity<StreamingResponseBody> response = controller.relay(
				AGENT,
				"{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"tasks/get\",\"params\":{\"id\":\"t-1\"}}",
				null, request("Bearer gw-a2a-test", -1L));

		assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
		assertThat(upstream.getRequestCount()).isEqualTo(1);
	}

	@Test
	@DisplayName("fails closed with 503 when the rate limiter is unavailable")
	void rateLimiterOutageFailsClosed() throws Exception {
		when(rateLimitEngine.checkRequestRate(any(), any()))
				.thenThrow(new RateLimitUnavailableException("redis down"));

		ResponseEntity<StreamingResponseBody> response = controller.relay(
				AGENT, SEND_BODY, null, request("Bearer gw-a2a-test", -1L));

		assertThat(response.getStatusCode()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
		assertThat(errorMessage(response)).contains("Rate limiter unavailable");
		assertThat(upstream.getRequestCount()).isZero();
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

	@Test
	@DisplayName("resolves an attribute-bound key without re-hashing and skips the limiter")
	void attributeBoundKeySkipsLimiter() throws Exception {
		upstream.enqueue(new MockResponse().setResponseCode(200)
				.setBody("{\"jsonrpc\":\"2.0\",\"id\":1,\"result\":{}}"));
		HttpServletRequest attributed = mock(HttpServletRequest.class);
		when(attributed.getAttribute("virtualApiKey")).thenReturn(apiKey);
		when(attributed.getContentLengthLong()).thenReturn(-1L);

		ResponseEntity<StreamingResponseBody> response = controller.relay(AGENT, SEND_BODY, null, attributed);

		assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
		verify(rateLimitEngine, never()).checkRequestRate(any(), any());
	}

	@Test
	@DisplayName("omits the Authorization header when the agent has no credential")
	void agentWithoutCredential() throws Exception {
		Map<String, A2aAgentConfig> agents = new LinkedHashMap<>(properties.getAgents());
		agents.put(AGENT, new A2aAgentConfig(AGENT, upstream.url("/a2a").uri(), null, null, null, null));
		properties.setAgents(agents);
		upstream.enqueue(new MockResponse().setResponseCode(200)
				.setBody("{\"jsonrpc\":\"2.0\",\"id\":1,\"result\":{}}"));

		controller.relay(AGENT, SEND_BODY, null, request("Bearer gw-a2a-test", -1L));

		assertThat(upstream.takeRequest().getHeader("Authorization")).isNull();
	}

	@Test
	@DisplayName("maps a blank upstream failure body to -32603")
	void upstreamBlankBody() throws Exception {
		upstream.enqueue(new MockResponse().setResponseCode(500));

		ResponseEntity<StreamingResponseBody> response = controller.relay(
				AGENT, SEND_BODY, null, request("Bearer gw-a2a-test", -1L));

		assertThat(errorMessage(response)).contains("HTTP 500");
	}

	@Test
	@DisplayName("maps a non-error JSON upstream failure body to -32603")
	void upstreamNonErrorJsonBody() throws Exception {
		upstream.enqueue(new MockResponse().setResponseCode(500).setBody("{\"foo\":1}"));

		ResponseEntity<StreamingResponseBody> response = controller.relay(
				AGENT, SEND_BODY, null, request("Bearer gw-a2a-test", -1L));

		assertThat(errorMessage(response)).contains("HTTP 500");
	}

	@Test
	@DisplayName("maps a blank pre-stream failure body to -32603")
	void streamPreFailureBlankBody() throws Exception {
		upstream.enqueue(new MockResponse().setResponseCode(500));

		ResponseEntity<StreamingResponseBody> response = controller.relay(
				AGENT, STREAM_BODY, null, request("Bearer gw-a2a-test", -1L));

		assertThat(response.getHeaders().getContentType()).isEqualTo(MediaType.APPLICATION_JSON);
		assertThat(errorMessage(response)).contains("HTTP 500");
	}

	@Test
	@DisplayName("caps the buffered pre-stream error body at the configured limit")
	void streamPreFailureBodyCapped() throws Exception {
		properties.setMaxResultBytes(8);
		upstream.enqueue(new MockResponse().setResponseCode(500).setBody("a body far longer than eight bytes"));

		ResponseEntity<StreamingResponseBody> response = controller.relay(
				AGENT, STREAM_BODY, null, request("Bearer gw-a2a-test", -1L));

		assertThat(errorMessage(response)).contains("HTTP 500");
	}

	@Test
	@DisplayName("maps a non-JSON card body to 502")
	void cardNonJsonBody() throws Exception {
		upstream.enqueue(new MockResponse().setResponseCode(200).setBody("not-json"));

		ResponseEntity<String> response = controller.agentCard(AGENT, request("Bearer gw-a2a-test", -1L));

		assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_GATEWAY);
	}

	@Test
	@DisplayName("blank caller version falls back to the agent default")
	void blankCallerVersionFallsBack() throws Exception {
		upstream.enqueue(new MockResponse().setResponseCode(200)
				.setBody("{\"jsonrpc\":\"2.0\",\"id\":1,\"result\":{}}"));

		controller.relay(AGENT, SEND_BODY, "   ", request("Bearer gw-a2a-test", -1L));

		assertThat(upstream.takeRequest().getHeader("A2A-Version")).isEqualTo("0.3");
	}

	@Test
	@DisplayName("card is invisible when disabled and for forbidden tenants")
	void cardDisabledAndForbidden() {
		properties.setEnabled(false);
		assertThat(controller.agentCard(AGENT, request("Bearer gw-a2a-test", -1L)).getStatusCode())
				.isEqualTo(HttpStatus.NOT_FOUND);

		properties.setEnabled(true);
		VirtualApiKey restricted = new VirtualApiKey(
				apiKey.keyHash(), "gw-", "tenant-corp", "restricted", 100, 1000,
				Set.of(), Set.of(), Set.of(), Set.of(), Set.of(), Set.of(), Set.of(), Set.of(),
				true, true, Instant.now(), VirtualApiKey.normalizeCacheScopes(Set.of()),
				Set.of("other-*"), Set.of());
		KeyManagementService restrictedKeys = mock(KeyManagementService.class);
		when(restrictedKeys.findByHash(any())).thenReturn(Optional.of(restricted));
		when(restrictedKeys.isUsable(any(VirtualApiKey.class))).thenReturn(true);
		A2aProxyController restrictedController = new A2aProxyController(
				properties, registry, new A2aRbacPolicyEngine(), breakers, restrictedKeys,
				rateLimitEngine, objectMapper, HttpClient.newHttpClient());

		assertThat(restrictedController.agentCard(AGENT, request("Bearer gw-restricted", -1L)).getStatusCode())
				.isEqualTo(HttpStatus.NOT_FOUND);
	}

	@Test
	@DisplayName("card fetch omits credentials when none configured and ignores non-array interfaces")
	void cardWithoutCredentialAndNonArrayInterfaces() throws Exception {
		Map<String, A2aAgentConfig> agents = new LinkedHashMap<>(properties.getAgents());
		agents.put(AGENT, new A2aAgentConfig(AGENT, upstream.url("/a2a").uri(), null, null, null, null));
		properties.setAgents(agents);
		upstream.enqueue(new MockResponse().setResponseCode(200).setBody(
				"{\"protocolVersion\":\"0.3\",\"name\":\"R\",\"url\":\"http://u/a2a\",\"version\":\"1\","
						+ "\"additionalInterfaces\":{}}"));

		ResponseEntity<String> response = controller.agentCard(AGENT, request("Bearer gw-a2a-test", -1L));

		assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
		assertThat(upstream.takeRequest().getHeader("Authorization")).isNull();
	}

	@Test
	@DisplayName("rejects a declared content length over the cap even when the body is small")
	void rejectsDeclaredContentLengthOverLimit() throws Exception {
		String smallBody = "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"tasks/get\",\"params\":{\"id\":\"t-1\"}}";

		ResponseEntity<StreamingResponseBody> response = controller.relay(
				AGENT, smallBody, null,
				request("Bearer gw-a2a-test", (long) properties.getMaxRequestBytes() + 1L));

		assertThat(response.getStatusCode()).as("declared length over cap answers 400")
				.isEqualTo(HttpStatus.BAD_REQUEST);
		assertThat(errorCode(response)).as("oversize maps to invalid request").isEqualTo(-32600);
		assertThat(upstream.getRequestCount()).as("oversize never reaches upstream").isZero();
	}

	@Test
	@DisplayName("rejects a body that parses to no JSON tree")
	void rejectsNullParsedBody() throws Exception {
		ObjectMapper spyMapper = spy(new ObjectMapper());
		doReturn(null).when(spyMapper).readTree(SEND_BODY);
		A2aProxyController spyController = controllerWith(newHttpClient(), spyMapper);

		ResponseEntity<StreamingResponseBody> response = spyController.relay(
				AGENT, SEND_BODY, null, request("Bearer gw-a2a-test", -1L));

		assertThat(response.getStatusCode()).as("null tree answers 400").isEqualTo(HttpStatus.BAD_REQUEST);
		assertThat(errorCode(response)).as("null tree maps to invalid request").isEqualTo(-32600);
		assertThat(upstream.getRequestCount()).as("unparsed body never reaches upstream").isZero();
	}

	@Test
	@DisplayName("maps an informational upstream status to a transport error")
	void mapsInformationalUpstreamStatus() throws Exception {
		HttpClient stubClient = mock(HttpClient.class);
		stubSend(stubClient, stringUpstream(150, "warming up"));
		A2aProxyController stubController = controllerWith(stubClient, objectMapper);

		ResponseEntity<StreamingResponseBody> response = stubController.relay(
				AGENT, SEND_BODY, null, request("Bearer gw-a2a-test", -1L));

		assertThat(response.getStatusCode()).as("sub-200 upstream answers 200 with an error body")
				.isEqualTo(HttpStatus.OK);
		assertThat(errorMessage(response)).as("informational status surfaces as a transport error")
				.contains("HTTP 150");
		assertThat(upstream.getRequestCount()).as("stubbed client bypasses the network").isZero();
	}

	@Test
	@DisplayName("maps an informational pre-stream status to a JSON-RPC error")
	void mapsInformationalPreStreamStatus() throws Exception {
		HttpClient stubClient = mock(HttpClient.class);
		stubSend(stubClient, streamUpstream(150, "application/json",
				new ByteArrayInputStream("{\"foo\":1}".getBytes(StandardCharsets.UTF_8))));
		A2aProxyController stubController = controllerWith(stubClient, objectMapper);

		ResponseEntity<StreamingResponseBody> response = stubController.relay(
				AGENT, STREAM_BODY, null, request("Bearer gw-a2a-test", -1L));

		assertThat(response.getHeaders().getContentType()).as("pre-stream failure is JSON, not SSE")
				.isEqualTo(MediaType.APPLICATION_JSON);
		assertThat(errorMessage(response)).as("informational pre-stream status surfaces as a transport error")
				.contains("HTTP 150");
		assertThat(upstream.getRequestCount()).as("stubbed client bypasses the network").isZero();
	}

	@Test
	@DisplayName("maps an unreadable pre-stream body to unavailable")
	void mapsUnreadablePreStreamBody() throws Exception {
		HttpClient stubClient = mock(HttpClient.class);
		stubSend(stubClient, streamUpstream(400, "application/json", new InputStream() {
			@Override
			public int read() throws IOException {
				throw new IOException("upstream reset");
			}
		}));
		A2aProxyController stubController = controllerWith(stubClient, objectMapper);

		ResponseEntity<StreamingResponseBody> response = stubController.relay(
				AGENT, STREAM_BODY, null, request("Bearer gw-a2a-test", -1L));

		assertThat(response.getHeaders().getContentType()).as("unreadable pre-stream body is JSON, not SSE")
				.isEqualTo(MediaType.APPLICATION_JSON);
		assertThat(errorMessage(response)).as("unreadable pre-stream body is unavailable")
				.contains("unavailable");
		assertThat(upstream.getRequestCount()).as("stubbed client bypasses the network").isZero();
	}

	@Test
	@DisplayName("tolerates a close failure after a clean end of stream")
	void toleratesCloseFailureAfterCleanEof() throws Exception {
		HttpClient stubClient = mock(HttpClient.class);
		stubSend(stubClient, streamUpstream(200, "text/event-stream", faultyStream(false, new byte[0], true)));
		A2aProxyController stubController = controllerWith(stubClient, objectMapper);

		ResponseEntity<StreamingResponseBody> response = stubController.relay(
				AGENT, STREAM_BODY, null, request("Bearer gw-a2a-test", -1L));

		assertThat(response.getStatusCode()).as("clean EOF still answers 200").isEqualTo(HttpStatus.OK);
		assertThat(response.getHeaders().getContentType()).as("pass-through stays SSE")
				.isEqualTo(MediaType.TEXT_EVENT_STREAM);
		assertThat(body(response)).as("no bytes were streamed").isEmpty();
	}

	@Test
	@DisplayName("suppresses a close failure after a mid-stream read failure")
	void suppressesCloseFailureAfterReadFailure() throws Exception {
		HttpClient stubClient = mock(HttpClient.class);
		stubSend(stubClient, streamUpstream(200, "text/event-stream", faultyStream(true, new byte[0], true)));
		A2aProxyController stubController = controllerWith(stubClient, objectMapper);

		ResponseEntity<StreamingResponseBody> response = stubController.relay(
				AGENT, STREAM_BODY, null, request("Bearer gw-a2a-test", -1L));

		assertThat(response.getStatusCode()).as("mid-stream failure still answers 200 with truncation")
				.isEqualTo(HttpStatus.OK);
		assertThat(body(response)).as("failed stream yields no bytes").isEmpty();
	}

	@Test
	@DisplayName("closes cleanly after a mid-stream read failure")
	void closesCleanlyAfterReadFailure() throws Exception {
		HttpClient stubClient = mock(HttpClient.class);
		stubSend(stubClient, streamUpstream(200, "text/event-stream",
				faultyStream(true, new byte[0], false)));
		A2aProxyController stubController = controllerWith(stubClient, objectMapper);

		ResponseEntity<StreamingResponseBody> response = stubController.relay(
				AGENT, STREAM_BODY, null, request("Bearer gw-a2a-test", -1L));

		assertThat(response.getStatusCode()).as("mid-stream failure still answers 200 with truncation")
				.isEqualTo(HttpStatus.OK);
		assertThat(body(response)).as("failed stream yields no bytes").isEmpty();
	}

	@Test
	@DisplayName("closes cleanly after a client disconnect")
	void closesCleanlyAfterClientDisconnect() throws Exception {
		HttpClient stubClient = mock(HttpClient.class);
		stubSend(stubClient, streamUpstream(200, "text/event-stream",
				faultyStream(false, "data: {\"x\":1}\n\n".getBytes(StandardCharsets.UTF_8), false)));
		A2aProxyController stubController = controllerWith(stubClient, objectMapper);

		ResponseEntity<StreamingResponseBody> response = stubController.relay(
				AGENT, STREAM_BODY, null, request("Bearer gw-a2a-test", -1L));
		response.getBody().writeTo(goneClient());

		assertThat(response.getHeaders().getContentType()).as("disconnect still answers SSE headers")
				.isEqualTo(MediaType.TEXT_EVENT_STREAM);
	}

	@Test
	@DisplayName("propagates a breaker failure on stream completion with a clean close")
	void propagatesBreakerFailureOnCleanClose() throws Exception {
		A2aAgentCircuitBreakerManager failingBreakers = mock(A2aAgentCircuitBreakerManager.class);
		when(failingBreakers.tryAcquire(any())).thenReturn(true);
		doThrow(new IllegalStateException("breaker down")).when(failingBreakers).recordSuccess(any());
		HttpClient stubClient = mock(HttpClient.class);
		stubSend(stubClient, streamUpstream(200, "text/event-stream",
				faultyStream(false, new byte[0], false)));
		A2aProxyController failingController = new A2aProxyController(
				properties, registry, new A2aRbacPolicyEngine(), failingBreakers,
				keyManagementService, rateLimitEngine, objectMapper, stubClient);

		ResponseEntity<StreamingResponseBody> response = failingController.relay(
				AGENT, STREAM_BODY, null, request("Bearer gw-a2a-test", -1L));

		assertThatThrownBy(() -> body(response)).as("breaker failure escapes the stream")
				.isInstanceOf(IllegalStateException.class)
				.hasMessageContaining("breaker down");
	}

	@Test
	@DisplayName("suppresses a close failure behind a breaker failure on stream completion")
	void suppressesCloseFailureBehindBreakerFailure() throws Exception {
		A2aAgentCircuitBreakerManager failingBreakers = mock(A2aAgentCircuitBreakerManager.class);
		when(failingBreakers.tryAcquire(any())).thenReturn(true);
		doThrow(new IllegalStateException("breaker down")).when(failingBreakers).recordSuccess(any());
		HttpClient stubClient = mock(HttpClient.class);
		stubSend(stubClient, streamUpstream(200, "text/event-stream",
				faultyStream(false, new byte[0], true)));
		A2aProxyController failingController = new A2aProxyController(
				properties, registry, new A2aRbacPolicyEngine(), failingBreakers,
				keyManagementService, rateLimitEngine, objectMapper, stubClient);

		ResponseEntity<StreamingResponseBody> response = failingController.relay(
				AGENT, STREAM_BODY, null, request("Bearer gw-a2a-test", -1L));

		Throwable thrown = catchThrowable(() -> body(response));

		assertThat(thrown).as("breaker failure escapes the stream")
				.isInstanceOf(IllegalStateException.class)
				.hasMessageContaining("breaker down");
		assertThat(thrown.getSuppressed()).as("close failure is suppressed, not lost")
				.hasSize(1)
				.allSatisfy(suppressed -> assertThat(suppressed).isInstanceOf(IOException.class));
	}

	@Test
	@DisplayName("maps a pre-headers stream failure to unavailable")
	void mapsPreHeadersStreamFailure() throws Exception {
		HttpClient stubClient = mock(HttpClient.class);
		doThrow(new IOException("connect reset")).when(stubClient).send(any(HttpRequest.class), any());
		A2aProxyController stubController = controllerWith(stubClient, objectMapper);

		ResponseEntity<StreamingResponseBody> response = stubController.relay(
				AGENT, STREAM_BODY, null, request("Bearer gw-a2a-test", -1L));

		assertThat(errorMessage(response)).as("pre-headers stream failure is unavailable")
				.contains("unavailable");
		assertThat(upstream.getRequestCount()).as("stubbed client bypasses the network").isZero();
	}

	@Test
	@DisplayName("a null pre-stream body fails fast instead of answering")
	void nullPreStreamBodyFailsFast() {
		HttpClient stubClient = mock(HttpClient.class);
		try {
			stubSend(stubClient, streamUpstream(400, "application/json", null));
		} catch (Exception setupFailure) {
			throw new IllegalStateException("stub setup failed", setupFailure);
		}
		A2aProxyController stubController = controllerWith(stubClient, objectMapper);

		assertThatThrownBy(() -> stubController.relay(
				AGENT, STREAM_BODY, null, request("Bearer gw-a2a-test", -1L)))
				.as("null pre-stream body fails fast")
				.isInstanceOf(NullPointerException.class);
	}

	@Test
	@DisplayName("suppresses a close failure after a client disconnect")
	void suppressesCloseFailureAfterClientDisconnect() throws Exception {
		HttpClient stubClient = mock(HttpClient.class);
		stubSend(stubClient, streamUpstream(200, "text/event-stream",
				faultyStream(false, "data: {\"x\":1}\n\n".getBytes(StandardCharsets.UTF_8), true)));
		A2aProxyController stubController = controllerWith(stubClient, objectMapper);

		ResponseEntity<StreamingResponseBody> response = stubController.relay(
				AGENT, STREAM_BODY, null, request("Bearer gw-a2a-test", -1L));

		response.getBody().writeTo(goneClient());

		assertThat(response.getHeaders().getContentType()).as("disconnect still answers SSE headers")
				.isEqualTo(MediaType.TEXT_EVENT_STREAM);
	}

	@Test
	@DisplayName("a null upstream stream body fails fast instead of streaming")
	void toleratesNullUpstreamStreamBody() throws Exception {
		HttpClient stubClient = mock(HttpClient.class);
		stubSend(stubClient, streamUpstream(200, "text/event-stream", null));
		A2aProxyController stubController = controllerWith(stubClient, objectMapper);

		ResponseEntity<StreamingResponseBody> response = stubController.relay(
				AGENT, STREAM_BODY, null, request("Bearer gw-a2a-test", -1L));

		assertThat(response.getStatusCode()).as("null stream body still answers 200 headers")
				.isEqualTo(HttpStatus.OK);
		assertThatThrownBy(() -> body(response)).as("null stream body fails fast on first read")
				.isInstanceOf(NullPointerException.class);
	}

	@Test
	@DisplayName("omits the Authorization header for null and blank agent credentials")
	@SuppressWarnings("DataFlowIssue")
	void omitsAuthorizationForDegenerateCredentials() throws Exception {
		Map<String, A2aAgentConfig> nullAgents = new LinkedHashMap<>(properties.getAgents());
		nullAgents.put(AGENT, new A2aAgentConfig(
				AGENT, upstream.url("/a2a").uri(), new SensitiveString(null), null, null, null));
		properties.setAgents(nullAgents);
		upstream.enqueue(new MockResponse().setResponseCode(200)
				.setBody("{\"jsonrpc\":\"2.0\",\"id\":1,\"result\":{}}"));

		controller.relay(AGENT, SEND_BODY, null, request("Bearer gw-a2a-test", -1L));

		assertThat(upstream.takeRequest().getHeader("Authorization"))
				.as("null credential sends no Authorization").isNull();

		Map<String, A2aAgentConfig> blankAgents = new LinkedHashMap<>(properties.getAgents());
		blankAgents.put(AGENT, new A2aAgentConfig(
				AGENT, upstream.url("/a2a").uri(), new SensitiveString("  "), null, null, null));
		properties.setAgents(blankAgents);
		upstream.enqueue(new MockResponse().setResponseCode(200)
				.setBody("{\"jsonrpc\":\"2.0\",\"id\":1,\"result\":{}}"));

		controller.relay(AGENT, SEND_BODY, null, request("Bearer gw-a2a-test", -1L));

		assertThat(upstream.takeRequest().getHeader("Authorization"))
				.as("blank credential sends no Authorization").isNull();
	}

	@Test
	@DisplayName("card omits the Authorization header for null and blank agent credentials")
	@SuppressWarnings("DataFlowIssue")
	void cardOmitsAuthorizationForDegenerateCredentials() throws Exception {
		Map<String, A2aAgentConfig> nullAgents = new LinkedHashMap<>(properties.getAgents());
		nullAgents.put(AGENT, new A2aAgentConfig(
				AGENT, upstream.url("/a2a").uri(), new SensitiveString(null), null, null, null));
		properties.setAgents(nullAgents);
		upstream.enqueue(new MockResponse().setResponseCode(200)
				.setBody("{\"protocolVersion\":\"0.3\",\"name\":\"R\",\"url\":\"http://u/a2a\",\"version\":\"1\"}"));

		ResponseEntity<String> nullResponse = controller.agentCard(AGENT, request("Bearer gw-a2a-test", -1L));

		assertThat(nullResponse.getStatusCode()).as("null credential still fetches the card")
				.isEqualTo(HttpStatus.OK);
		assertThat(upstream.takeRequest().getHeader("Authorization"))
				.as("card fetch with null credential sends no Authorization").isNull();

		Map<String, A2aAgentConfig> blankAgents = new LinkedHashMap<>(properties.getAgents());
		blankAgents.put(AGENT, new A2aAgentConfig(
				AGENT, upstream.url("/a2a").uri(), new SensitiveString("  "), null, null, null));
		properties.setAgents(blankAgents);
		upstream.enqueue(new MockResponse().setResponseCode(200)
				.setBody("{\"protocolVersion\":\"0.3\",\"name\":\"R\",\"url\":\"http://u/a2a\",\"version\":\"1\"}"));

		ResponseEntity<String> blankResponse = controller.agentCard(AGENT, request("Bearer gw-a2a-test", -1L));

		assertThat(blankResponse.getStatusCode()).as("blank credential still fetches the card")
				.isEqualTo(HttpStatus.OK);
		assertThat(upstream.takeRequest().getHeader("Authorization"))
				.as("card fetch with blank credential sends no Authorization").isNull();
	}

	@Test
	@DisplayName("maps an informational card status to 502")
	void mapsInformationalCardStatus() throws Exception {
		HttpClient stubClient = mock(HttpClient.class);
		stubSend(stubClient, stringUpstream(150, "warming up"));
		A2aProxyController stubController = controllerWith(stubClient, objectMapper);

		ResponseEntity<String> response = stubController.agentCard(AGENT, request("Bearer gw-a2a-test", -1L));

		assertThat(response.getStatusCode()).as("informational card status maps to bad gateway")
				.isEqualTo(HttpStatus.BAD_GATEWAY);
		assertThat(upstream.getRequestCount()).as("stubbed client bypasses the network").isZero();
	}

	@Test
	@DisplayName("ignores non-object additional interface entries when rewriting the card")
	void ignoresNonObjectInterfaceEntries() throws Exception {
		upstream.enqueue(new MockResponse().setResponseCode(200).setBody(
				"{\"protocolVersion\":\"0.3\",\"name\":\"R\",\"url\":\"http://u/a2a\",\"version\":\"1\","
						+ "\"additionalInterfaces\":[42,{\"url\":\"http://old\",\"transport\":\"JSONRPC\"}]}"));

		ResponseEntity<String> response = controller.agentCard(AGENT, request("Bearer gw-a2a-test", -1L));

		assertThat(response.getStatusCode()).as("card with mixed interfaces is accepted").isEqualTo(HttpStatus.OK);
		JsonNode rewritten = objectMapper.readTree(response.getBody());
		assertThat(rewritten.get("additionalInterfaces").get(1).get("url").asString())
				.as("object entries are still rewritten")
				.isEqualTo("https://gateway.example.com/v1/a2a/" + AGENT);
	}

	@Test
	@DisplayName("maps an empty upstream success body to an empty-response error")
	void mapsEmptyUpstreamBody() throws Exception {
		HttpClient stubClient = mock(HttpClient.class);
		stubSend(stubClient, stringUpstream(200, null));
		A2aProxyController stubController = controllerWith(stubClient, objectMapper);

		ResponseEntity<StreamingResponseBody> response = stubController.relay(
				AGENT, SEND_BODY, null, request("Bearer gw-a2a-test", -1L));

		assertThat(errorMessage(response)).as("empty upstream body is an empty-response error")
				.contains("empty response");
		assertThat(upstream.getRequestCount()).as("stubbed client bypasses the network").isZero();
	}

	@Test
	@DisplayName("maps a null upstream failure body to a transport error")
	void mapsNullUpstreamErrorBody() throws Exception {
		HttpClient stubClient = mock(HttpClient.class);
		stubSend(stubClient, stringUpstream(500, null));
		A2aProxyController stubController = controllerWith(stubClient, objectMapper);

		ResponseEntity<StreamingResponseBody> response = stubController.relay(
				AGENT, SEND_BODY, null, request("Bearer gw-a2a-test", -1L));

		assertThat(errorMessage(response)).as("null failure body surfaces as a transport error")
				.contains("HTTP 500");
		assertThat(upstream.getRequestCount()).as("stubbed client bypasses the network").isZero();
	}

	@Test
	@DisplayName("maps an unparseable-shaped upstream error body to a transport error")
	void mapsNullParsedUpstreamError() throws Exception {
		String opaqueBody = "{\"ping\":1}";
		ObjectMapper spyMapper = spy(new ObjectMapper());
		doReturn(null).when(spyMapper).readTree(opaqueBody);
		A2aProxyController spyController = controllerWith(newHttpClient(), spyMapper);
		upstream.enqueue(new MockResponse().setResponseCode(500).setBody(opaqueBody));

		ResponseEntity<StreamingResponseBody> response = spyController.relay(
				AGENT, SEND_BODY, null, request("Bearer gw-a2a-test", -1L));

		assertThat(response.getStatusCode()).as("unparseable error body answers 200 with an error body")
				.isEqualTo(HttpStatus.OK);
		assertThat(errorMessage(response)).as("null parsed error surfaces as a transport error")
				.contains("HTTP 500");
	}

	@Test
	@DisplayName("rewrites the card URL even when no public base URL is configured")
	void rewritesCardWithNullBaseUrl() throws Exception {
		properties.setPublicBaseUrl(null);
		upstream.enqueue(new MockResponse().setResponseCode(200)
				.setBody("{\"protocolVersion\":\"0.3\",\"name\":\"R\",\"url\":\"http://u/a2a\",\"version\":\"1\"}"));

		ResponseEntity<String> response = controller.agentCard(AGENT, request("Bearer gw-a2a-test", -1L));

		assertThat(response.getStatusCode()).as("card without a base URL is still served").isEqualTo(HttpStatus.OK);
		assertThat(objectMapper.readTree(response.getBody()).get("url").asString())
				.as("missing base URL degrades to a gateway-relative URL")
				.isEqualTo("/v1/a2a/" + AGENT);
	}

	@Test
	@DisplayName("attribute-bound keys with unusable headers skip the limiter")
	void skipsLimiterForAttributedKeysWithUnusableHeaders() throws Exception {
		upstream.enqueue(new MockResponse().setResponseCode(200)
				.setBody("{\"jsonrpc\":\"2.0\",\"id\":1,\"result\":{}}"));
		upstream.enqueue(new MockResponse().setResponseCode(200)
				.setBody("{\"jsonrpc\":\"2.0\",\"id\":1,\"result\":{}}"));
		HttpServletRequest basicAttr = mock(HttpServletRequest.class);
		when(basicAttr.getAttribute("virtualApiKey")).thenReturn(apiKey);
		when(basicAttr.getHeader("Authorization")).thenReturn("Basic abc");
		when(basicAttr.getContentLengthLong()).thenReturn(-1L);
		HttpServletRequest blankBearerAttr = mock(HttpServletRequest.class);
		when(blankBearerAttr.getAttribute("virtualApiKey")).thenReturn(apiKey);
		when(blankBearerAttr.getHeader("Authorization")).thenReturn("Bearer   ");
		when(blankBearerAttr.getContentLengthLong()).thenReturn(-1L);

		ResponseEntity<StreamingResponseBody> basicResponse = controller.relay(AGENT, SEND_BODY, null, basicAttr);
		ResponseEntity<StreamingResponseBody> blankResponse =
				controller.relay(AGENT, SEND_BODY, null, blankBearerAttr);

		assertThat(basicResponse.getStatusCode()).as("non-Bearer header on an attributed key still relays")
				.isEqualTo(HttpStatus.OK);
		assertThat(blankResponse.getStatusCode()).as("blank Bearer on an attributed key still relays")
				.isEqualTo(HttpStatus.OK);
		verify(rateLimitEngine, never()).checkRequestRate(any(), any());
	}

	private HttpServletRequest request(String authorizationHeader, long contentLength) {
		HttpServletRequest request = mock(HttpServletRequest.class);
		if (authorizationHeader != null) {
			when(request.getHeader("Authorization")).thenReturn(authorizationHeader);
		}
		when(request.getContentLengthLong()).thenReturn(contentLength);
		return request;
	}

	private String body(ResponseEntity<StreamingResponseBody> response) throws Exception {
		if (response.getBody() == null) {
			return "";
		}
		ByteArrayOutputStream out = new ByteArrayOutputStream();
		response.getBody().writeTo(out);
		return out.toString(StandardCharsets.UTF_8);
	}

	private int errorCode(ResponseEntity<StreamingResponseBody> response) throws Exception {
		return objectMapper.readTree(body(response)).get("error").get("code").asInt();
	}

	private String errorMessage(ResponseEntity<StreamingResponseBody> response) throws Exception {
		return objectMapper.readTree(body(response)).get("error").get("message").asString();
	}

	private A2aProxyController controllerWith(HttpClient httpClient, ObjectMapper mapper) {
		return new A2aProxyController(
				properties,
				registry,
				new A2aRbacPolicyEngine(),
				breakers,
				keyManagementService,
				rateLimitEngine,
				mapper,
				httpClient);
	}

	private static HttpClient newHttpClient() {
		return HttpClient.newBuilder()
				.connectTimeout(Duration.ofSeconds(2))
				.followRedirects(HttpClient.Redirect.NEVER)
				.build();
	}

	private static void stubSend(HttpClient stubClient, HttpResponse<?> response) throws Exception {
		doReturn(response).when(stubClient).send(any(HttpRequest.class), any());
	}

	@SuppressWarnings("unchecked")
	private static HttpResponse<String> stringUpstream(int status, String body) {
		HttpResponse<String> response = mock(HttpResponse.class);
		when(response.statusCode()).thenReturn(status);
		doReturn(body).when(response).body();
		return response;
	}

	@SuppressWarnings("unchecked")
	private static HttpResponse<InputStream> streamUpstream(
			int status, String contentType, InputStream body) {
		HttpResponse<InputStream> response = mock(HttpResponse.class);
		when(response.statusCode()).thenReturn(status);
		when(response.headers()).thenReturn(contentHeaders(contentType));
		doReturn(body).when(response).body();
		return response;
	}

	private static HttpHeaders contentHeaders(String contentType) {
		return HttpHeaders.of(Map.of("Content-Type", List.of(contentType)), (name, value) -> true);
	}

	private static InputStream faultyStream(boolean failOnRead, byte[] content, boolean failOnClose) {
		return new InputStream() {
			private int position;

			@Override
			public int read() throws IOException {
				if (failOnRead) {
					throw new IOException("upstream reset");
				}
				if (position >= content.length) {
					return -1;
				}
				return content[position++] & 0xFF;
			}

			@Override
			public void close() throws IOException {
				if (failOnClose) {
					throw new IOException("close failed");
				}
			}
		};
	}

	private static OutputStream goneClient() {
		return new OutputStream() {
			@Override
			public void write(int value) throws IOException {
				throw new IOException("client gone");
			}
		};
	}
}
