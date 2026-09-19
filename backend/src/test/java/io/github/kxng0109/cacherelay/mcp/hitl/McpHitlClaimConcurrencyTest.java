package io.github.kxng0109.cacherelay.mcp.hitl;

import com.redis.testcontainers.RedisContainer;
import io.github.kxng0109.cacherelay.config.SensitiveString;
import io.github.kxng0109.cacherelay.contracts.SHA256Hash;
import io.github.kxng0109.cacherelay.contracts.VirtualApiKey;
import io.github.kxng0109.cacherelay.mcp.config.McpGatewayProperties;
import io.github.kxng0109.cacherelay.mcp.contracts.McpJsonRpcRequest;
import io.github.kxng0109.cacherelay.mcp.contracts.McpJsonRpcResponse;
import io.github.kxng0109.cacherelay.mcp.contracts.McpServerConfig;
import io.github.kxng0109.cacherelay.mcp.contracts.McpTransportType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

import java.net.URI;
import java.time.Instant;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

@Testcontainers(disabledWithoutDocker = true)
@DisplayName("HITL approval claim atomicity against real Redis (SEC-02)")
class McpHitlClaimConcurrencyTest {

	@Container
	static final RedisContainer REDIS =
			new RedisContainer(DockerImageName.parse("redis:8.10.1-alpine3.23"));

	private final ObjectMapper objectMapper = new ObjectMapper();

	private StringRedisTemplate redisTemplate;
	private McpHitlSuspensionEngine suspensionEngine;
	private McpServerConfig hitlServer;
	private VirtualApiKey apiKey;

	@BeforeEach
	void setUp() {
		LettuceConnectionFactory factory = new LettuceConnectionFactory(
				REDIS.getHost(), REDIS.getMappedPort(6379));
		factory.afterPropertiesSet();
		redisTemplate = new StringRedisTemplate(factory);
		redisTemplate.afterPropertiesSet();
		redisTemplate.getConnectionFactory().getConnection().serverCommands().flushDb();

		McpGatewayProperties properties = new McpGatewayProperties();
		properties.setHitlSecret(new SensitiveString("test-hitl-secret-32-bytes-minimum!!"));
		McpAeadResumptionTokenService tokenService =
				new McpAeadResumptionTokenService(properties, objectMapper);
		suspensionEngine = new McpHitlSuspensionEngine(
				properties, tokenService, redisTemplate, objectMapper);

		hitlServer = new McpServerConfig(
				"postgres",
				McpTransportType.STREAMABLE_HTTP,
				URI.create("http://localhost:8081"),
				null,
				null,
				null,
				Set.of(),
				Set.of(),
				Set.of("execute_sql"),
				100,
				true
		);

		apiKey = new VirtualApiKey(
				SHA256Hash.fromRawKey("gw-hitl-race-test-key-0123456789ab"),
				"gw-",
				"tenant-corp",
				"hitl-race",
				100,
				1000,
				Set.of(),
				Set.of(),
				Set.of(),
				Set.of(),
				true,
				Instant.now()
		);
	}

	private McpJsonRpcRequest resumptionRequest(String token) {
		ObjectNode params = objectMapper.createObjectNode();
		params.put("name", "postgres__execute_sql");
		params.put("requestState", token);
		params.putObject("arguments").put("sql", "DROP TABLE users");
		return new McpJsonRpcRequest(
				"2.0",
				objectMapper.getNodeFactory().numberNode(1),
				"tools/call",
				params
		);
	}

	@Test
	@DisplayName("32 concurrent resumptions of one approval yield exactly one execution")
	@Timeout(value = 30, unit = TimeUnit.SECONDS)
	void concurrentResumptionsYieldSingleExecution() throws Exception {
		String argsSha = McpAeadResumptionTokenService.computeArgsSha256("{\"sql\":\"DROP TABLE users\"}");
		Instant now = Instant.now();
		McpResumptionClaims claims = new McpResumptionClaims(
				"tok-race-1",
				"tenant-corp",
				"postgres__execute_sql",
				argsSha,
				now,
				now.plusSeconds(300)
		);
		McpGatewayProperties properties = new McpGatewayProperties();
		properties.setHitlSecret(new SensitiveString("test-hitl-secret-32-bytes-minimum!!"));
		String token = new McpAeadResumptionTokenService(properties, objectMapper).mintToken(claims);

		redisTemplate.opsForValue().set("mcp:hitl:approved:tok-race-1", "APPROVED", 300, TimeUnit.SECONDS);
		redisTemplate.opsForValue().set("mcp:hitl:pending:tok-race-1", "{}", 300, TimeUnit.SECONDS);

		int racers = 32;
		CountDownLatch startGate = new CountDownLatch(1);
		CountDownLatch doneGate = new CountDownLatch(racers);
		AtomicInteger executions = new AtomicInteger();
		AtomicInteger suspensions = new AtomicInteger();

		try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
			for (int i = 0; i < racers; i++) {
				executor.submit(() -> {
					try {
						startGate.await();
						Optional<McpJsonRpcResponse> outcome = suspensionEngine.evaluateOrSuspend(
								resumptionRequest(token),
								hitlServer,
								"execute_sql",
								"postgres__execute_sql",
								apiKey
						);
						if (outcome.isEmpty()) {
							executions.incrementAndGet();
						} else {
							suspensions.incrementAndGet();
						}
					} catch (Exception e) {
						suspensions.incrementAndGet();
					} finally {
						doneGate.countDown();
					}
				});
			}
			startGate.countDown();
			assertThat(doneGate.await(20, TimeUnit.SECONDS)).isTrue();
		}

		assertThat(executions.get()).as("exactly one winner executes").isEqualTo(1);
		assertThat(suspensions.get()).as("losers re-suspend").isEqualTo(racers - 1);
		assertThat(redisTemplate.hasKey("mcp:hitl:approved:tok-race-1")).isFalse();
	}

	@Test
	@DisplayName("missing approval denies without executing")
	void missingApprovalDenies() {
		String argsSha = McpAeadResumptionTokenService.computeArgsSha256("{\"sql\":\"DROP TABLE users\"}");
		Instant now = Instant.now();
		McpResumptionClaims claims = new McpResumptionClaims(
				"tok-missing-1",
				"tenant-corp",
				"postgres__execute_sql",
				argsSha,
				now,
				now.plusSeconds(300)
		);
		McpGatewayProperties properties = new McpGatewayProperties();
		properties.setHitlSecret(new SensitiveString("test-hitl-secret-32-bytes-minimum!!"));
		String token = new McpAeadResumptionTokenService(properties, objectMapper).mintToken(claims);

		Optional<McpJsonRpcResponse> outcome = suspensionEngine.evaluateOrSuspend(
				resumptionRequest(token),
				hitlServer,
				"execute_sql",
				"postgres__execute_sql",
				apiKey
		);

		assertThat(outcome).as("missing approval re-suspends, never executes").isPresent();
	}
}
