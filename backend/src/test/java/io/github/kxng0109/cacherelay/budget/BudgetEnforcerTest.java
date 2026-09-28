package io.github.kxng0109.cacherelay.budget;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.github.benmanes.caffeine.cache.Ticker;
import io.github.kxng0109.cacherelay.contracts.ProviderType;
import io.github.kxng0109.cacherelay.contracts.SHA256Hash;
import io.github.kxng0109.cacherelay.ledger.CostCalculator;
import io.github.kxng0109.cacherelay.security.ratelimit.RateLimitUnavailableException;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ZSetOperations;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.mockito.ArgumentCaptor;

import java.time.Duration;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Unit tests for the spend-budget enforcer with a stubbed script backend.
 */
@DisplayName("BudgetEnforcer")
class BudgetEnforcerTest {

	private final StringRedisTemplate redisTemplate = mock(StringRedisTemplate.class);

	private final DefaultRedisScript<List> script = script();

	private final CostCalculator costCalculator = mock(CostCalculator.class);

	private final BudgetEnforcer enforcer =
			new BudgetEnforcer(redisTemplate, script(), script(), script(), costCalculator,
					new SimpleMeterRegistry());

	private static SHA256Hash keyHash() {
		return SHA256Hash.fromRawKey("gw-aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa");
	}

	@SuppressWarnings("unchecked")
	private static DefaultRedisScript<List> script() {
		return new DefaultRedisScript<>("return {1,0,-1,0,0}", List.class);
	}

	@Test
	@DisplayName("allowed decisions pass remaining spend through")
	void allowedPassesThrough() {
		when(redisTemplate.execute(any(), anyList(), any(), any(), any(), any(), any(), any()))
				.thenReturn(List.of(1L, 0L, 500L, 60L, 2L, "2026-09"));
		when(costCalculator.calculate(any(), any(), anyLong(), anyLong())).thenReturn(4_000L);

		BudgetDecision decision = enforcer.checkBudget(keyHash(), "owner-1", ProviderType.OPENAI, "gpt-5.6-luna", 10, null, null);

		assertTrue(decision instanceof BudgetDecision.Allowed allowed
				           && allowed.remainingMicros() == 500L
				           && allowed.resetSeconds() == 60L);
	}

	@Test
	@DisplayName("denied decisions map level, window, and retry horizon")
	void deniedMapsLevelWindowAndRetry() {
		when(redisTemplate.execute(any(), anyList(), any(), any(), any(), any(), any(), any()))
				.thenReturn(List.of(0L, 3L, 0L, 45L, 2L, "2026-09"));
		when(costCalculator.calculate(any(), any(), anyLong(), anyLong())).thenReturn(4_000L);

		BudgetDecision decision = enforcer.checkBudget(keyHash(), "owner-1", ProviderType.OPENAI, "gpt-5.6-luna", 10, null, null);

		assertTrue(decision instanceof BudgetDecision.Denied denied
				           && "TEAM".equals(denied.level())
				           && "MINUTE".equals(denied.window())
				           && denied.retryAfterSeconds() == 45L);
	}

	@Test
	@DisplayName("monthly denies resolve month-end horizons")
	void monthlyDeniesResolveMonthEnd() {
		when(redisTemplate.execute(any(), anyList(), any(), any(), any(), any(), any(), any()))
				.thenReturn(List.of(0L, 4L, 0L, 0L, 1L, "2026-09"));
		when(costCalculator.calculate(any(), any(), anyLong(), anyLong())).thenReturn(4_000L);

		BudgetDecision decision = enforcer.checkBudget(keyHash(), "owner-1", ProviderType.OPENAI, "gpt-5.6-luna", 10, null, null);

		assertTrue(decision instanceof BudgetDecision.Denied denied
				           && "TEAM".equals(denied.level())
				           && "MONTH".equals(denied.window())
				           && denied.retryAfterSeconds() >= 1L
				           && denied.retryAfterSeconds() <= 2_678_400L);
	}

