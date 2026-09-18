package io.github.kxng0109.cacherelay.security;

import com.redis.testcontainers.RedisContainer;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Proves the development CORS allow-list exists only under the {@code dev} profile.
 *
 * <p>Uses the repo's established live-server pattern ({@code SpringBootTest + LocalServerPort} with JDK
 * {@link HttpClient}): CORS preflight is answered by the security filter chain, so only the full context is an
 * honest test. The {@code DevCorsConfig} bean registers under {@code dev} and is absent otherwise, which the
 * preflight assertions below lock in from both sides.
 */
@DisplayName("Development CORS allow-list")
@Testcontainers
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("dev")
class DevCorsTest {

	private static final String DEV_ORIGIN = "http://localhost:5173";

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

	@LocalServerPort
	private int port;

	@Test
	@DisplayName("preflight from the dev origin succeeds with credentials headers")
	void preflightFromDevOriginSucceeds() throws Exception {
		HttpResponse<Void> response = preflight(DEV_ORIGIN, "POST");
		assertThat(response.statusCode()).as("dev preflight status").isEqualTo(200);
		assertThat(response.headers().firstValue("Access-Control-Allow-Origin"))
				.as("echoed origin")
				.hasValue(DEV_ORIGIN);
		assertThat(response.headers().firstValue("Access-Control-Allow-Credentials"))
				.as("credentials flag")
				.hasValue("true");
		assertThat(response.headers().firstValue("Access-Control-Expose-Headers"))
				.as("exposed operational headers")
				.hasValueSatisfying(value -> assertThat(value)
						.contains("X-RateLimit-Remaining-RPM")
						.contains("X-CacheRelay-Similarity-Score")
						.contains("X-Budget-Remaining")
						.contains("X-Budget-Held-Micros")
						.contains("X-CacheRelay-Provider")
						.contains("X-CacheRelay-Tried"));
	}

	@Test
	@DisplayName("preflight from an unlisted origin is refused")
	void preflightFromUnlistedOriginRefused() throws Exception {
		HttpResponse<Void> response = preflight("http://evil.example:9999", "POST");
		assertThat(response.statusCode()).as("unlisted-origin preflight status").isEqualTo(403);
	}

	@Test
	@DisplayName("actual request still authenticates but carries CORS headers")
	void actualRequestCarriesCorsHeaders() throws Exception {
		HttpRequest request = HttpRequest.newBuilder()
				.uri(URI.create("http://localhost:" + port + "/v1/chat/completions"))
				.header("Origin", DEV_ORIGIN)
				.POST(HttpRequest.BodyPublishers.noBody())
				.build();
		HttpResponse<Void> response = HttpClient.newHttpClient().send(request, HttpResponse.BodyHandlers.discarding());
		assertThat(response.statusCode()).as("unauthenticated gateway status").isEqualTo(401);
		assertThat(response.headers().firstValue("Access-Control-Allow-Origin"))
				.as("echoed origin on actual request")
				.hasValue(DEV_ORIGIN);
	}

	private HttpResponse<Void> preflight(String origin, String requestMethod) throws Exception {
		HttpRequest request = HttpRequest.newBuilder()
				.uri(URI.create("http://localhost:" + port + "/v1/chat/completions"))
				.header("Origin", origin)
				.header("Access-Control-Request-Method", requestMethod)
				.method("OPTIONS", HttpRequest.BodyPublishers.noBody())
				.build();
		return HttpClient.newHttpClient().send(request, HttpResponse.BodyHandlers.discarding());
	}
}
