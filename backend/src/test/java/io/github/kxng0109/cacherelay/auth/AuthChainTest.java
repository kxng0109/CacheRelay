package io.github.kxng0109.cacherelay.auth;

import com.redis.testcontainers.RedisContainer;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
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
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Proves the human-auth surface end to end against a live server: bootstrap invite,
 * redemption, login, identity, refresh rotation with reuse detection, stealth-404 on the
 * control plane, and the per-response CSP nonce.
 */
@DisplayName("Auth chain end to end")
@Testcontainers
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class AuthChainTest {

	private static final HttpClient CLIENT = HttpClient.newBuilder()
			.followRedirects(HttpClient.Redirect.NEVER)
			.build();

	@Container
	@ServiceConnection
	static final PostgreSQLContainer POSTGRES =
			new PostgreSQLContainer(DockerImageName.parse("postgres:16.15-alpine"));

	@Container
	static final RedisContainer REDIS =
			new RedisContainer(DockerImageName.parse("redis:8.10.1-alpine3.23"));

	@DynamicPropertySource
	static void properties(DynamicPropertyRegistry registry) {
		registry.add("spring.data.redis.host", REDIS::getHost);
		registry.add("spring.data.redis.port", () -> REDIS.getMappedPort(6379));
		registry.add("gateway.admin.master-key", () -> "test-only-admin-master-key-32b-min!!");
	}

	@LocalServerPort
	private int port;

	@Test
	@DisplayName("unknown logins fail closed")
	void unknownLoginRejected() throws Exception {
		HttpResponse<String> response = post("/v1/auth/login",
				"{\"username\":\"ghost\",\"password\":\"whatever-12345\"}", null, null);

		assertThat(response.statusCode()).isEqualTo(401);
	}

	@Test
	@DisplayName("bootstrap invite redeems the first admin and the link dies after use")
	void bootstrapRedeemAndReuseDies() throws Exception {
		String link = createInvite(null, true);
		String token = link.substring(link.indexOf("token=") + "token=".length());

		HttpResponse<String> redeem = post("/v1/auth/redeem",
				"{\"token\":\"" + token + "\",\"username\":\"root\",\"password\":\"password-12345\"}",
				null, null);
		assertThat(redeem.statusCode()).isEqualTo(201);
		assertThat(redeem.body()).contains("accessToken");
		assertThat(extractSessionCookie(redeem)).as("refresh cookie set").isPresent();
		String access = accessToken(redeem.body());

		HttpResponse<String> me = get("/v1/auth/me", "Bearer " + access);
		assertThat(me.statusCode()).isEqualTo(200);
		assertThat(me.body()).contains("\"admin\":true");

		HttpResponse<String> replay = post("/v1/auth/redeem",
				"{\"token\":\"" + token + "\",\"username\":\"root2\",\"password\":\"password-12345\"}",
				null, null);
		assertThat(replay.statusCode()).as("consumed invite is gone").isEqualTo(410);
	}

	@Test
	@DisplayName("refresh rotates once; replay revokes the family")
	void refreshRotationAndReuse() throws Exception {
		String link = createInvite("op-" + System.nanoTime() + "@example.com", false);
		String token = link.substring(link.indexOf("token=") + "token=".length());
		HttpResponse<String> redeem = post("/v1/auth/redeem",
				"{\"token\":\"" + token + "\",\"username\":\"rot\",\"password\":\"password-12345\"}",
				null, null);
		assertThat(redeem.statusCode()).isEqualTo(201);
		String firstCookie = extractSessionCookie(redeem).orElseThrow();

		HttpResponse<String> rotated = post("/v1/auth/refresh", "", firstCookie, "1");
		assertThat(rotated.statusCode()).isEqualTo(200);
		String secondCookie = extractSessionCookie(rotated).orElseThrow();
		assertThat(secondCookie).as("cookie rotated").isNotEqualTo(firstCookie);

		HttpResponse<String> replay = post("/v1/auth/refresh", "", firstCookie, "1");
		assertThat(replay.statusCode()).as("replayed rotation revoked").isEqualTo(401);

		HttpResponse<String> afterRevoke = post("/v1/auth/refresh", "", secondCookie, "1");
		assertThat(afterRevoke.statusCode()).as("family dead after reuse").isEqualTo(401);
	}

	@Test
	@DisplayName("refresh without the CSRF header is a 400")
	void refreshRequiresHeader() throws Exception {
		HttpResponse<String> response = post("/v1/auth/refresh", "", "refresh=abc", null);

		assertThat(response.statusCode()).isEqualTo(400);
	}

	@Test
	@DisplayName("control plane hides from anonymous callers but serves admin sessions")
	void stealthControlPlane() throws Exception {
		HttpResponse<String> hidden = get("/v1/admin/keys", null);

		assertThat(hidden.statusCode()).as("anonymous admin access hides").isEqualTo(404);
		assertThat(hidden.body()).contains("No such endpoint");
	}

	@Test
	@DisplayName("every response carries a strict CSP with a fresh nonce")
	void cspNoncePresent() throws Exception {
		HttpResponse<String> first = get("/", null);
		HttpResponse<String> second = get("/", null);

		String firstCsp = first.headers().firstValue("Content-Security-Policy").orElseThrow();
		String secondCsp = second.headers().firstValue("Content-Security-Policy").orElseThrow();
		assertThat(firstCsp).contains("'strict-dynamic'").contains("object-src 'none'");
		assertThat(secondCsp).as("nonce differs per response").isNotEqualTo(firstCsp);
	}

	private String createInvite(String email, boolean admin) throws Exception {
		String emailJson = email != null ? "\"email\":\"" + email + "\"," : "";
		HttpResponse<String> response = post("/v1/admin/invites",
				"{" + emailJson + "\"admin\":" + admin + "}", "X-Admin-Key test-only-admin-master-key-32b-min!!",
				null);
		assertThat(response.statusCode()).as("invite created").isEqualTo(201);
		String body = response.body();
		int start = body.indexOf("\"link\":\"") + "\"link\":\"".length();
		int end = body.indexOf('"', start);
		return body.substring(start, end);
	}

	private HttpResponse<String> post(String path, String json, String credential,
			String refreshHeader) throws Exception {
		HttpRequest.Builder builder = HttpRequest.newBuilder()
				.uri(URI.create("http://localhost:" + port + path))
				.header("Content-Type", "application/json")
				.POST(HttpRequest.BodyPublishers.ofString(json));
		if (credential != null) {
			if (credential.startsWith("X-Admin-Key ")) {
				builder.header("X-Admin-Key",
						credential.substring("X-Admin-Key ".length()));
			} else if (credential.contains("=")) {
				builder.header("Cookie", credential);
			} else {
				builder.header("Authorization", credential);
			}
		}
		if (refreshHeader != null) {
			builder.header("X-CacheRelay-Refresh", refreshHeader);
		}
		return CLIENT.send(builder.build(), HttpResponse.BodyHandlers.ofString());
	}

	private HttpResponse<String> get(String path, String authorization) throws Exception {
		HttpRequest.Builder builder = HttpRequest.newBuilder()
				.uri(URI.create("http://localhost:" + port + path))
				.GET();
		if (authorization != null) {
			builder.header("Authorization", authorization);
		}
		return CLIENT.send(builder.build(), HttpResponse.BodyHandlers.ofString());
	}

	private Optional<String> extractSessionCookie(HttpResponse<String> response) {
		return response.headers().allValues("Set-Cookie").stream()
				.map(value -> value.split(";", 2)[0])
				.filter(value -> value.contains("=") && !value.endsWith("="))
				.findFirst();
	}

	private String accessToken(String body) {
		int start = body.indexOf("\"accessToken\":\"") + "\"accessToken\":\"".length();
		int end = body.indexOf('"', start);
		return body.substring(start, end);
	}
}