	@Test
	@DisplayName("string wire values are accepted like integers")
	void stringWireValuesAccepted() {
		when(redisTemplate.execute(any(), anyList(), any(), any(), any(), any(), any(), any()))
				.thenReturn(List.of("1", "0", "500", "60", "2", "2026-09"));
		when(costCalculator.calculate(any(), any(), anyLong(), anyLong())).thenReturn(4_000L);

		BudgetDecision decision = enforcer.checkBudget(keyHash(), "owner-1", ProviderType.OPENAI, "gpt-5.6-luna", 10, null, null);

		assertTrue(decision instanceof BudgetDecision.Allowed);
	}

	@Test
	@DisplayName("malformed script results fail closed")
	void malformedShapeFailsClosed() {
		when(redisTemplate.execute(any(), anyList(), any(), any(), any(), any(), any(), any())).thenReturn(List.of(1L));

		assertThrows(
				RateLimitUnavailableException.class, () ->
						enforcer.checkBudget(keyHash(), "owner-1", ProviderType.OPENAI, "gpt-5.6-luna", 10, null, null)
		);
	}

	@Test
	@DisplayName("Redis failures fail closed for every key class")
	void redisDownFailsClosed() {
		when(redisTemplate.execute(any(), anyList(), any(), any(), any(), any(), any(), any()))
				.thenThrow(new RedisConnectionFailureException("redis down"));

		assertThrows(
				RateLimitUnavailableException.class, () ->
						enforcer.checkBudget(keyHash(), "owner-1", ProviderType.OPENAI, "gpt-5.6-luna", 10, null, null)
		);
	}

	@Test
	@DisplayName("confirmed-unbudgeted keys skip the second round trip")
	void presenceSkipsSecondRoundTrip() {
		when(redisTemplate.execute(any(), anyList(), any(), any(), any(), any(), any(), any()))
				.thenReturn(List.of(1L, 0L, -1L, 0L, 0L, "2026-09"));
		when(costCalculator.calculate(any(), any(), anyLong(), anyLong())).thenReturn(4_000L);

		enforcer.checkBudget(keyHash(), "owner-1", ProviderType.OPENAI, "gpt-5.6-luna", 10, null, null);
		enforcer.checkBudget(keyHash(), "owner-1", ProviderType.OPENAI, "gpt-5.6-luna", 10, null, null);

		verify(redisTemplate, times(1)).execute(any(), anyList(), any(), any(), any(), any(), any(), any());
	}

	@Test
	@DisplayName("prompt token heuristic is documented and bounded")
	void estimatePromptTokensIsBounded() {
		assertEquals(1, BudgetEnforcer.estimatePromptTokens(0));
		assertEquals(1, BudgetEnforcer.estimatePromptTokens(4));
		assertEquals(2, BudgetEnforcer.estimatePromptTokens(5));
		assertEquals(25, BudgetEnforcer.estimatePromptTokens(100));
	}

	@Test
	@DisplayName("key builders follow the documented layout")
	void keyBuildersFollowLayout() {
		assertEquals("budget:{b:global}:cfg:TEAM:tenant-a", BudgetEnforcer.cfgKey("TEAM", "tenant-a"));
		assertTrue(BudgetEnforcer.minuteKey("KEY", "ab12", 42L).equals("budget:{b:global}:KEY:ab12:minute:42"));
		assertTrue(BudgetEnforcer.monthKey("ORG", "global", "2026-09").equals("budget:{b:global}:ORG:global:month:2026-09"));
		assertTrue(BudgetEnforcer.secondsToMonthEnd() >= 1L);
	}

	@Test
	@DisplayName("null meter registry falls back to an isolated registry")
	void nullRegistryFallsBack() {
		BudgetEnforcer local =
				new BudgetEnforcer(redisTemplate, script(), script(), script(), costCalculator, null);
		when(redisTemplate.execute(any(), anyList(), any(), any(), any(), any(), any(), any()))
				.thenReturn(List.of(1L, 0L, 500L, 60L, 2L, "2026-09"));
		when(costCalculator.calculate(any(), any(), anyLong(), anyLong())).thenReturn(4_000L);

		BudgetDecision decision = local.checkBudget(
				SHA256Hash.fromRawKey("gw-cccccccccccccccccccccccccccccccc"), "owner-1",
				ProviderType.OPENAI, "gpt-5.6-luna", 10, null, null);

		assertTrue(decision instanceof BudgetDecision.Allowed);
	}

