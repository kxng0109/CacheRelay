package io.github.kxng0109.cacherelay.proxy;

import io.github.kxng0109.cacherelay.contracts.GatewayProperties;
import io.github.kxng0109.cacherelay.ledger.CostCalculator;
import io.github.kxng0109.cacherelay.proxy.failover.FailoverOrchestrator;
import io.github.kxng0109.cacherelay.proxy.protocol.ProtocolAdapterResolver;
import io.github.kxng0109.cacherelay.proxy.sse.SseFlushStrategy;
import io.github.kxng0109.cacherelay.proxy.sse.SseLineGuardAutoConfig;
import io.micrometer.tracing.Span;
import io.micrometer.tracing.Tracer;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.mock.web.MockHttpServletRequest;
import tools.jackson.databind.ObjectMapper;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Controller-time span tagging without any container: with a tracer wired,
 * the unknown-model path tags the active span; without one the request still
 * answers normally.
 */
@DisplayName("ProxyController span tagging")
class ProxyControllerSpanTaggingTest {

	@Test
	@DisplayName("unknown-model 404 tags the active span")
	void unknownModelTagsSpan() {
		Span span = mock(Span.class);
		Tracer tracer = mock(Tracer.class);
		when(tracer.currentSpan()).thenReturn(span);
		ProxyController controller = controller();
		controller.setTracer(tracer);

		var entity = controller.proxyChatCompletions(
				"{\"model\":\"ghost-model-xyz\",\"messages\":[]}", new MockHttpServletRequest());

		assertThat(entity.getStatusCode().value()).isEqualTo(404);
		// Alias stays absent on this path (no alias resolved); the model is recorded.
		verify(span).tag(ProxySpanAttributes.MODEL, "ghost-model-xyz");
		verify(span).tag(ProxySpanAttributes.OUTCOME, ProxySpanAttributes.OUTCOME_ERROR);
		verify(span).tag(ProxySpanAttributes.ERROR_REASON, "unknown_model");
	}

	@Test
	@DisplayName("no tracer means the request still answers normally")
	void nullTracerStillAnswers() {
		ProxyController controller = controller();

		var entity = controller.proxyChatCompletions(
				"{\"model\":\"ghost-model-xyz\",\"messages\":[]}", new MockHttpServletRequest());

		assertThat(entity.getStatusCode().value()).isEqualTo(404);
	}

	private static ProxyController controller() {
		return new ProxyController(
				mock(FailoverOrchestrator.class),
				new GatewayProperties(),
				new ObjectMapper(),
				mock(ProtocolAdapterResolver.class),
				mock(CostCalculator.class),
				mock(ApplicationEventPublisher.class),
				mock(SseFlushStrategy.class),
				mock(SseLineGuardAutoConfig.SseLineGuardFactory.class));
	}
}
