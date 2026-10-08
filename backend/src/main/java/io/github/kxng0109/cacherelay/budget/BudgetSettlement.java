package io.github.kxng0109.cacherelay.budget;

import java.time.Instant;
import java.time.YearMonth;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import io.github.kxng0109.cacherelay.contracts.ProviderType;
import io.github.kxng0109.cacherelay.contracts.SHA256Hash;
import io.github.kxng0109.cacherelay.security.ratelimit.TenantIds;
import io.github.kxng0109.cacherelay.security.ratelimit.RateLimitUnavailableException;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

/**
 * Orchestrates hold-then-settle spend accounting: admission holds, stream-end true-ups, abort handling, gap-row
 * persistence, and sweeper expiry. Thin over {@link BudgetEnforcer} (Lua) and {@link BudgetGapRepository} (audit).
 *
 * <p>Fail direction is always safe: money only ever moves inside the atomic scripts; every persistence failure
 * here re-arms work for the next tick instead of losing it, and the hold H stays counted (over-count, never
 * under-count) whenever settlement cannot complete.</p>
 */
@Service
public class BudgetSettlement {

	private static final Logger log = LoggerFactory.getLogger(BudgetSettlement.class);

	private final BudgetEnforcer enforcer;

	private final BudgetGapRepository gapRepository;

	private final BudgetSettlementProperties properties;

	private volatile MeterRegistry meterRegistry = new SimpleMeterRegistry();

	private volatile Counter renewRenewed = counter("renewed", new SimpleMeterRegistry());

	private volatile Counter renewMissing = counter("missing", new SimpleMeterRegistry());

	private volatile Counter renewFailed = counter("failed", new SimpleMeterRegistry());

	public BudgetSettlement(BudgetEnforcer enforcer, BudgetGapRepository gapRepository,
	                        BudgetSettlementProperties properties) {
		this.enforcer = enforcer;
		this.gapRepository = gapRepository;
		this.properties = properties;
	}

	/**
	 * Wires the registry for the hold-renewal counters. Optional on purpose:
	 * without it renewals still run, only unobserved.
	 *
	 * @param meterRegistry registry hosting the renewal counters, if available
	 */
	@Autowired
	public void setMeterRegistry(@Nullable MeterRegistry meterRegistry) {
		if (meterRegistry != null) {
			this.meterRegistry = meterRegistry;
			this.renewRenewed = counter("renewed", meterRegistry);
			this.renewMissing = counter("missing", meterRegistry);
			this.renewFailed = counter("failed", meterRegistry);
		}
	}

	private static Counter counter(String result, MeterRegistry registry) {
		return Counter.builder("budget.hold.renew.total")
				.description("Live-stream hold renewals by outcome")
				.tag("result", result)
				.register(registry);
	}

	/**
	 * Admission under hold-then-settle. When the kill-switch is off, falls back to the prompt-only gate and
	 * reports no hold (sentinel micros {@code -1}).
	 *
	 * @return authorization carrying the verdict, the hold cost, and the admission month
	 */
	public BudgetEnforcer.HoldAuthorization authorize(SHA256Hash keyHash, @Nullable String ownerId,
	                                                  ProviderType type, String model,
	                                                  int promptChars, @Nullable Integer maxTokensOpt,
	                                                  @Nullable String idempotencyKey,
	                                                  @Nullable String bodyHashHex) {
		if (!properties.enabled()) {
			BudgetDecision decision = enforcer.checkBudget(keyHash, ownerId, type, model,
					BudgetEnforcer.estimatePromptTokens(promptChars), idempotencyKey, bodyHashHex);
			Instant now = Instant.now();
			return new BudgetEnforcer.HoldAuthorization(decision, -1L,
					YearMonth.from(now.atZone(ZoneOffset.UTC)).toString());
		}
		int maxTokens = maxTokensOpt != null && maxTokensOpt > 0
				? Math.min(maxTokensOpt, properties.maxTokensCeiling())
				: properties.maxTokensCeiling();
		return enforcer.authorizeHold(keyHash, ownerId, type, model,
				BudgetEnforcer.estimatePromptTokens(promptChars), maxTokens, idempotencyKey, bodyHashHex);
	}

	/**
	 * Creates the hold record for an admitted request. No-op when settlement is disabled.
	 *
	 * @return {@code true} when the record was created (or settlement is disabled)
	 */
	public boolean createHold(String holdId, String keyHex, @Nullable String ownerId,
	                          BudgetEnforcer.HoldAuthorization auth) {
		if (!properties.enabled() || auth.holdMicros() < 0) {
			return true;
		}
		String team = TenantIds.orEmpty(ownerId);
		Instant now = Instant.now();
		try {
			return enforcer.createHold(holdId, keyHex + "|" + team, auth.holdMicros(), auth.holdMonth(),
					now.getEpochSecond(), properties.holdTtlSeconds());
		} catch (RateLimitUnavailableException ex) {
			// H is already counted by admission; a missing hold record only means a later settle becomes a
			// documented gap instead of a true-up. Never fail the request for bookkeeping.
			log.warn("Hold record creation failed for hold {}; continuing without true-up", holdId);
			return false;
		}
	}

