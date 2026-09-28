package io.github.kxng0109.cacherelay.budget;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import io.github.kxng0109.cacherelay.SharedContainersBase;
import io.github.kxng0109.cacherelay.contracts.ProviderType;
import io.github.kxng0109.cacherelay.contracts.SHA256Hash;
import io.github.kxng0109.cacherelay.ledger.CostCalculator;
import io.github.kxng0109.cacherelay.security.ratelimit.RateLimitUnavailableException;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;

import java.time.YearMonth;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Proves the Lua budget script against real Redis: atomic allow/consume, first-denied-wins, check-before-increment
 * (denials consume nothing), zero-cost key silence, and month key shape. Wire-type coverage (Long vs String results) is
 * asserted through the enforcer in {@link BudgetEnforcerTest}; here the real script exercises real Redis semantics.
 */
@DisplayName("Budget Lua script against real Redis")
class BudgetLuaIntegrationTest extends SharedContainersBase {

	private static StringRedisTemplate sharedTemplate;

	private static StringRedisTemplate template() {
		if (sharedTemplate == null) {
			org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory factory =
					new org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory(
							SharedContainersBase.redisHost(), SharedContainersBase.redisPort());
			factory.afterPropertiesSet();
			sharedTemplate = new StringRedisTemplate(factory);
			sharedTemplate.afterPropertiesSet();
		}
		return sharedTemplate;
	}

	@BeforeEach
	void resetRedisState() {
		template().getConnectionFactory().getConnection().serverCommands().flushDb();
	}

	private static DefaultRedisScript<List> script() {
		DefaultRedisScript<List> script = new DefaultRedisScript<>();
		script.setLocation(new org.springframework.core.io.ClassPathResource("budget_limit.lua"));
		script.setResultType(List.class);
		return script;
	}

	private static BudgetEnforcer enforcer(StringRedisTemplate template, long estimateMicros) {
		CostCalculator calculator = mock(CostCalculator.class);
		when(calculator.calculate(any(), anyString(), anyLong(), anyLong())).thenReturn(estimateMicros);
		return new BudgetEnforcer(template, script(), holdScript(), settleScript(), calculator,
				new SimpleMeterRegistry());
	}

	private static DefaultRedisScript<List> holdScript() {
		DefaultRedisScript<List> script = new DefaultRedisScript<>();
		script.setLocation(new org.springframework.core.io.ClassPathResource("hold.lua"));
		script.setResultType(List.class);
		return script;
	}

	private static DefaultRedisScript<List> settleScript() {
		DefaultRedisScript<List> script = new DefaultRedisScript<>();
		script.setLocation(new org.springframework.core.io.ClassPathResource("settle.lua"));
		script.setResultType(List.class);
		return script;
	}

	private static void seedCfg(StringRedisTemplate template, String level, String subject,
	                            long minuteMicros, long monthMicros) {
		template.opsForHash().putAll(
				BudgetEnforcer.cfgKey(level, subject),
				Map.of(
						"minute_micros", Long.toString(minuteMicros),
						"month_micros", Long.toString(monthMicros)
				)
		);
	}

	private static String hex() {
		return "ab".repeat(32);
	}

	@Test
	@DisplayName("allowed requests consume spend and report remaining")
	void allowedConsumesAndReports() {
		StringRedisTemplate template = template();
		template.getConnectionFactory().getConnection().serverCommands().flushDb();
		seedCfg(template, "KEY", hex(), 1_000_000L, 100_000_000L);
		Object cfgReadback = template.opsForHash().get(BudgetEnforcer.cfgKey("KEY", hex()), "minute_micros");
		assertEquals("1000000", String.valueOf(cfgReadback), "seeded config is visible to the script");
		BudgetEnforcer enforcer = enforcer(template, 60_000L);

		BudgetDecision first = enforcer.checkBudget(
				SHA256Hash.fromHex(hex()), "owner-1", ProviderType.OPENAI, "gpt-5.6-luna", 10, null, null);

		assertTrue(first instanceof BudgetDecision.Allowed allowed);
		assertEquals(940_000L, ((BudgetDecision.Allowed) first).remainingMicros());
		String minuteKey = BudgetEnforcer.minuteKey(
				"KEY", hex(),
				System.currentTimeMillis() / 60_000L
		);
		assertEquals("60000", template.opsForValue().get(minuteKey));
	}