	@Test
	@DisplayName("null script result fails closed")
	void nullScriptResultFailsClosed() {
		when(redisTemplate.execute(any(), anyList(), any(), any(), any(), any(), any(), any())).thenReturn(null);

		assertThrows(
				RateLimitUnavailableException.class, () ->
						enforcer.checkBudget(
								SHA256Hash.fromRawKey("gw-dddddddddddddddddddddddddddddddd"), "owner-1",
								ProviderType.OPENAI, "gpt-5.6-luna", 10, null, null)
		);
	}

	@Test
	@DisplayName("non-numeric wire values fail closed")
	void nonNumericWireValueFailsClosed() {
		when(redisTemplate.execute(any(), anyList(), any(), any(), any(), any(), any(), any()))
				.thenReturn(List.of(1L, 0L, Boolean.TRUE, 60L, 2L, "2026-09"));

		assertThrows(
				RateLimitUnavailableException.class, () ->
						enforcer.checkBudget(
								SHA256Hash.fromRawKey("gw-eeeeeeeeeeeeeeeeeeeeeeeeeeeeeeee"), "owner-1",
								ProviderType.OPENAI, "gpt-5.6-luna", 10, null, null)
		);
	}

	@Test
	@DisplayName("null owner skips the TEAM level keys")
	@SuppressWarnings("unchecked")
	void nullOwnerSkipsTeamLevel() {
		ArgumentCaptor<List<String>> keysCaptor = ArgumentCaptor.forClass(List.class);
		when(redisTemplate.execute(any(), anyList(), any(), any(), any(), any(), any(), any()))
				.thenReturn(List.of(1L, 0L, 500L, 60L, 1L, "2026-09"));
		when(costCalculator.calculate(any(), any(), anyLong(), anyLong())).thenReturn(4_000L);

		BudgetDecision decision = enforcer.checkBudget(
				SHA256Hash.fromRawKey("gw-ffffffffffffffffffffffffffffffff"), null,
				ProviderType.OPENAI, "gpt-5.6-luna", 10, null, null);

		assertTrue(decision instanceof BudgetDecision.Allowed);
		verify(redisTemplate, atLeastOnce()).execute(any(), keysCaptor.capture(), any(Object[].class));
		List<String> keys = keysCaptor.getValue();
		assertEquals(3, keys.size());
		assertEquals("", keys.get(1));
	}

	@Test
	@DisplayName("blank owner skips the TEAM level keys")
	void blankOwnerSkipsTeamLevel() {
		when(redisTemplate.execute(any(), anyList(), any(), any(), any(), any(), any(), any()))
				.thenReturn(List.of(1L, 0L, 500L, 60L, 1L, "2026-09"));
		when(costCalculator.calculate(any(), any(), anyLong(), anyLong())).thenReturn(4_000L);

		BudgetDecision decision = enforcer.checkBudget(
				SHA256Hash.fromRawKey("gw-11111111111111111111111111111111"), "  ",
				ProviderType.OPENAI, "gpt-5.6-luna", 10, null, null);

		assertTrue(decision instanceof BudgetDecision.Allowed);
	}

	@Test
	@DisplayName("org-level denies map level and minute horizon")
	void orgDenialMapsLevel() {
		when(redisTemplate.execute(any(), anyList(), any(), any(), any(), any(), any(), any()))
				.thenReturn(List.of(0L, 5L, 0L, 60L, 3L, "2026-09"));
		when(costCalculator.calculate(any(), any(), anyLong(), anyLong())).thenReturn(4_000L);

		BudgetDecision decision = enforcer.checkBudget(
				SHA256Hash.fromRawKey("gw-22222222222222222222222222222222"), "owner-1",
				ProviderType.OPENAI, "gpt-5.6-luna", 10, null, null);

		assertTrue(decision instanceof BudgetDecision.Denied denied
				           && "ORG".equals(denied.level())
				           && "MINUTE".equals(denied.window())
				           && denied.retryAfterSeconds() == 60L);
	}

