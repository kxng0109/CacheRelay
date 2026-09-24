package io.github.kxng0109.cacherelay.auth.backfill;

import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.util.Base64;
import java.util.Map;
import java.util.Optional;

import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("OktaBackfillClient")
class OktaBackfillClientTest {

	private static final String USER_ID = "00u1234567890abcdef";

	private MockWebServer okta;

	private String domain;

	private OktaBackfillClient client;

	@BeforeEach
	void setUp() throws Exception {
		okta = new MockWebServer();
		okta.start();
		domain = okta.url("/").toString().replaceAll("/$", "");
		client = new OktaBackfillClient("okta-client-id", TestKeys.RSA_PRIVATE_PEM,
				"test-kid", Clock.systemUTC());
	}

	@AfterEach
	void tearDown() throws Exception {
		okta.shutdown();
	}

	private void tokenOk() {
		okta.enqueue(new MockResponse().setResponseCode(200).setBody(
				"{\"token_type\":\"Bearer\",\"expires_in\":3600,\"access_token\":\"okta-token\"}"));
	}

	private void activeUser() {
		okta.enqueue(new MockResponse().setResponseCode(200).setBody(
				"{\"id\":\"" + USER_ID + "\",\"status\":\"ACTIVE\",\"profile\":{\"login\":\"op\"}}"));
	}

	private String groupsPage(String[][] groups, String nextPath) {
		StringBuilder body = new StringBuilder("[");
		for (int index = 0; index < groups.length; index++) {
			if (index > 0) {
				body.append(',');
			}
			body.append("{\"id\":\"").append(groups[index][0]).append("\",\"type\":\"OKTA_GROUP\",")
					.append("\"profile\":{\"name\":");
			if (groups[index][1] == null) {
				body.append("null");
			} else {
				body.append('"').append(groups[index][1]).append('"');
			}
			body.append("}}");
		}
		body.append(']');
		return body.toString();
	}

	private void groupsOk(String[][] groups, String nextPath) {
		MockResponse response =
				new MockResponse().setResponseCode(200).setBody(groupsPage(groups, nextPath));
		if (nextPath != null) {
			String base = okta.url("/").toString().replaceAll("/$", "");
			response.addHeader("Link", "<" + base + nextPath + ">; rel=\"next\"");
		}
		okta.enqueue(response);
	}

	@Test
	@DisplayName("happy path returns id to name groups with one token call")
	void happyPath() throws Exception {
		tokenOk();
		activeUser();
		groupsOk(new String[][]{{"00g1", "Engineering"}, {"00g2", "Ops"}}, null);

		Optional<BackfillResult> result = client.fetch(domain, USER_ID);

		assertThat(result).isPresent();
		assertThat(result.get().disabled()).isFalse();
		assertThat(result.get().groups()).containsExactly(
				Map.entry("00g1", "Engineering"), Map.entry("00g2", "Ops"));
		assertThat(okta.getRequestCount()).isEqualTo(3);
		assertThat(okta.takeRequest().getPath()).contains("/oauth2/v1/token");
		assertThat(okta.takeRequest().getPath()).contains("/api/v1/users/" + USER_ID);
		assertThat(okta.takeRequest().getPath()).contains("/api/v1/users/" + USER_ID + "/groups");
	}

	@Test
	@DisplayName("assertion carries kid, scope, and audience")
	void assertionCarriesKidAndScope() throws Exception {
		tokenOk();
		activeUser();
		groupsOk(new String[][]{{"00g1", "Engineering"}}, null);

		assertThat(client.fetch(domain, USER_ID)).isPresent();

		String tokenBody = okta.takeRequest().getBody().readUtf8();
		String assertion = null;
		for (String part : tokenBody.split("&")) {
			if (part.startsWith("client_assertion=")) {
				assertion = URLDecoder.decode(part.substring("client_assertion=".length()),
						StandardCharsets.UTF_8);
			}
		}
		assertThat(assertion).isNotNull();
		String[] segments = assertion.split("\\.");
		assertThat(segments).hasSize(3);
		String header = new String(Base64.getUrlDecoder().decode(segments[0]),
				StandardCharsets.UTF_8);
		String payload = new String(Base64.getUrlDecoder().decode(segments[1]),
				StandardCharsets.UTF_8);
		assertThat(header).contains("\"alg\":\"RS256\"");
		assertThat(header).contains("\"kid\":\"test-kid\"");
		assertThat(payload).contains("okta-client-id");
		assertThat(payload).contains("/oauth2/v1/token");
		assertThat(tokenBody).contains("grant_type=client_credentials");
		assertThat(tokenBody).contains("scope=okta.users.read");
	}

