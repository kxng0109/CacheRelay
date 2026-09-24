package io.github.kxng0109.cacherelay.ledger;

import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;

/**
 * One lazily-materialized dashboard bucket: mergeable partial sums for a single
 * settled day at one scope grain.
 *
 * <p>Rows are written on first view of a settled day and reused afterwards;
 * no background job touches this table. Each row pins one owner, provider,
 * and model triple; totals and every breakdown reconstruct exactly from
 * detail rows in memory. The grain unique constraint keeps exactly one row
 * per grain: concurrent first views race on insert and exactly one wins.
 * Ledger rows are append-only, so settled-day buckets are immutable once
 * written.</p>
 */
@Entity
@Table(name = "dashboard_daily_bucket")
@Getter
public class DashboardBucket {

	@Id
	@Column(name = "id", nullable = false, updatable = false)
	private UUID id;

	@Column(name = "scope_type", nullable = false, length = 16)
	private String scopeType;

	@Column(name = "scope_key", nullable = false, length = 128)
	private String scopeKey;

	@Column(name = "bucket_day", nullable = false)
	private LocalDate bucketDay;

	@Column(name = "owner", nullable = false, length = 64)
	private String owner;

	@Column(name = "provider", nullable = false, length = 64)
	private String provider;

	@Column(name = "model", nullable = false, length = 128)
	private String model;

	@Column(name = "requests", nullable = false)
	private long requests;

	@Column(name = "prompt_tokens", nullable = false)
	private long promptTokens;

	@Column(name = "completion_tokens", nullable = false)
	private long completionTokens;

	@Column(name = "total_tokens", nullable = false)
	private long totalTokens;

	@Column(name = "cost_micros", nullable = false)
	private long costMicros;

	@Column(name = "billed_micros", nullable = false)
	private long billedMicros;

	@Column(name = "effective_micros", nullable = false)
	private long effectiveMicros;

	@Column(name = "duration_sum_ms", nullable = false)
	private long durationSumMs;

	@Column(name = "cache_read_tokens", nullable = false)
	private long cacheReadTokens;

	@Column(name = "cache_write_tokens", nullable = false)
	private long cacheWriteTokens;

	@Column(name = "uncached_tokens", nullable = false)
	private long uncachedTokens;

	@Column(name = "reasoning_tokens", nullable = false)
	private long reasoningTokens;

	@Column(name = "watermark", nullable = false)
	private Instant watermark;

	@Column(name = "updated_at", nullable = false)
	private Instant updatedAt;

	/**
	 * No argument constructor required by the JPA specification.
	 */
	protected DashboardBucket() {
	}

	/**
	 * @param id                row id, never {@code null}
	 * @param scopeType         scope grain ({@code PERSONAL}, {@code ORG}; {@code TEAM} reserved)
	 * @param scopeKey          scope identity (user id string, or {@code global})
	 * @param bucketDay         settled day, never {@code null}
	 * @param owner             owner grain, never {@code null}
	 * @param provider          provider grain, or {@code ""} for totals rows
	 * @param model             model grain, or {@code ""} for totals rows
	 * @param requests          request count in the bucket
	 * @param promptTokens      prompt token sum in the bucket
	 * @param completionTokens  completion token sum in the bucket
	 * @param totalTokens       total token sum in the bucket
	 * @param costMicros        list-cost sum in micro dollars
	 * @param billedMicros      billed-cost sum in micro dollars
	 * @param effectiveMicros   effective-cost sum in micro dollars
	 * @param durationSumMs     duration sum in milliseconds (averages recompute from this)
	 * @param cacheReadTokens   cache-read token sum in the bucket
	 * @param cacheWriteTokens  cache-write token sum in the bucket
	 * @param uncachedTokens    uncached token sum in the bucket
	 * @param reasoningTokens   reasoning token sum in the bucket
	 * @param watermark         newest ledger row counted, never {@code null}
	 * @param updatedAt         when the row was written, never {@code null}
	 */
	public DashboardBucket(
			UUID id,
			String scopeType,
			String scopeKey,
			LocalDate bucketDay,
			String owner,
			String provider,
			String model,
			long requests,
			long promptTokens,
			long completionTokens,
			long totalTokens,
			long costMicros,
			long billedMicros,
			long effectiveMicros,
			long durationSumMs,
			long cacheReadTokens,
			long cacheWriteTokens,
			long uncachedTokens,
			long reasoningTokens,
			Instant watermark,
			Instant updatedAt
	) {
		this.id = id;
		this.scopeType = scopeType;
		this.scopeKey = scopeKey;
		this.bucketDay = bucketDay;
		this.owner = owner;
		this.provider = provider;
		this.model = model;
		this.requests = requests;
		this.promptTokens = promptTokens;
		this.completionTokens = completionTokens;
		this.totalTokens = totalTokens;
		this.costMicros = costMicros;
		this.billedMicros = billedMicros;
		this.effectiveMicros = effectiveMicros;
		this.durationSumMs = durationSumMs;
		this.cacheReadTokens = cacheReadTokens;
		this.cacheWriteTokens = cacheWriteTokens;
		this.uncachedTokens = uncachedTokens;
		this.reasoningTokens = reasoningTokens;
		this.watermark = watermark;
		this.updatedAt = updatedAt;
	}
}