	/**
	 * Renews a live hold's TTL so a stream longer than the hold TTL still
	 * settles actuals instead of lapsing to a gap (FIN-B20). Never throws:
	 * a failed renewal only means the sweeper may gap the hold later — the
	 * safe over-count direction — so the stream is never broken for
	 * bookkeeping.
	 *
	 * @param holdId hold id from creation
	 * @return {@code true} when the hold still exists and was renewed
	 */
	public boolean renewHold(String holdId) {
		try {
			boolean renewed = enforcer.renewHold(holdId, properties.holdTtlSeconds());
			if (renewed) {
				renewRenewed.increment();
			} else {
				renewMissing.increment();
			}
			return renewed;
		} catch (RuntimeException ex) {
			log.warn("Hold renewal failed for hold {}; sweeper will gap it if it lapses", holdId);
			renewFailed.increment();
			return false;
		}
	}

	/**
	 * Renews the hold when the coarse cadence elapsed since the last renewal
	 * (FIN-B20): callers check every N written lines, but Redis sees at most
	 * one round trip per {@code renewal-interval-seconds} per stream.
	 *
	 * @param holdId hold id from creation
	 * @param lastRenewalNanos {@link System#nanoTime} of the last renewal attempt
	 * @param nowNanos current {@link System#nanoTime}
	 * @return {@code nowNanos} when a renewal was attempted, else {@code lastRenewalNanos}
	 */
	public long renewHoldIfDue(String holdId, long lastRenewalNanos, long nowNanos) {
		if (nowNanos - lastRenewalNanos < properties.renewalIntervalSeconds() * 1_000_000_000L) {
			return lastRenewalNanos;
		}
		renewHold(holdId);
		return nowNanos;
	}

	/**
	 * Trues a hold up to measured actual spend at stream end.
	 *
	 * @param actualMicros measured actual cost A (micro dollars, >= 0)
	 * @param abort        {@code true} on client abort: settles the input-known portion and re-arms the output
	 *                     hold for the abort grace window instead of closing it
	 * @return settle outcome; a set gap flag persists a gap row (abort re-arms do not, unless straddled)
	 */
	public BudgetEnforcer.SettleOutcome settleStream(String holdId, String keyHex, @Nullable String ownerId,
	                                                 String origMonth, long actualMicros, boolean abort) {
		Instant now = Instant.now();
		String currMonth = YearMonth.from(now.atZone(ZoneOffset.UTC)).toString();
		long abortDue = abort ? now.getEpochSecond() + properties.abortGraceSeconds() : 0L;
		BudgetEnforcer.SettleOutcome outcome = enforcer.settle(holdId, "KEY", keyHex, ownerId, keyHex,
				origMonth, currMonth, Math.max(0L, actualMicros), abortDue);
		if (outcome.gapSet()) {
			String reason = !origMonth.equals(currMonth) ? "ROLLOVER"
					: (abort ? "ABORTED" : "EXPIRED");
			persistGap(holdId, keyHex, ownerId, outcome.amountApplied(), currMonth, origMonth, reason,
					actualMicros);
		}
		return outcome;
	}

	/**
	 * Point-in-time view of one hold record for operator inspection.
	 *
	 * @param holdId        request identifier the hold was created under
	 * @param subject       subject ref recorded at admission
	 * @param heldMicros    micros held at admission
	 * @param settledMicros micros applied at settle, or {@code null} before settle
	 * @param state         hold lifecycle state (e.g. ACTIVE, SETTLED, ABORTED, EXPIRED)
	 */
	public record HoldView(String holdId, String subject, long heldMicros,
			@Nullable Long settledMicros, String state) {
	}

	/**
	 * Reads one hold record by request identifier.
	 *
	 * @param holdId request identifier (hold id)
	 * @return view, or empty when the record expired or never existed
	 */
	public Optional<HoldView> readHold(String holdId) {
		Map<Object, Object> fields = enforcer.readHold(BudgetEnforcer.holdKey(holdId));
		if (fields.isEmpty()) {
			return Optional.empty();
		}
		String settledRaw = stringField(fields, "settled");
		Long settled = settledRaw.isEmpty() ? null : longField(fields, "settled");
		return Optional.of(new HoldView(
				holdId,
				stringField(fields, "subject"),
				longField(fields, "amount"),
				settled,
				stringField(fields, "state")));
	}

