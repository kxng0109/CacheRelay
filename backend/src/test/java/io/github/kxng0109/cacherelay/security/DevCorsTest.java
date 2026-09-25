package io.github.kxng0109.cacherelay.security;

import io.github.kxng0109.cacherelay.SharedContainersBase;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalManagementPort;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.List;

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
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("dev")
class DevCorsTest extends SharedContainersBase {

	private static final String DEV_ORIGIN = "http://localhost:5173";

	@DynamicPropertySource
	static void sharedContainers(DynamicPropertyRegistry registry) {
		registry.add("spring.datasource.url", SharedContainersBase::postgresJdbcUrl);
		registry.add("spring.datasource.username", SharedContainersBase::postgresUsername);
		registry.add("spring.datasource.password", SharedContainersBase::postgresPassword);
		registry.add("spring.data.redis.host", SharedContainersBase::redisHost);
		registry.add("spring.data.redis.port", SharedContainersBase::redisPort);
	}

	@LocalServerPort
	private int port;

	@LocalManagementPort
	private int managementPort;

	@Test
	@DisplayName("management actuator preflight from the dev origin succeeds read-only")
	void managementActuatorPreflightFromDevOriginSucceeds() throws Exception {
		for (String path : List.of("/actuator/health", "/actuator/prometheus")) {
			HttpRequest request = HttpRequest.newBuilder()
					.uri(URI.create("http://localhost:" + managementPort + path))
					.header("Origin", DEV_ORIGIN)
					.header("Access-Control-Request-Method", "GET")
					.method("OPTIONS", HttpRequest.BodyPublishers.noBody())
					.build();
			HttpResponse<Void> response = HttpClient.newHttpClient().send(request,
					HttpResponse.BodyHandlers.discarding());

			assertThat(response.statusCode()).as("mgmt preflight status for " + path).isEqualTo(200);
			assertThat(response.headers().firstValue("Access-Control-Allow-Origin"))
					.as("echoed origin for " + path)
					.hasValue(DEV_ORIGIN);
		}
	}

	@Test
	@DisplayName("management actuator preflight refuses non-GET methods")
	void managementActuatorPreflightRefusesPost() throws Exception {
		HttpRequest request = HttpRequest.newBuilder()
				.uri(URI.create("http://localhost:" + managementPort + "/actuator/health"))
				.header("Origin", DEV_ORIGIN)
				.header("Access-Control-Request-Method", "POST")
				.method("OPTIONS", HttpRequest.BodyPublishers.noBody())
				.build();
		HttpResponse<Void> response = HttpClient.newHttpClient().send(request,
				HttpResponse.BodyHandlers.discarding());

		assertThat(response.statusCode()).as("mgmt POST preflight status").isEqualTo(403);
	}

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
						.contains("X-CacheRelay-Tried")
						.contains("X-CacheRelay-Audit-Receipt")
						.contains("X-No-Storage")
						.contains("Idempotent-Replayed"));
	}

	@Test
	@DisplayName("act-as header passes preflight")
	void actAsHeaderPassesPreflight() throws Exception {
		HttpRequest request = HttpRequest.newBuilder()
				.uri(URI.create("http://localhost:" + port + "/v1/models"))
				.header("Origin", DEV_ORIGIN)
				.header("Access-Control-Request-Method", "GET")
				.header("Access-Control-Request-Headers", "X-Act-As-Key")
				.method("OPTIONS", HttpRequest.BodyPublishers.noBody())
				.build();
		HttpResponse<Void> response = HttpClient.newHttpClient().send(request,
				HttpResponse.BodyHandlers.discarding());

		assertThat(response.statusCode()).as("act-as preflight status").isEqualTo(200);
		assertThat(response.headers().firstValue("Access-Control-Allow-Origin"))
				.as("echoed origin")
				.hasValue(DEV_ORIGIN);
	}

	@Test
	@DisplayName("refresh CSRF header passes preflight")
	void refreshHeaderPassesPreflight() throws Exception {
		HttpRequest request = HttpRequest.newBuilder()
				.uri(URI.create("http://localhost:" + port + "/v1/auth/refresh"))
				.header("Origin", DEV_ORIGIN)
				.header("Access-Control-Request-Method", "POST")
				.header("Access-Control-Request-Headers", "X-CacheRelay-Refresh")
				.method("OPTIONS", HttpRequest.BodyPublishers.noBody())
				.build();
		HttpResponse<Void> response = HttpClient.newHttpClient().send(request,
				HttpResponse.BodyHandlers.discarding());

		assertThat(response.statusCode()).as("refresh preflight status").isEqualTo(200);
		assertThat(response.headers().firstValue("Access-Control-Allow-Origin"))
				.as("echoed origin")
				.hasValue(DEV_ORIGIN);
	}

	@Test
	@DisplayName("actuator is no longer reachable from the app port (SEC-15)")
	void actuatorIsNotOnTheAppPort() throws Exception {
		// Since SEC-15 the actuator moved to the loopback-published management port:
		// the app port serves no actuator route (404, no content), and the dev CORS
		// grant for actuator paths is consumed on the management port
		// (see managementActuatorPreflight*). The path-based grant echoes the dev
		// origin even on this 404; that header alone exposes nothing.
		HttpRequest request = HttpRequest.newBuilder()
				.uri(URI.create("http://localhost:" + port + "/actuator/prometheus"))
				.header("Origin", DEV_ORIGIN)
				.GET()
				.build();
		HttpResponse<Void> response = HttpClient.newHttpClient().send(request,
				HttpResponse.BodyHandlers.discarding());

		assertThat(response.statusCode()).as("actuator on the app port").isEqualTo(404);
	}

	@Test
	@DisplayName("management actuator preflight refuses unlisted origins")
	void managementActuatorPreflightRefusesUnlistedOrigin() throws Exception {
		HttpRequest request = HttpRequest.newBuilder()
				.uri(URI.create("http://localhost:" + managementPort + "/actuator/health"))
				.header("Origin", "http://evil.example:9999")
				.header("Access-Control-Request-Method", "GET")
				.method("OPTIONS", HttpRequest.BodyPublishers.noBody())
				.build();
		HttpResponse<Void> response = HttpClient.newHttpClient().send(request,
				HttpResponse.BodyHandlers.discarding());

		assertThat(response.statusCode()).as("evil-origin mgmt preflight status").isEqualTo(403);
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
