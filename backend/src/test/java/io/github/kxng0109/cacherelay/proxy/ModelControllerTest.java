package io.github.kxng0109.cacherelay.proxy;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import io.github.kxng0109.cacherelay.contracts.FailoverStrategy;
import io.github.kxng0109.cacherelay.contracts.GatewayProperties;
import io.github.kxng0109.cacherelay.contracts.ModelAlias;
import io.github.kxng0109.cacherelay.contracts.ProviderRef;
import io.github.kxng0109.cacherelay.contracts.SHA256Hash;
import io.github.kxng0109.cacherelay.contracts.VirtualApiKey;
import io.github.kxng0109.cacherelay.security.ratelimit.KeyManagementService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockHttpServletRequest;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit tests for the OpenAI-compatible model catalog.
 */
@DisplayName("ModelController")
class ModelControllerTest {

	private GatewayProperties gatewayProperties;
	private KeyManagementService keys;
	private ModelController controller;

	@BeforeEach
	void setUp() {
		gatewayProperties = new GatewayProperties();
		gatewayProperties.setAliases(Map.of(
				"gpt-56-luna", new ModelAlias(
						List.of(new ProviderRef("openai", null)), FailoverStrategy.SEQUENTIAL),
				"claude-sonnet-5", new ModelAlias(
						List.of(new ProviderRef("anthropic", null)), FailoverStrategy.SEQUENTIAL)));
		keys = mock(KeyManagementService.class);
		controller = new ModelController(gatewayProperties, keys);
		when(keys.findByHash(any())).thenReturn(Optional.of(key(true)));
		when(keys.isUsable(any(VirtualApiKey.class))).thenReturn(true);
	}

	@Test
	@DisplayName("lists aliases sorted with provider owners")
	@SuppressWarnings("unchecked")
	void listsAliasesSorted() {
		ResponseEntity<Map<String, Object>> listed = controller.listModels(bearerRequest("gw-test-key"));

		assertThat(listed.getStatusCode()).isEqualTo(HttpStatus.OK);
		assertThat(listed.getBody().get("object")).isEqualTo("list");
		List<Map<String, Object>> data =
				(List<Map<String, Object>>) listed.getBody().get("data");
		assertThat(data).hasSize(2);
		assertThat(data.get(0).get("id")).isEqualTo("claude-sonnet-5");
		assertThat(data.get(0).get("owned_by")).isEqualTo("anthropic");
		assertThat(data.get(1).get("id")).isEqualTo("gpt-56-luna");
		assertThat(data.get(1).get("owned_by")).isEqualTo("openai");
		assertThat(data.get(0).get("object")).isEqualTo("model");
		assertThat((Long) data.get(0).get("created")).isPositive();
	}

	@Test
	@DisplayName("missing, unknown, and disabled keys are 401")
	void rejectsBadKeys() {		assertThat(controller.listModels(new MockHttpServletRequest()).getStatusCode())
				.isEqualTo(HttpStatus.UNAUTHORIZED);

		when(keys.findByHash(any())).thenReturn(Optional.empty());
		assertThat(controller.listModels(bearerRequest("gw-bogus")).getStatusCode())
				.isEqualTo(HttpStatus.UNAUTHORIZED);

		when(keys.findByHash(any())).thenReturn(Optional.of(key(false)));
		when(keys.isUsable(any(VirtualApiKey.class))).thenReturn(false);
		assertThat(controller.listModels(bearerRequest("gw-off")).getStatusCode())
				.isEqualTo(HttpStatus.UNAUTHORIZED);
	}

	@Test
	@DisplayName("empty aliases list empty data")
	void emptyAliasesEmptyData() {
		gatewayProperties.setAliases(Map.of());

		ResponseEntity<Map<String, Object>> empty = controller.listModels(bearerRequest("gw-test-key"));

		assertThat(empty.getStatusCode()).isEqualTo(HttpStatus.OK);
		assertThat(empty.getBody().get("data")).isEqualTo(List.of());
	}

