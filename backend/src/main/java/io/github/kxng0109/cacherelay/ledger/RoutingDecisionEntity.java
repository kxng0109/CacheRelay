package io.github.kxng0109.cacherelay.ledger;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;
import org.jspecify.annotations.Nullable;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * One sampled routing decision: which alias chain was walked for a request,
 * which legs were tried, who won, and the price rates known at the time.
 *
 * <p>Observation only: rows never influence routing. Identifiers and rates
 * only — no prompts, no completions, no keys, no PII.</p>
 */
@Entity
@Table(name = "routing_decision_log")
@Getter
public class RoutingDecisionEntity {

	@Id
	@GeneratedValue(strategy = GenerationType.UUID)
	@Column(name = "id", nullable = false, updatable = false)
	private UUID id;

	@Column(name = "occurred_at", nullable = false)
	private Instant occurredAt;

	@Column(name = "alias_name", nullable = false, length = 128)
	private String alias;

	@Column(name = "model_name", nullable = false, length = 128)
	private String model;

	@Column(name = "min_quality_tier", length = 16)
	private @Nullable String minQualityTier;

	@Column(name = "tradeoff_mode", nullable = false, length = 16)
	private String tradeoffMode;

	@Column(name = "chain_json", nullable = false, columnDefinition = "TEXT")
	private String chainJson;

	@Column(name = "tried_json", nullable = false, columnDefinition = "TEXT")
	private String triedJson;

	@Column(name = "winner", length = 128)
	private @Nullable String winner;

	@Column(name = "input_rate", precision = 24, scale = 12)
	private @Nullable BigDecimal inputRate;

	@Column(name = "output_rate", precision = 24, scale = 12)
	private @Nullable BigDecimal outputRate;

	/**
	 * No argument constructor required by the JPA specification.
	 */
	protected RoutingDecisionEntity() {
	}

	/**
	 * @param occurredAt     when the decision completed
	 * @param alias          requested alias name
	 * @param model          requested model name
	 * @param minQualityTier requested quality floor, may be {@code null}
	 * @param tradeoffMode   requested tradeoff mode as received
	 * @param chainJson      planned chain steps as a JSON array
	 * @param triedJson      tried legs in walk order as a JSON array
	 * @param winner         winning provider, may be {@code null} when all legs failed
	 * @param inputRate      known input price rate, may be {@code null}
	 * @param outputRate     known output price rate, may be {@code null}
	 */
	public RoutingDecisionEntity(
			Instant occurredAt,
			String alias,
			String model,
			@Nullable String minQualityTier,
			String tradeoffMode,
			String chainJson,
			String triedJson,
			@Nullable String winner,
			@Nullable BigDecimal inputRate,
			@Nullable BigDecimal outputRate
	) {
		this.occurredAt = occurredAt;
		this.alias = alias;
		this.model = model;
		this.minQualityTier = minQualityTier;
		this.tradeoffMode = tradeoffMode;
		this.chainJson = chainJson;
		this.triedJson = triedJson;
		this.winner = winner;
		this.inputRate = inputRate;
		this.outputRate = outputRate;
	}
}
