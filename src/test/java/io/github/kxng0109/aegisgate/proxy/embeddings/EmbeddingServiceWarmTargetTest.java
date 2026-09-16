package io.github.kxng0109.aegisgate.proxy.embeddings;

import io.github.kxng0109.aegisgate.contracts.FailoverStrategy;
import io.github.kxng0109.aegisgate.contracts.GatewayProperties;
import io.github.kxng0109.aegisgate.contracts.ModelAlias;
import io.github.kxng0109.aegisgate.contracts.ProviderConfig;
import io.github.kxng0109.aegisgate.contracts.ProviderRef;
import io.github.kxng0109.aegisgate.contracts.ProviderType;
import io.github.kxng0109.aegisgate.ledger.CostCalculator;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.context.ApplicationEventPublisher;

import java.net.URI;
import java.time.Duration;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

@DisplayName("EmbeddingService.resolveWarmTarget")
class EmbeddingServiceWarmTargetTest {

	private final EmbeddingAdapterResolver adapterResolver = mock(EmbeddingAdapterResolver.class);
	private final EmbeddingBatchOrchestrator batchOrchestrator = mock(EmbeddingBatchOrchestrator.class);
	private final CostCalculator costCalculator = mock(CostCalculator.class);
	private final ApplicationEventPublisher eventPublisher = mock(ApplicationEventPublisher.class);

	@Test
	@DisplayName("an unresolvable semantic model returns null instead of throwing")
	void unresolvableModelReturnsNull() {
		EmbeddingService service = new EmbeddingService(
				new GatewayProperties(), adapterResolver, batchOrchestrator, costCalculator, eventPublisher);

		assertThat(service.resolveWarmTarget("nomic-embed")).isNull();
	}

	@Test
	@DisplayName("an alias chain resolves to the provider config, effective model, and /api/embed endpoint")
	void aliasChainResolvesWarmTarget() {
		GatewayProperties gateway = new GatewayProperties();
		gateway.getProviders().put("ollama-local", new ProviderConfig(
				"ollama-local", ProviderType.OLLAMA, URI.create("http://localhost:11434"),
				null, Duration.ofSeconds(5), Duration.ofSeconds(60), false));
		gateway.getAliases().put("local-embed", new ModelAlias(
				List.of(new ProviderRef("ollama-local", "nomic-embed-text:latest")), FailoverStrategy.SEQUENTIAL));
		EmbeddingService service = new EmbeddingService(
				gateway, adapterResolver, batchOrchestrator, costCalculator, eventPublisher);

		EmbeddingService.WarmTarget target = service.resolveWarmTarget("local-embed");

		assertThat(target).isNotNull();
		assertThat(target.config().name()).isEqualTo("ollama-local");
		assertThat(target.config().type()).isEqualTo(ProviderType.OLLAMA);
		assertThat(target.effectiveModel()).isEqualTo("nomic-embed-text:latest");
		assertThat(target.targetUri()).isEqualTo(URI.create("http://localhost:11434/api/embed"));
	}

	@Test
	@DisplayName("a chain step without model override keeps the requested name as the effective model")
	void aliasWithoutOverrideKeepsRequestedName() {
		GatewayProperties gateway = new GatewayProperties();
		gateway.getProviders().put("ollama-local", new ProviderConfig(
				"ollama-local", ProviderType.OLLAMA, URI.create("http://localhost:11434"),
				null, Duration.ofSeconds(5), Duration.ofSeconds(60), false));
		gateway.getAliases().put("local-embed", new ModelAlias(
				List.of(new ProviderRef("ollama-local", null)), FailoverStrategy.SEQUENTIAL));
		EmbeddingService service = new EmbeddingService(
				gateway, adapterResolver, batchOrchestrator, costCalculator, eventPublisher);

		EmbeddingService.WarmTarget target = service.resolveWarmTarget("local-embed");

		assertThat(target).isNotNull();
		assertThat(target.effectiveModel()).isEqualTo("local-embed");
	}
}