	@Test
	@DisplayName("denials consume nothing and first-denied level wins")
	void deniedConsumesNothingAndFirstWins() {
		StringRedisTemplate template = template();
		template.getConnectionFactory().getConnection().serverCommands().flushDb();
		seedCfg(template, "KEY", hex(), 100_000L, 100_000_000L);
		seedCfg(template, "TEAM", "tenant-a", 0L, 100_000_000L);
		BudgetEnforcer enforcer = enforcer(template, 60_000L);

		BudgetDecision first = enforcer.checkBudget(
				SHA256Hash.fromHex(hex()), "tenant-a", ProviderType.OPENAI, "gpt-5.6-luna", 10, null, null);
		assertTrue(first instanceof BudgetDecision.Allowed);

		BudgetDecision second = enforcer.checkBudget(
				SHA256Hash.fromHex(hex()), "tenant-a", ProviderType.OPENAI, "gpt-5.6-luna", 10, null, null);
		assertTrue(second instanceof BudgetDecision.Denied denied);
		assertEquals("KEY", ((BudgetDecision.Denied) second).level());
		assertEquals("MINUTE", ((BudgetDecision.Denied) second).window());

		String minuteKey = BudgetEnforcer.minuteKey(
				"KEY", hex(),
				System.currentTimeMillis() / 60_000L
		);
		assertEquals("60000", template.opsForValue().get(minuteKey));
		assertFalse(template.hasKey(BudgetEnforcer.minuteKey(
				"TEAM", "tenant-a",
				System.currentTimeMillis() / 60_000L
		)));
	}

	@Test
	@DisplayName("zero-cost requests create no keys")
	void zeroCostCreatesNoKeys() {
		StringRedisTemplate template = template();
		template.getConnectionFactory().getConnection().serverCommands().flushDb();
		seedCfg(template, "KEY", hex(), 100_000L, 100_000_000L);
		BudgetEnforcer enforcer = enforcer(template, 0L);

		BudgetDecision decision = enforcer.checkBudget(
				SHA256Hash.fromHex(hex()), "owner-1", ProviderType.OLLAMA, "local-llama", 10, null, null);

		assertTrue(decision instanceof BudgetDecision.Allowed);
		assertFalse(template.hasKey(BudgetEnforcer.minuteKey(
				"KEY", hex(),
				System.currentTimeMillis() / 60_000L
		)));
	}

	@Test
	@DisplayName("month counters use the UTC calendar month key")
	void monthKeyShape() {
		StringRedisTemplate template = template();
		template.getConnectionFactory().getConnection().serverCommands().flushDb();
		seedCfg(template, "KEY", hex(), 0L, 100_000_000L);
		BudgetEnforcer enforcer = enforcer(template, 60_000L);

		enforcer.checkBudget(SHA256Hash.fromHex(hex()), "owner-1", ProviderType.OPENAI, "gpt-5.6-luna", 10, null, null);

		String expected = BudgetEnforcer.monthKey("KEY", hex(), YearMonth.now(ZoneOffset.UTC).toString());
		assertEquals("60000", template.opsForValue().get(expected));
	}

	@Test
	@DisplayName("presence cache is instance-local and invalidatable")
	void presenceCacheInvalidates() {
		Cache<String, Boolean> probe = Caffeine.newBuilder()
		                                                          .maximumSize(10)
		                                                          .build();
		probe.put("k", Boolean.TRUE);
		probe.invalidate("k");
		assertTrue(probe.getIfPresent("k") == null);
	}

	@Test
	@DisplayName("absurd estimates deny without consuming")
	void absurdEstimateDeniesWithoutConsuming() {
		StringRedisTemplate template = template();
		template.getConnectionFactory().getConnection().serverCommands().flushDb();
		seedCfg(template, "KEY", hex(), 1_000_000L, 100_000_000L);
		DefaultRedisScript<List> script = script();
		List<String> keys = List.of(
				BudgetEnforcer.cfgKey("KEY", hex()),
				"",
				"");
		List<?> result = template.execute(script, keys,
				"99999999999999999999", "", hex(), "", "global", "{b:global}");
		assertEquals(6, result.size());
		long[] actual = new long[5];
		for (int i = 0; i < 5; i++) {
			Object value = result.get(i);
			actual[i] = value instanceof Number number ? number.longValue()
					: Long.parseLong(String.valueOf(value).trim());
		}
		assertArrayEquals(new long[]{0L, 1L, 0L, 60L, 2L}, actual);
		assertEquals(redisYearMonth(template), String.valueOf(result.get(5)));
	}

