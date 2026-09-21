package io.github.kxng0109.cacherelay.admin;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.net.URI;
import java.time.Duration;
import java.util.List;
import java.util.Map;

import io.github.kxng0109.cacherelay.admin.dto.ProviderListResponse;
import io.github.kxng0109.cacherelay.admin.dto.ProviderStatusResponse;
import io.github.kxng0109.cacherelay.config.SensitiveString;
import io.github.kxng0109.cacherelay.contracts.FailoverStrategy;
import io.github.kxng0109.cacherelay.contracts.GatewayProperties;
import io.github.kxng0109.cacherelay.contracts.ModelAlias;
import io.github.kxng0109.cacherelay.contracts.ProviderConfig;
import io.github.kxng0109.cacherelay.contracts.ProviderRef;
import io.github.kxng0109.cacherelay.contracts.ProviderType;
import io.github.kxng0109.cacherelay.proxy.failover.CircuitBreaker;
import io.github.kxng0109.cacherelay.proxy.failover.CircuitBreakerFactory;

import org.assertj.core.groups.Tuple;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

/**
 * Unit tests for {@link AdminProviderController}: provider rows join the bound
 * configuration with credential presence, live circuit state, and alias
 * reference counts. Authentication and audit live in {@link AdminAuthFilter}
 * and are not exercised here.
 */
@DisplayName("AdminProviderController")
class AdminProviderControllerTest {

	private CircuitBreakerFactory circuitBreakerFactory;

	private AdminProviderController controller;

	@BeforeEach
	void setUp() {
		circuitBreakerFactory = mock(CircuitBreakerFactory.class);
		GatewayProperties properties = new GatewayProperties();
		properties.setProviders(Map.of(
				"openai", new ProviderConfig("openai", ProviderType.OPENAI,
						URI.create("https://api.openai.com"), new SensitiveString("sk-test"),
						Duration.ofSeconds(5), Duration.ofSeconds(60)),
				"groq", new ProviderConfig("groq", ProviderType.OPENAI,
						URI.create("https://api.groq.com/openai/v1"), new SensitiveString(""),
						Duration.ofSeconds(5), Duration.ofSeconds(60)),
				"local", new ProviderConfig("local", ProviderType.OLLAMA,
						URI.create("http://localhost:11434"), null,
						Duration.ofSeconds(3), Duration.ofSeconds(120)),
				"minimal", new ProviderConfig("minimal", null, null, null, null, null, null)));
		properties.setAliases(Map.of(
				"fast", new ModelAlias(List.of(
						new ProviderRef("openai", "gpt-5.6-luna"),
						new ProviderRef("groq", null)), FailoverStrategy.SEQUENTIAL),
				"backup", new ModelAlias(List.of(new ProviderRef("openai", null)),
						FailoverStrategy.RACE)));
		CircuitBreaker openaiBreaker = breaker(CircuitBreaker.State.CLOSED);
		CircuitBreaker groqBreaker = breaker(CircuitBreaker.State.OPEN);
		CircuitBreaker localBreaker = breaker(CircuitBreaker.State.HALF_OPEN);
		CircuitBreaker minimalBreaker = breaker(CircuitBreaker.State.CLOSED);
		when(circuitBreakerFactory.get("openai")).thenReturn(openaiBreaker);
		when(circuitBreakerFactory.get("groq")).thenReturn(groqBreaker);
		when(circuitBreakerFactory.get("local")).thenReturn(localBreaker);
		when(circuitBreakerFactory.get("minimal")).thenReturn(minimalBreaker);
		controller = new AdminProviderController(properties, circuitBreakerFactory);
	}

	@Test
	@DisplayName("lists every configured provider ordered by name")
	void listsProvidersOrderedByName() {
		ResponseEntity<ProviderListResponse> response = controller.listProviders();

		assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
		assertThat(response.getBody()).isNotNull();
		assertThat(response.getBody().providers()).extracting(ProviderStatusResponse::name)
				.containsExactly("groq", "local", "minimal", "openai");
	}

	@Test
	@DisplayName("reports credential presence without ever exposing the key")
	void reportsCredentialPresence() {
		ProviderListResponse body = controller.listProviders().getBody();

		assertThat(body).isNotNull();
		assertThat(body.providers())
				.filteredOn(provider -> provider.name().equals("openai"))
				.singleElement()
				.satisfies(provider -> {
					assertThat(provider.keyConfigured()).isTrue();
					assertThat(provider.toString()).doesNotContain("sk-test");
				});
		assertThat(body.providers())
				.filteredOn(provider -> provider.name().equals("groq"))
				.singleElement()
				.satisfies(provider -> assertThat(provider.keyConfigured()).isFalse());
		assertThat(body.providers())
				.filteredOn(provider -> provider.name().equals("local"))
				.singleElement()
				.satisfies(provider -> assertThat(provider.keyConfigured()).isFalse());
	}

	@Test
	@DisplayName("reports live circuit state per provider")
	void reportsCircuitState() {
		ProviderListResponse body = controller.listProviders().getBody();

		assertThat(body).isNotNull();
		assertThat(body.providers()).extracting(ProviderStatusResponse::name,
						ProviderStatusResponse::circuitState)
				.containsExactly(
						Tuple.tuple("groq", "OPEN"),
						Tuple.tuple("local", "HALF_OPEN"),
						Tuple.tuple("minimal", "CLOSED"),
						Tuple.tuple("openai", "CLOSED"));
	}

	@Test
	@DisplayName("counts alias chain step references per provider")
	void countsAliasReferences() {
		ProviderListResponse body = controller.listProviders().getBody();

		assertThat(body).isNotNull();
		assertThat(body.providers())
				.filteredOn(provider -> provider.name().equals("openai"))
				.singleElement()
				.satisfies(provider -> assertThat(provider.aliasReferences()).isEqualTo(2));
		assertThat(body.providers())
				.filteredOn(provider -> provider.name().equals("groq"))
				.singleElement()
				.satisfies(provider -> assertThat(provider.aliasReferences()).isEqualTo(1));
		assertThat(body.providers())
				.filteredOn(provider -> provider.name().equals("local"))
				.singleElement()
				.satisfies(provider -> assertThat(provider.aliasReferences()).isZero());
	}

	@Test
	@DisplayName("exposes dialect, base URL, and timeouts with null-safe defaults")
	void exposesConfiguration() {
		ProviderListResponse body = controller.listProviders().getBody();

		assertThat(body).isNotNull();
		assertThat(body.providers())
				.filteredOn(provider -> provider.name().equals("openai"))
				.singleElement()
				.satisfies(provider -> {
					assertThat(provider.type()).isEqualTo("OPENAI");
					assertThat(provider.baseUrl()).isEqualTo("https://api.openai.com");
					assertThat(provider.connectTimeoutSeconds()).isEqualTo(5);
					assertThat(provider.requestTimeoutSeconds()).isEqualTo(60);
					assertThat(provider.embeddingSingleAsString()).isFalse();
				});
		assertThat(body.providers())
				.filteredOn(provider -> provider.name().equals("minimal"))
				.singleElement()
				.satisfies(provider -> {
					assertThat(provider.type()).isEqualTo("UNKNOWN");
					assertThat(provider.baseUrl()).isNull();
					assertThat(provider.connectTimeoutSeconds()).isZero();
					assertThat(provider.requestTimeoutSeconds()).isZero();
				});
	}

	private static CircuitBreaker breaker(CircuitBreaker.State state) {
		CircuitBreaker breaker = mock(CircuitBreaker.class);
		when(breaker.getState()).thenReturn(state);
		return breaker;
	}
}
