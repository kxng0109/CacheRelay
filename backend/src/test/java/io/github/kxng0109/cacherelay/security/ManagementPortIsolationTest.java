package io.github.kxng0109.cacherelay.security;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;

import com.redis.testcontainers.RedisContainer;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalManagementPort;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

/**
 * Proves the SEC-15 closure end-to-end: actuator endpoints (health, probe subpaths,
 * prometheus metrics) are served exclusively on the dedicated management port, and
 * the application port serves no actuator route at all.
 *
 * <p>The management port is random in tests (surefire system property
 * {@code management.server.port=0}); production defaults to 9091, published on host
 * loopback only and scraped by Prometheus over the compose network.</p>
 *
 * <p>Redis connection details are injected via {@code DynamicPropertySource} and
 * PostgreSQL via {@code @ServiceConnection}, matching the repository's established
 * live-server test pattern so the aggregate health indicator can reach UP.</p>
 */
@DisplayName("Management port isolation (SEC-15)")
@Testcontainers
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class ManagementPortIsolationTest {

	@Container
	@ServiceConnection
	static final PostgreSQLContainer POSTGRES =
			new PostgreSQLContainer(DockerImageName.parse("postgres:16.15-alpine"));

	@Container
	static final RedisContainer REDIS =
			new RedisContainer(DockerImageName.parse("redis:8.10.1-alpine3.23"));

	@DynamicPropertySource
	static void redisProperties(DynamicPropertyRegistry registry) {
		registry.add("spring.data.redis.host", REDIS::getHost);
		registry.add("spring.data.redis.port", () -> REDIS.getMappedPort(6379));
	}

	private static final HttpClient CLIENT = HttpClient.newBuilder()
			.connectTimeout(Duration.ofSeconds(5))
			.build();

	@LocalServerPort
	private int port;

	@LocalManagementPort
	private int managementPort;

	@Test
	@DisplayName("the app port serves no actuator route")
	void appPortServesNoActuator() throws Exception {
		assertThat(get(port, "/actuator/health").statusCode()).isEqualTo(404);
		assertThat(get(port, "/actuator/prometheus").statusCode()).isEqualTo(404);
		assertThat(get(port, "/actuator/health/liveness").statusCode()).isEqualTo(404);
	}

	@Test
	@DisplayName("the management port serves health and prometheus")
	void managementPortServesObservability() throws Exception {
		awaitStatus("/actuator/health", 200);
		HttpResponse<String> metrics = get(managementPort, "/actuator/prometheus");
		assertThat(metrics.statusCode()).isEqualTo(200);
		assertThat(metrics.headers().firstValue("Content-Type"))
				.hasValueSatisfying(value -> assertThat(value).contains("text/plain"));
		assertThat(metrics.body()).contains("jvm_");
	}

	@Test
	@DisplayName("probe subpaths are permitted on the management port")
	void probeSubpathsArePermitted() throws Exception {
		assertThat(get(managementPort, "/actuator/health/liveness").statusCode()).isEqualTo(200);
		awaitStatus("/actuator/health/readiness", 200);
	}

	private HttpResponse<String> get(int targetPort, String path) throws Exception {
		HttpRequest request = HttpRequest.newBuilder()
		                                 .uri(URI.create("http://localhost:" + targetPort + path))
		                                 .GET()
		                                 .build();
		return CLIENT.send(request, HttpResponse.BodyHandlers.ofString());
	}

	/**
	 * Polls a management-port route until it reports the expected status.
	 *
	 * <p>Only {@code 503} (transient DOWN while pools warm) and connection failures
	 * are retried; any other status fails immediately so a regression cannot be
	 * masked by the wait loop.</p>
	 *
	 * @param path     management-port path to poll
	 * @param expected expected HTTP status
	 */
	private void awaitStatus(String path, int expected) throws Exception {
		Instant deadline = Instant.now().plus(Duration.ofSeconds(30));
		int lastStatus = -1;
		while (true) {
			try {
				lastStatus = get(managementPort, path).statusCode();
				if (lastStatus == expected) {
					return;
				}
				assertThat(lastStatus).as("management route %s status", path).isEqualTo(503);
			} catch (IOException connectionLevel) {
				lastStatus = -1;
			}
			if (Instant.now().isAfter(deadline)) {
				throw new AssertionError(
						"management route " + path + " never reached " + expected + ", last " + lastStatus);
			}
			Thread.sleep(500);
		}
	}
}
