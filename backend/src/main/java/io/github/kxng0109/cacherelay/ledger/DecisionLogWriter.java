package io.github.kxng0109.cacherelay.ledger;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.ThreadLocalRandom;

import org.jspecify.annotations.Nullable;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import lombok.extern.slf4j.Slf4j;
import tools.jackson.databind.ObjectMapper;

/**
 * Best-effort, sampled persistence of routing decisions.
 *
 * <p>Sampling gates persistence first, so unsampled requests pay nothing. A
 * sampled write that fails (database outage, serialization fault) is dropped
 * with a debug log and never propagates: observation must never break serving.
 * Only identifiers and price rates are stored — never prompts, completions,
 * keys, or PII.</p>
 */
@Slf4j
@Component
public class DecisionLogWriter {

	private final RoutingDecisionRepository repository;
	private final ObjectMapper objectMapper;
	private final int samplePerMille;

	/**
	 * @param repository     decision repository
	 * @param objectMapper   mapper for chain and leg arrays
	 * @param samplePerMille samples stored per mille (0 through 1000)
	 */
	public DecisionLogWriter(
			RoutingDecisionRepository repository,
			ObjectMapper objectMapper,
			@Value("${gateway.routing.decision-log-sample-per-mille:10}") int samplePerMille
	) {
		this.repository = repository;
		this.objectMapper = objectMapper;
		this.samplePerMille = Math.clamp(samplePerMille, 0, 1000);
	}

	/**
	 * Records one routing decision when sampled. Never throws.
	 *
	 * @param alias          requested alias name
	 * @param model          requested model name
	 * @param minQualityTier requested quality floor, may be {@code null}
	 * @param tradeoffMode   requested tradeoff mode as received
	 * @param chain          planned chain steps ({@code provider:model} strings)
	 * @param triedLegs      tried legs in walk order, winner included
	 * @param winner         winning provider, may be {@code null}
	 * @param inputRate      known input price rate, may be {@code null}
	 * @param outputRate     known output price rate, may be {@code null}
	 */
	public void record(
			String alias,
			String model,
			@Nullable String minQualityTier,
			String tradeoffMode,
			List<String> chain,
			List<String> triedLegs,
			@Nullable String winner,
			@Nullable BigDecimal inputRate,
			@Nullable BigDecimal outputRate
	) {
		if (ThreadLocalRandom.current().nextInt(1000) >= samplePerMille) {
			return;
		}
		try {
			repository.save(new RoutingDecisionEntity(
					Instant.now(), alias, model, minQualityTier, tradeoffMode,
					toJson(chain), toJson(triedLegs), winner, inputRate, outputRate));
		} catch (RuntimeException ex) {
			log.debug("Dropping routing decision log: {}", ex.getMessage());
		}
	}

	private String toJson(List<String> items) {
		return objectMapper.writeValueAsString(items == null ? List.of() : items);
	}
}