	@Test
	@DisplayName("pricing outage fails closed instead of zero-cost admit")
	void pricingFailureFailsClosed() {
		when(costCalculator.calculate(any(), any(), anyLong(), anyLong()))
				.thenThrow(new RuntimeException("pricing down"));

		assertThrows(
				RateLimitUnavailableException.class, () ->
						enforcer.checkBudget(
								SHA256Hash.fromRawKey("gw-33333333333333333333333333333333"), "owner-1",
								ProviderType.OPENAI, "gpt-5.6-luna", 10, null, null)
		);
		verify(redisTemplate, never()).execute(any(), anyList(), any(), any(), any(), any(), any(), any());
	}

	@Test
	@DisplayName("script takes config keys plus subjects; Lua derives TIME windows")
	@SuppressWarnings("unchecked")
	void scriptKeysGroupedByKind() {
		ArgumentCaptor<List<String>> keysCaptor = ArgumentCaptor.forClass(List.class);
		ArgumentCaptor<Object[]> argsCaptor = ArgumentCaptor.forClass(Object[].class);
		when(redisTemplate.execute(any(), anyList(), any(), any(), any(), any(), any(), any()))
				.thenReturn(List.of(1L, 0L, 500L, 60L, 3L, "2026-09"));
		when(costCalculator.calculate(any(), any(), anyLong(), anyLong())).thenReturn(4_000L);
		SHA256Hash hash = SHA256Hash.fromRawKey("gw-44444444444444444444444444444444");

		enforcer.checkBudget(
				hash, "owner-1",
				ProviderType.OPENAI, "gpt-5.6-luna", 10, null, null);

		verify(redisTemplate).execute(any(), keysCaptor.capture(), argsCaptor.capture());
		List<String> keys = keysCaptor.getValue();
		assertEquals(3, keys.size());
		// Only config keys cross the boundary; windows are Lua-derived from TIME.
		assertEquals(BudgetEnforcer.cfgKey("KEY", hash.hex()), keys.get(0));
		assertEquals(BudgetEnforcer.cfgKey("TEAM", "owner-1"), keys.get(1));
		assertEquals(BudgetEnforcer.cfgKey("ORG", "global"), keys.get(2));
		Object[] args = argsCaptor.getValue();
		assertEquals(6, args.length);
		assertEquals(hash.hex(), args[2]);
		assertEquals("owner-1", args[3]);
		assertEquals("global", args[4]);
		assertEquals("{b:global}", args[5]);
		// Single Cluster slot: every addressed key carries the same hash tag.
		for (String key : keys) {
			if (!key.isEmpty()) {
				assertTrue(key.contains("{b:global}"));
			}
		}
	}

	@Test
	@DisplayName("negatives lapse in seconds, positives persist a minute")
	void presenceExpirySplit() {
		AtomicLong nanos = new AtomicLong();
		Ticker ticker = nanos::get;
		Cache<String, Boolean> cache = Caffeine.newBuilder()
				.maximumSize(10)
				.expireAfter(BudgetEnforcer.presenceExpiry())
				.ticker(ticker)
				.build();
		cache.put("neg", Boolean.FALSE);
		cache.put("pos", Boolean.TRUE);
		nanos.addAndGet(Duration.ofSeconds(6).toNanos());
		cache.cleanUp();
		assertNull(cache.getIfPresent("neg"));
		assertTrue(cache.getIfPresent("pos"));
		nanos.addAndGet(Duration.ofSeconds(60).toNanos());
		cache.cleanUp();
		assertNull(cache.getIfPresent("pos"));
	}