	@Test
	@DisplayName("FIN-B22: windows and month derive from Redis TIME, not the pod clock")
	void luaDerivesWindowFromRedisTime() {
		StringRedisTemplate template = template();
		template.getConnectionFactory().getConnection().serverCommands().flushDb();
		seedCfg(template, "KEY", hex(), 1_000_000L, 100_000_000L);
		DefaultRedisScript<List> script = script();
		List<String> keys = List.of(
				BudgetEnforcer.cfgKey("KEY", hex()),
				"",
				"");
		List<?> result = template.execute(script, keys,
				"60000", "", hex(), "", "global", "{b:global}");

		assertEquals(6, result.size());
		long redisSeconds = redisTimeSeconds(template);
		String expectedMinuteKey = BudgetEnforcer.minuteKey("KEY", hex(), redisSeconds / 60L);
		assertEquals("60000", template.opsForValue().get(expectedMinuteKey));
		String expectedMonthKey = BudgetEnforcer.monthKey("KEY", hex(), redisYearMonth(template));
		assertEquals("60000", template.opsForValue().get(expectedMonthKey));
		assertEquals(redisYearMonth(template), String.valueOf(result.get(5)));
	}

	private static long redisTimeSeconds(StringRedisTemplate template) {
		// Spring's serverCommands().time() folds TIME into epoch millis; the Lua
		// script divides TIME[1] seconds itself, so normalize here to seconds.
		Long millis = template.execute(
				(org.springframework.data.redis.core.RedisCallback<Long>) connection ->
						connection.serverCommands().time());
		assertThat(millis).as("Redis TIME available").isNotNull().isPositive();
		return millis / 1000L;
	}

	private static String redisYearMonth(StringRedisTemplate template) {
		return YearMonth.from(
				java.time.Instant.ofEpochSecond(redisTimeSeconds(template)).atZone(ZoneOffset.UTC))
				.toString();
	}

	@Test
	@DisplayName("duplicate claim still evaluates caps and charges every attempt")
	void duplicateClaimEvaluatesCapsAndCharges() {
		// FIN-B12: a retried key with no replay hit is new upstream work, so every
		// attempt consumes budget (fail-closed). Free retries come from the replay
		// store, never from the spend gate.
		StringRedisTemplate template = template();
		template.getConnectionFactory().getConnection().serverCommands().flushDb();
		seedCfg(template, "KEY", hex(), 1_000_000L, 100_000_000L);
		BudgetEnforcer enforcer = enforcer(template, 60_000L);
		String idempotencyKey = UUID.randomUUID().toString();

		BudgetDecision first = enforcer.checkBudget(
				SHA256Hash.fromHex(hex()), "owner-1", ProviderType.OPENAI, "gpt-5.6-luna", 10, idempotencyKey, "body-sha-1");
		BudgetDecision second = enforcer.checkBudget(
				SHA256Hash.fromHex(hex()), "owner-1", ProviderType.OPENAI, "gpt-5.6-luna", 10, idempotencyKey, "body-sha-1");

		assertThat(first).as("first attempt").isInstanceOf(BudgetDecision.Allowed.class);
		assertThat(second).as("retry without replay hit").isInstanceOf(BudgetDecision.Allowed.class);
		String month = YearMonth.now(ZoneOffset.UTC).toString();
		assertThat(template.opsForValue().get(BudgetEnforcer.monthKey("KEY", hex(), month)))
				.as("both attempts charged")
				.isEqualTo("120000");
	}