	@Test
	@DisplayName("session JWT plus default act-as key lists models")
	@SuppressWarnings("unchecked")
	void sessionWithDefaultActAsKeyListsModels() {
		when(keys.findByHash(any())).thenReturn(Optional.empty());
		when(keys.resolveActAsSelf(any(), any())).thenReturn(Optional.of(key(true)));
		MockHttpServletRequest request = bearerRequest("session-jwt");
		request.addHeader("X-Act-As-Key", "default");

		ResponseEntity<Map<String, Object>> response = controller.listModels(request);

		assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
		assertThat(response.getBody().get("object")).isEqualTo("list");
		List<Map<String, Object>> data =
				(List<Map<String, Object>>) response.getBody().get("data");
		assertThat(data).hasSize(2);
		verify(keys).resolveActAsSelf("session-jwt", "default");
	}

	@Test
	@DisplayName("session JWT with unknown act-as key is 401")
	void sessionWithUnknownActAsKeyIs401() {
		when(keys.findByHash(any())).thenReturn(Optional.empty());
		when(keys.resolveActAsSelf(any(), any())).thenReturn(Optional.empty());
		MockHttpServletRequest request = bearerRequest("session-jwt");
		request.addHeader("X-Act-As-Key", "deadbeef");

		assertThat(controller.listModels(request).getStatusCode())
				.isEqualTo(HttpStatus.UNAUTHORIZED);
	}

	@Test
	@DisplayName("session JWT without act-as header is 401 without resolution")
	void sessionWithoutActAsHeaderIs401() {
		when(keys.findByHash(any())).thenReturn(Optional.empty());

		assertThat(controller.listModels(bearerRequest("session-jwt")).getStatusCode())
				.isEqualTo(HttpStatus.UNAUTHORIZED);
		verify(keys, never()).resolveActAsSelf(any(), any());
	}

	@Test
	@DisplayName("direct key hit never consults act-as resolution")
	void directKeySkipsActAsResolution() {
		ResponseEntity<Map<String, Object>> response =
				controller.listModels(bearerRequest("gw-test-key"));

		assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
		verify(keys, never()).resolveActAsSelf(any(), any());
	}

	@Test
	@DisplayName("non-Bearer credentials are 401 without a lookup")
	void rejectsNonBearer() {
		MockHttpServletRequest request = new MockHttpServletRequest("GET", "/v1/models");
		request.addHeader("Authorization", "Basic dXNlcjpwYXNz");

		ResponseEntity<Map<String, Object>> response = controller.listModels(request);

		assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
		verify(keys, never()).findByHash(any());
	}

	@Test
	@DisplayName("aliases without a chain owned by the gateway itself")
	@SuppressWarnings("unchecked")
	void emptyChainOwnedByGateway() {
		gatewayProperties.setAliases(Map.of("lonely", new ModelAlias(List.of(), null)));

		ResponseEntity<Map<String, Object>> response = controller.listModels(bearerRequest("gw-test-key"));

		assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
		List<Map<String, Object>> data =
				(List<Map<String, Object>>) response.getBody().get("data");
		assertThat(data).hasSize(1);
		assertThat(data.getFirst().get("owned_by")).isEqualTo("cacherelay");
	}

	private MockHttpServletRequest bearerRequest(String token) {
		MockHttpServletRequest request = new MockHttpServletRequest("GET", "/v1/models");
		request.addHeader("Authorization", "Bearer " + token);
		return request;
	}

	private VirtualApiKey key(boolean enabled) {
		return new VirtualApiKey(
				SHA256Hash.fromRawKey("gw-test-key-1234567890abcdef"),
				"gw-",
				"tenant-1",
				"test-key",
				60,
				100000,
				Set.of(),
				Set.of(),
				Set.of(),
				Set.of(),
				enabled,
				Instant.now());
	}
}