	@Test
	@DisplayName("idempotency key is forwarded to the script as a namespaced claim")
	void idempotencyKeyForwarded() {
		when(redisTemplate.execute(any(), anyList(), anyString(), anyString(), anyString(),
						anyString(), anyString(), anyString()))
				.thenReturn(List.of(1L, 0L, 500L, 60L, 3L, "2026-09"));
		when(costCalculator.calculate(any(), any(), anyLong(), anyLong())).thenReturn(4_000L);
		SHA256Hash hash = SHA256Hash.fromRawKey("gw-55555555555555555555555555555555");

		BudgetDecision decision = enforcer.checkBudget(
				hash, "owner-1",
				ProviderType.OPENAI, "gpt-5.6-luna", 10, "idem-1", "body-sha-1");

		assertTrue(decision instanceof BudgetDecision.Allowed);
		verify(redisTemplate).execute(any(), anyList(), eq("4000"),
				eq(BudgetEnforcer.dedupeClaimId("owner-1", hash.hex(), "body-sha-1", "idem-1")),
				eq(hash.hex()), eq("owner-1"), eq("global"), eq("{b:global}"));
	}

	@Test
	@DisplayName("FS-B12: claim ids namespace blanks and nulls without throwing")
	void dedupeClaimIdNamespacesMissingComponents() {
		assertEquals(":k:b:i", BudgetEnforcer.dedupeClaimId("  ", "k", "b", "i"));
		assertEquals(":::", BudgetEnforcer.dedupeClaimId(null, null, null, null));
		assertEquals("t::b:", BudgetEnforcer.dedupeClaimId("t", null, "b", null));
	}

	@Test
	@DisplayName("FS-B12: releasing absent claims is a silent no-op")
	void releaseAbsentClaimNoOp() {
		enforcer.releaseIdempotencyClaim(null);
		enforcer.releaseIdempotencyClaim("   ");

		verify(redisTemplate, never()).delete(anyString());
	}

	@Test
	@DisplayName("FIN-B20: renewHold refreshes the TTL and re-arms the expiry index")
	@SuppressWarnings("unchecked")
	void renewHoldRefreshesTtl() {
		ZSetOperations<String, String> zset = mock(ZSetOperations.class);
		when(redisTemplate.opsForZSet()).thenReturn(zset);
		when(redisTemplate.expire(eq(BudgetEnforcer.holdKey("hold-1")), eq(Duration.ofSeconds(3600L))))
				.thenReturn(Boolean.TRUE);

		assertTrue(enforcer.renewHold("hold-1", 3600L));

		verify(zset).add(eq(BudgetEnforcer.holdExpiryKey()), eq(BudgetEnforcer.holdKey("hold-1")),
				anyDouble());
	}

	@Test
	@DisplayName("FIN-B20: renewHold reports a gone hold without touching the index")
	@SuppressWarnings("unchecked")
	void renewHoldReportsGoneHold() {
		ZSetOperations<String, String> zset = mock(ZSetOperations.class);
		when(redisTemplate.opsForZSet()).thenReturn(zset);
		when(redisTemplate.expire(eq(BudgetEnforcer.holdKey("hold-gone")), any(Duration.class)))
				.thenReturn(Boolean.FALSE);

		assertFalse(enforcer.renewHold("hold-gone", 3600L));

		verify(zset, never()).add(anyString(), anyString(), anyDouble());
	}

	@Test
	@DisplayName("FIN-B20: renewHold fails closed when Redis is down")
	void renewHoldFailsClosed() {
		when(redisTemplate.expire(anyString(), any(Duration.class)))
				.thenThrow(new RedisConnectionFailureException("down"));

		assertThrows(RateLimitUnavailableException.class, () -> enforcer.renewHold("hold-1", 3600L));
	}

	@Test
	@DisplayName("present claims are deleted on release")
	void releasedPresentClaimDeletesIt() {
		enforcer.releaseIdempotencyClaim("claim-1");

		verify(redisTemplate).delete(BudgetEnforcer.dedupeKey("claim-1"));
	}