	@Test
	@DisplayName("same key with a different body is a separate namespaced spend")
	void sameKeyDifferentBodyChargedSeparately() {
		StringRedisTemplate template = template();
		template.getConnectionFactory().getConnection().serverCommands().flushDb();
		seedCfg(template, "KEY", hex(), 1_000_000L, 100_000_000L);
		BudgetEnforcer enforcer = enforcer(template, 60_000L);
		String idempotencyKey = UUID.randomUUID().toString();

		BudgetDecision first = enforcer.checkBudget(
				SHA256Hash.fromHex(hex()), "owner-1", ProviderType.OPENAI, "gpt-5.6-luna", 10,
				idempotencyKey, "body-sha-1");
		BudgetDecision second = enforcer.checkBudget(
				SHA256Hash.fromHex(hex()), "owner-1", ProviderType.OPENAI, "gpt-5.6-luna", 10,
				idempotencyKey, "body-sha-2");

		assertThat(first).as("first body").isInstanceOf(BudgetDecision.Allowed.class);
		assertThat(second).as("different body").isInstanceOf(BudgetDecision.Allowed.class);
		String month = YearMonth.now(ZoneOffset.UTC).toString();
		assertThat(template.opsForValue().get(BudgetEnforcer.monthKey("KEY", hex(), month)))
				.as("both bodies charged")
				.isEqualTo("120000");
		assertThat(template.hasKey(BudgetEnforcer.dedupeKey(
				BudgetEnforcer.dedupeClaimId("owner-1", hex(), "body-sha-1", idempotencyKey))))
				.as("first body claim namespaced").isTrue();
		assertThat(template.hasKey(BudgetEnforcer.dedupeKey(
				BudgetEnforcer.dedupeClaimId("owner-1", hex(), "body-sha-2", idempotencyKey))))
				.as("second body claim namespaced").isTrue();
	}

	@Test
	@Timeout(value = 60, unit = TimeUnit.SECONDS)
	@DisplayName("50 concurrent duplicate claims never yield uncharged work")
	void concurrentDuplicateClaimsAllCharged() throws Exception {
		StringRedisTemplate template = template();
		template.getConnectionFactory().getConnection().serverCommands().flushDb();
		seedCfg(template, "KEY", hex(), 100_000_000L, 10_000_000_000L);
		BudgetEnforcer enforcer = enforcer(template, 60_000L);
		String idempotencyKey = UUID.randomUUID().toString();

		int tasks = 50;
		AtomicInteger allowed = new AtomicInteger();
		CountDownLatch startGate = new CountDownLatch(1);
		CountDownLatch completionGate = new CountDownLatch(tasks);
		try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
			for (int i = 0; i < tasks; i++) {
				executor.submit(() -> {
					try {
						startGate.await();
						BudgetDecision decision = enforcer.checkBudget(
								SHA256Hash.fromHex(hex()), "owner-1", ProviderType.OPENAI,
								"gpt-5.6-luna", 10, idempotencyKey, "body-sha-1");
						if (decision instanceof BudgetDecision.Allowed) {
							allowed.incrementAndGet();
						}
					} catch (InterruptedException interrupted) {
						Thread.currentThread().interrupt();
					} finally {
						completionGate.countDown();
					}
				});
			}
			startGate.countDown();
			assertThat(completionGate.await(30, TimeUnit.SECONDS)).as("all tasks complete").isTrue();
		}

		assertThat(allowed.get()).as("allowed attempts").isEqualTo(tasks);
		String month = YearMonth.now(ZoneOffset.UTC).toString();
		assertThat(template.opsForValue().get(BudgetEnforcer.monthKey("KEY", hex(), month)))
				.as("every attempt charged exactly once")
				.isEqualTo(String.valueOf(60_000L * tasks));
	}

	@Test
	@DisplayName("hold TTL is clamped below the settled-flag TTL")
	void holdTtlClampedBelowFlagTtl() {
		StringRedisTemplate template = template();
		template.getConnectionFactory().getConnection().serverCommands().flushDb();
		BudgetEnforcer enforcer = enforcer(template, 60_000L);

		assertThat(enforcer.createHold("h-clamp", "KEY:hex", 100L, "2026-09",
				1_700_000_000L, 86_400L)).as("hold created").isTrue();
		Long ttl = template.getExpire(BudgetEnforcer.holdKey("h-clamp"));
		assertThat(ttl).as("applied hold TTL").isNotNull().isLessThanOrEqualTo(86_100L);
	}

	@Test
	@DisplayName("non-positive hold TTL is rejected fail-closed")
	void nonPositiveHoldTtlRejected() {
		StringRedisTemplate template = template();
		template.getConnectionFactory().getConnection().serverCommands().flushDb();
		BudgetEnforcer enforcer = enforcer(template, 60_000L);

		assertThatThrownBy(() -> enforcer.createHold("h-bad", "KEY:hex", 100L, "2026-09",
				1_700_000_000L, 0L))
				.as("zero TTL")
				.isInstanceOf(RateLimitUnavailableException.class);
		assertThatThrownBy(() -> enforcer.createHold("h-bad", "KEY:hex", 100L, "2026-09",
				1_700_000_000L, -5L))
				.as("negative TTL")
				.isInstanceOf(RateLimitUnavailableException.class);
	}
}