	/**
	 * Expires one due hold (sweeper path): crash holds, lapsed abort grace, or records already gone.
	 *
	 * @param holdHashKey hold hash key as returned by {@link BudgetEnforcer#dueHoldKeys}
	 * @return {@code true} when the hold needs no further attention
	 */
	public boolean expireDueHold(String holdHashKey) {
		Map<Object, Object> fields = enforcer.readHold(holdHashKey);
		String holdId = holdIdOf(holdHashKey);
		String keyHex = "";
		String team = "";
		String origMonth = "";
		long held = 0L;
		String state = "";
		if (!fields.isEmpty()) {
			String subject = stringField(fields, "subject");
			int sep = subject.indexOf('|');
			keyHex = sep < 0 ? subject : subject.substring(0, sep);
			team = sep < 0 ? "" : subject.substring(sep + 1);
			origMonth = stringField(fields, "orig_month");
			held = longField(fields, "amount");
			state = stringField(fields, "state");
		}
		Instant now = Instant.now();
		String currMonth = YearMonth.from(now.atZone(ZoneOffset.UTC)).toString();
		BudgetEnforcer.SettleOutcome outcome;
		try {
			outcome = enforcer.settle(holdId, "KEY", keyHex.isEmpty() ? holdId : keyHex,
					team.isEmpty() ? null : team, keyHex.isEmpty() ? holdId : keyHex,
					origMonth.isEmpty() ? currMonth : origMonth, currMonth, -1L, 0L);
		} catch (RateLimitUnavailableException ex) {
			log.warn("Hold expiry Lua failed for {}; re-arming", holdHashKey);
			rearm(holdHashKey, now.getEpochSecond() + 60L);
			return false;
		}
		if (outcome.gapSet()) {
			String reason = fields.isEmpty() ? "EXPIRED" : ("ABORTED".equals(state) ? "ABORTED" : "CRASH");
			String gapKeyHex = keyHex.isEmpty() ? holdId : keyHex;
			String gapTeam = team.isEmpty() ? null : team;
			try {
				gapRepository.save(new BudgetGapRecord(holdId, gapLevel(gapTeam), gapSubject(gapTeam, gapKeyHex),
						held, 0L, origMonth.isEmpty() ? currMonth : origMonth, currMonth, reason));
			} catch (RuntimeException ex) {
				log.warn("Gap-row persistence failed for {}; re-arming for retry", holdHashKey);
				rearm(holdHashKey, now.getEpochSecond() + 60L);
				return false;
			}
		}
		return true;
	}

	/**
	 * Hold ids whose expiry score has passed, bounded for one sweeper tick.
	 */
	public Set<String> dueHoldKeys(int limit) {
		return enforcer.dueHoldKeys(limit, Instant.now().getEpochSecond());
	}

	private void persistGap(String holdId, String keyHex, @Nullable String ownerId, long applied,
	                        String currMonth, String origMonth, String reason, long settledMicros) {
		try {
			gapRepository.save(new BudgetGapRecord(holdId, gapLevel(ownerId), gapSubject(ownerId, keyHex),
					applied, settledMicros, origMonth, currMonth, reason));
		} catch (RuntimeException ex) {
			// Audit-only: the money already moved atomically; a lost gap row degrades chargeback detail,
			// never correctness of the counters.
			log.warn("Gap-row persistence failed for hold {}", holdId);
		}
	}

	/**
	 * Gap-row level attribution (FIN-B23): team-owned holds attribute to their
	 * team so chargeback by level stays truthful; key-only holds stay KEY.
	 *
	 * @param ownerId owning tenant, possibly {@code null} or blank
	 * @return {@code TEAM} when an owner is present, else {@code KEY}
	 */
	private static String gapLevel(@Nullable String ownerId) {
		return ownerId != null && !ownerId.isBlank() ? "TEAM" : "KEY";
	}

	/**
	 * Gap-row subject attribution (FIN-B23): the team when present, else the key.
	 *
	 * @param ownerId owning tenant, possibly {@code null} or blank
	 * @param keyHex  key hex fallback
	 * @return attribution subject
	 */
	private static String gapSubject(@Nullable String ownerId, String keyHex) {
		return ownerId != null && !ownerId.isBlank() ? ownerId : keyHex;
	}

	private void rearm(String holdHashKey, long scoreEpochSec) {
		try {
			enforcer.rearmHold(holdHashKey, scoreEpochSec);
		} catch (RateLimitUnavailableException ex) {
			log.warn("Hold re-arm failed for {}; entry retries on TTL drift", holdHashKey);
		}
	}

	private static String holdIdOf(String holdHashKey) {
		int idx = holdHashKey.lastIndexOf(':');
		return idx < 0 ? holdHashKey : holdHashKey.substring(idx + 1);
	}

	private static String stringField(Map<Object, Object> fields, String name) {
		Object value = fields.get(name);
		return value == null ? "" : value.toString();
	}

	private static long longField(Map<Object, Object> fields, String name) {
		try {
			return Long.parseLong(stringField(fields, name));
		} catch (NumberFormatException malformed) {
			return 0L;
		}
	}
}