	@Test
	@DisplayName("repeat fetches reuse the cached token")
	void tokenCachedAcrossFetches() {
		tokenOk();
		activeUser();
		groupsOk(new String[][]{{"00g1", "Engineering"}}, null);
		activeUser();
		groupsOk(new String[][]{{"00g1", "Engineering"}}, null);

		assertThat(client.fetch(domain, USER_ID)).isPresent();
		assertThat(client.fetch(domain, USER_ID)).isPresent();
		assertThat(okta.getRequestCount()).isEqualTo(5);
	}

	@Test
	@DisplayName("empty token payloads fail closed")
	void emptyTokenDenied() {
		okta.enqueue(new MockResponse().setResponseCode(200).setBody("{}"));

		assertThat(client.fetch(domain, USER_ID)).isEmpty();
	}

	@Test
	@DisplayName("blank string names fail the whole fetch closed")
	void blankStringNameDenied() {
		tokenOk();
		activeUser();
		groupsOk(new String[][]{{"00g1", ""}}, null);

		assertThat(client.fetch(domain, USER_ID)).isEmpty();
	}

	@Test
	@DisplayName("dead servers fail closed without hanging")
	void deadServerDenied() throws Exception {
		MockWebServer dead = new MockWebServer();
		dead.start();
		String deadBase = dead.url("/").toString().replaceAll("/$", "");
		dead.shutdown();
		OktaBackfillClient broken = new OktaBackfillClient("okta-client-id",
				TestKeys.RSA_PRIVATE_PEM, "test-kid", Clock.systemUTC());

		assertThat(broken.fetch(deadBase, USER_ID)).isEmpty();
	}

	@Test
	@DisplayName("malformed user ids fail closed without network calls")
	void malformedUserIdDenied() {
		assertThat(client.fetch(domain, "user/id")).isEmpty();
		assertThat(client.fetch(domain, "")).isEmpty();
		assertThat(client.fetch(domain, null)).isEmpty();
		assertThat(client.fetch(null, USER_ID)).isEmpty();
		assertThat(okta.getRequestCount()).isZero();
	}

	@Test
	@DisplayName("token mint errors fail closed")
	void tokenErrorsDenied() {
		okta.enqueue(new MockResponse().setResponseCode(401).setBody("{}"));

		assertThat(client.fetch(domain, USER_ID)).isEmpty();
		assertThat(okta.getRequestCount()).isEqualTo(1);
	}

	@Test
	@DisplayName("unusable private keys fail closed")
	void unusableKeyDenied() throws Exception {
		MockWebServer server = new MockWebServer();
		server.start();
		try {
			String domain = server.url("/").toString().replaceAll("/$", "");
			OktaBackfillClient broken =
					new OktaBackfillClient("okta-client-id", "not-a-pem", "test-kid",
							Clock.systemUTC());
			assertThat(broken.fetch(domain, USER_ID)).isEmpty();
			assertThat(server.getRequestCount()).isZero();
		} finally {
			server.shutdown();
		}
	}

	@Test
	@DisplayName("suspended users short-circuit as disabled")
	void suspendedShortCircuits() throws Exception {
		tokenOk();
		okta.enqueue(new MockResponse().setResponseCode(200).setBody(
				"{\"id\":\"" + USER_ID + "\",\"status\":\"SUSPENDED\"}"));

		Optional<BackfillResult> result = client.fetch(domain, USER_ID);

		assertThat(result).isPresent();
		assertThat(result.get().disabled()).isTrue();
		assertThat(okta.getRequestCount()).isEqualTo(2);
	}

