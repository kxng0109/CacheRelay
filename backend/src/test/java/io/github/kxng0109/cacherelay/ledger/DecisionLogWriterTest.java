package io.github.kxng0109.cacherelay.ledger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNoException;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.junit.jupiter.MockitoExtension;
import tools.jackson.databind.ObjectMapper;

/**
 * Unit tests for {@link DecisionLogWriter}: sampling gates persistence,
 * failures never propagate, and only identifiers and rates are stored.
 */
@DisplayName("DecisionLogWriter")
@ExtendWith(MockitoExtension.class)
class DecisionLogWriterTest {

	private final ObjectMapper objectMapper = new ObjectMapper();

	private DecisionLogWriter writer(RoutingDecisionRepository repository, int perMille) {
		return new DecisionLogWriter(repository, objectMapper, perMille);
	}

	@Test
	@DisplayName("full sampling persists the decision with chain, legs, and rates")
	void fullSamplingPersists() {
		RoutingDecisionRepository repository = mock(RoutingDecisionRepository.class);
		when(repository.save(any(RoutingDecisionEntity.class)))
				.thenAnswer(call -> call.getArgument(0));

		writer(repository, 1000).record("fast", "gpt-5.6-luna", "STANDARD", "quality",
				List.of("openai:gpt-5.6-luna", "groq:"), List.of("openai", "groq (circuit open)"),
				"openai", new BigDecimal("0.0000025"), new BigDecimal("0.00001"));

		ArgumentCaptor<RoutingDecisionEntity> saved = ArgumentCaptor.forClass(RoutingDecisionEntity.class);
		verify(repository).save(saved.capture());
		RoutingDecisionEntity entity = saved.getValue();
		assertThat(entity.getAlias()).isEqualTo("fast");
		assertThat(entity.getModel()).isEqualTo("gpt-5.6-luna");
		assertThat(entity.getMinQualityTier()).isEqualTo("STANDARD");
		assertThat(entity.getTradeoffMode()).isEqualTo("quality");
		assertThat(entity.getChainJson()).contains("openai:gpt-5.6-luna");
		assertThat(entity.getTriedJson()).contains("groq (circuit open)");
		assertThat(entity.getWinner()).isEqualTo("openai");
		assertThat(entity.getInputRate()).isEqualByComparingTo("0.0000025");
		assertThat(entity.getOutputRate()).isEqualByComparingTo("0.00001");
		assertThat(entity.getOccurredAt()).isNotNull();
	}

	@Test
	@DisplayName("zero sampling stores nothing")
	void zeroSamplingStoresNothing() {
		RoutingDecisionRepository repository = mock(RoutingDecisionRepository.class);

		writer(repository, 0).record("fast", "m", null, "quality",
				List.of("openai:"), List.of("openai"), "openai", null, null);

		verify(repository, never()).save(any());
	}

	@Test
	@DisplayName("repository failures never propagate to the request path")
	void failuresNeverPropagate() {
		RoutingDecisionRepository repository = mock(RoutingDecisionRepository.class);
		when(repository.save(any())).thenThrow(new RuntimeException("db down"));

		assertThatNoException().isThrownBy(() -> writer(repository, 1000).record(
				"fast", "m", null, "quality", List.of("openai:"), List.of(),
				null, null, null));
	}

	@Test
	@DisplayName("null lists serialize as empty arrays")
	void nullListsSerializeEmpty() {
		RoutingDecisionRepository repository = mock(RoutingDecisionRepository.class);
		when(repository.save(any(RoutingDecisionEntity.class)))
				.thenAnswer(call -> call.getArgument(0));

		writer(repository, 1000).record("a", "m", null, "quality", null, null, null, null, null);

		ArgumentCaptor<RoutingDecisionEntity> saved = ArgumentCaptor.forClass(RoutingDecisionEntity.class);
		verify(repository).save(saved.capture());
		assertThat(saved.getValue().getChainJson()).isEqualTo("[]");
		assertThat(saved.getValue().getTriedJson()).isEqualTo("[]");
	}

	@Test
	@DisplayName("unknown rates persist as null, never fabricated")
	void unknownRatesPersistNull() {
		RoutingDecisionRepository repository = mock(RoutingDecisionRepository.class);
		when(repository.save(any(RoutingDecisionEntity.class)))
				.thenAnswer(call -> call.getArgument(0));

		writer(repository, 1000).record("fast", "m", null, "quality",
				List.of(), List.of(), null, null, null);

		ArgumentCaptor<RoutingDecisionEntity> saved = ArgumentCaptor.forClass(RoutingDecisionEntity.class);
		verify(repository).save(saved.capture());
		assertThat(saved.getValue().getInputRate()).isNull();
		assertThat(saved.getValue().getOutputRate()).isNull();
		assertThat(saved.getValue().getWinner()).isNull();
	}
}