	@Test
	@DisplayName("release failures degrade silently (TTL bounds the stale claim)")
	void releaseFailureDegradesSilently() {
		when(redisTemplate.delete(anyString())).thenThrow(new RedisConnectionFailureException("down"));

		assertDoesNotThrow(() -> enforcer.releaseIdempotencyClaim("claim-1"));
	}

	@Test
	@DisplayName("settle rejects malformed script shapes fail-closed")
	void settleRejectsMalformedShape() {
		when(redisTemplate.execute(any(), anyList(), any(), any(), any()))
				.thenReturn(List.of(1L));

		assertThrows(RateLimitUnavailableException.class, () -> enforcer.settle(
				"hold-1", "KEY", "sub", "owner-1", "hex", "2026-09", "2026-09", 100L, 0L));
	}

	@Test
	@DisplayName("settle accepts string wire values like integers")
	void settleAcceptsStringWireValues() {
		when(redisTemplate.execute(any(), anyList(), any(), any(), any()))
				.thenReturn(List.of("1", "0", "500", "60", "2"));

		BudgetEnforcer.SettleOutcome outcome = enforcer.settle(
				"hold-1", "KEY", "sub", "owner-1", "hex", "2026-09", "2026-09", 100L, 0L);

		assertFalse(outcome.gapSet());
	}

	@Test
	@DisplayName("settle rejects non-numeric and foreign wire values fail-closed")
	void settleRejectsBadWireValues() {
		when(redisTemplate.execute(any(), anyList(), any(), any(), any()))
				.thenReturn(List.of("1", "0", "nope", "60", "2"));

		assertThrows(RateLimitUnavailableException.class, () -> enforcer.settle(
				"hold-1", "KEY", "sub", "owner-1", "hex", "2026-09", "2026-09", 100L, 0L));

		when(redisTemplate.execute(any(), anyList(), any(), any(), any()))
				.thenReturn(List.of(1L, 0L, true, 60L, 2L));

		assertThrows(RateLimitUnavailableException.class, () -> enforcer.settle(
				"hold-1", "KEY", "sub", "owner-1", "hex", "2026-09", "2026-09", 100L, 0L));
	}

	@Test
	@DisplayName("checkBudget rejects non-numeric values and blank months fail-closed")
	void checkBudgetRejectsBadWireValues() {
		when(costCalculator.calculate(any(), any(), anyLong(), anyLong())).thenReturn(4_000L);
		when(redisTemplate.execute(any(), anyList(), any(), any(), any(), any(), any(), any()))
				.thenReturn(List.of("1", "0", "nope", "60", "2", "2026-09"));

		assertThrows(RateLimitUnavailableException.class, () -> enforcer.checkBudget(
				keyHash(), "owner-1", ProviderType.OPENAI, "gpt-5.6-luna", 10, null, null));

		when(redisTemplate.execute(any(), anyList(), any(), any(), any(), any(), any(), any()))
				.thenReturn(List.of(1L, 0L, 500L, 60L, 2L, "  "));

		assertThrows(RateLimitUnavailableException.class, () -> enforcer.checkBudget(
				keyHash(), "owner-1", ProviderType.OPENAI, "gpt-5.6-luna", 10, null, null));
	}

	@Test
	@DisplayName("rearmHold re-adds the expiry score and fails closed when Redis is down")
	@SuppressWarnings("unchecked")
	void rearmHoldAddsAndFailsClosed() {
		ZSetOperations<String, String> zset = mock(ZSetOperations.class);
		when(redisTemplate.opsForZSet()).thenReturn(zset);

		enforcer.rearmHold(BudgetEnforcer.holdKey("hold-1"), 123L);

		verify(zset).add(BudgetEnforcer.holdExpiryKey(), BudgetEnforcer.holdKey("hold-1"), 123.0);

		when(redisTemplate.opsForZSet()).thenThrow(new RedisConnectionFailureException("down"));

		assertThrows(RateLimitUnavailableException.class,
				() -> enforcer.rearmHold(BudgetEnforcer.holdKey("hold-1"), 123L));
	}
}
