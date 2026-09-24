package io.github.kxng0109.cacherelay.web;

import io.github.kxng0109.cacherelay.SharedContainersBase;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Proves the SPA shell fallback serves operator deep links without opening the API boundary.
 *
 * <p>Runs the default profile against the repo's established live-server pattern: the fallback lives behind the
 * security chain, so only the full context proves the two layers agree. Shell and asset bytes come from
 * {@code src/test/resources/static} fixtures — production ships the real bundle from the release pipeline.
 */
@DisplayName("SPA shell fallback")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class SpaFallbackTest extends SharedContainersBase {

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

	@Test
	@DisplayName("deep links resolve to the shell at any depth")
	void deepLinksResolveToShell() throws Exception {
		assertThat(body("GET", "/dashboard")).as("one-segment shell").contains("cacherelay-test-shell");
		assertThat(body("GET", "/dashboard/keys")).as("two-segment shell").contains("cacherelay-test-shell");
	}

	@Test
	@DisplayName("root and shell entry points serve with no-store policy")
	void rootAndShellEntryPoints() throws Exception {
		assertThat(status("GET", "/")).as("root status").isEqualTo(200);
		HttpResponse<Void> index = response("GET", "/index.html", null);
		assertThat(index.statusCode()).as("shell status").isEqualTo(200);
		assertThat(index.headers().firstValue("Cache-Control"))
				.as("shell cache policy")
				.hasValueSatisfying(value -> assertThat(value).contains("no-store"));
	}

	@Test
	@DisplayName("versioned assets serve immutable")
	void versionedAssetsImmutable() throws Exception {
		HttpResponse<Void> asset = response("GET", "/assets/test.txt", null);
		assertThat(asset.statusCode()).as("asset status").isEqualTo(200);
		assertThat(asset.headers().firstValue("Cache-Control"))
				.as("asset cache policy")
				.hasValueSatisfying(value -> assertThat(value).contains("immutable"));
	}

	@Test
	@DisplayName("unknown API routes stay refused with the fallback present")
	void unknownApiRoutesStayRefused() throws Exception {
		assertThat(status("GET", "/v1/unknown-route")).as("unknown v1 route").isEqualTo(403);
		assertThat(status("POST", "/v1/chat/completions/extra")).as("suffixed gateway route").isEqualTo(403);
		assertThat(status("GET", "/v1/admin/anything")).as("admin route without key").isBetween(400, 499);
	}

	@Test
	@DisplayName("error dispatch renders cleanly instead of looping")
	void errorDispatchRendersCleanly() throws Exception {
		assertThat(status("GET", "/v1/unknown-route")).as("refused route is a clean 403, not a 500").isEqualTo(403);
	}

	@Test
	@DisplayName("no CORS headers leak outside the dev profile")
	void noCorsHeadersOutsideDevProfile() throws Exception {
		HttpResponse<Void> preflight = response("OPTIONS", "/v1/chat/completions", "http://localhost:5173");
		assertThat(preflight.headers().firstValue("Access-Control-Allow-Origin"))
				.as("allow-origin must be absent without the dev profile")
				.isEmpty();
	}

	private int status(String method, String path) throws Exception {
		return response(method, path, null).statusCode();
	}

	private String body(String method, String path) throws Exception {
		HttpRequest request = HttpRequest.newBuilder()
				.uri(URI.create("http://localhost:" + port + path))
				.method(method, HttpRequest.BodyPublishers.noBody())
				.build();
		return HttpClient.newHttpClient().send(request, HttpResponse.BodyHandlers.ofString()).body();
	}

	private HttpResponse<Void> response(String method, String path, String origin) throws Exception {
		HttpRequest.Builder builder = HttpRequest.newBuilder()
				.uri(URI.create("http://localhost:" + port + path))
				.method(method, HttpRequest.BodyPublishers.noBody());
		if (origin != null) {
			builder.header("Origin", origin)
					.header("Access-Control-Request-Method", "POST");
		}
		return HttpClient.newHttpClient().send(builder.build(), HttpResponse.BodyHandlers.discarding());
	}
}