	@Test
	@DisplayName("deleted users fail closed as disabled")
	void deletedUserDenied() throws Exception {
		tokenOk();
		okta.enqueue(new MockResponse().setResponseCode(404).setBody(
				"{\"errorCode\":\"E0000007\",\"errorSummary\":\"Not found\"}"));

		Optional<BackfillResult> result = client.fetch(domain, USER_ID);

		assertThat(result).isPresent();
		assertThat(result.get().disabled()).isTrue();
		assertThat(okta.getRequestCount()).isEqualTo(2);
	}

	@Test
	@DisplayName("user read errors fail closed")
	void userErrorsDenied() throws Exception {
		tokenOk();
		okta.enqueue(new MockResponse().setResponseCode(500).setBody("{}"));

		assertThat(client.fetch(domain, USER_ID)).isEmpty();
		assertThat(okta.getRequestCount()).isEqualTo(2);
	}

	@Test
	@DisplayName("missing statuses fail closed")
	void missingStatusDenied() throws Exception {
		tokenOk();
		okta.enqueue(new MockResponse().setResponseCode(200).setBody(
				"{\"id\":\"" + USER_ID + "\"}"));

		assertThat(client.fetch(domain, USER_ID)).isEmpty();
		assertThat(okta.getRequestCount()).isEqualTo(2);
	}

	@Test
	@DisplayName("group read errors fail closed")
	void groupErrorsDenied() throws Exception {
		tokenOk();
		activeUser();
		okta.enqueue(new MockResponse().setResponseCode(403).setBody("{}"));

		assertThat(client.fetch(domain, USER_ID)).isEmpty();
	}

	@Test
	@DisplayName("malformed group payloads fail closed")
	void malformedGroupsDenied() throws Exception {
		tokenOk();
		activeUser();
		okta.enqueue(new MockResponse().setResponseCode(200).setBody("not-json"));

		assertThat(client.fetch(domain, USER_ID)).isEmpty();
	}

	@Test
	@DisplayName("blank group names fail the whole fetch closed")
	void blankNamesDenied() throws Exception {
		tokenOk();
		activeUser();
		groupsOk(new String[][]{{"00g1", null}}, null);

		assertThat(client.fetch(domain, USER_ID)).isEmpty();
	}

	@Test
	@DisplayName("paged groups merge across Link follow-ups")
	void pagedGroupsMerge() throws Exception {
		tokenOk();
		activeUser();
		groupsOk(new String[][]{{"00g1", "One"}}, "/api/v1/users/" + USER_ID + "/groups?after=abc");
		groupsOk(new String[][]{{"00g2", "Two"}}, null);

		Optional<BackfillResult> result = client.fetch(domain, USER_ID);

		assertThat(result).isPresent();
		assertThat(result.get().groups()).containsExactly(
				Map.entry("00g1", "One"), Map.entry("00g2", "Two"));
		assertThat(okta.getRequestCount()).isEqualTo(4);
	}

	@Test
	@DisplayName("truncation past the page cap fails closed")
	void truncationDenied() throws Exception {
		tokenOk();
		activeUser();
		for (int page = 0; page < 3; page++) {
			groupsOk(new String[][]{{"00g" + page, "G" + page}},
					"/api/v1/users/" + USER_ID + "/groups?after=" + page);
		}

		assertThat(client.fetch(domain, USER_ID)).isEmpty();
	}

	@Test
	@DisplayName("off-host Link follow-ups fail closed")
	void offHostLinkDenied() throws Exception {
		tokenOk();
		activeUser();
		MockResponse first = new MockResponse().setResponseCode(200)
				.setBody(groupsPage(new String[][]{{"00g1", "One"}}, null));
		first.addHeader("Link", "<https://evil.example/x>; rel=\"next\"");
		okta.enqueue(first);

		assertThat(client.fetch(domain, USER_ID)).isEmpty();
	}
}
