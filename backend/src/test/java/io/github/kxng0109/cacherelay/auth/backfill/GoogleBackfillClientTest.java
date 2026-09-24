package io.github.kxng0109.cacherelay.auth.backfill;

import java.time.Clock;
import java.util.Map;
import java.util.Optional;

import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("GoogleBackfillClient")
class GoogleBackfillClientTest {

	private static final String SA_JSON = "{\"client_email\":\"sa@example.iam.gserviceaccount.com\","
			+ "\"private_key\":\""
			+ TestKeys.RSA_PRIVATE_PEM.replace("\n", "\\n").replace("\r", "")
			+ "\"}";

	private MockWebServer google;

	private GoogleBackfillClient client;

	@BeforeEach
	void setUp() throws Exception {
		google = new MockWebServer();
		google.start();
		String base = google.url("/").toString().replaceAll("/$", "");
		client = new GoogleBackfillClient(SA_JSON, "reader@example.com", Clock.systemUTC(), base,
				base + "/token");
	}

	@AfterEach
	void tearDown() throws Exception {
		google.shutdown();
	}

	private void tokenOk() {
		google.enqueue(new MockResponse().setResponseCode(200).setBody(
				"{\"access_token\":\"google-token\",\"expires_in\":3600,\"token_type\":\"Bearer\"}"));
	}

	private void activeUser() {
		google.enqueue(new MockResponse().setResponseCode(200).setBody(
				"{\"kind\":\"admin#directory#user\",\"suspended\":false,\"archived\":false}"));
	}

	private String groupsPage(String[][] groups, String nextToken) {
		StringBuilder body = new StringBuilder(
				"{\"kind\":\"admin#directory#groups\",\"groups\":[");
		for (int index = 0; index < groups.length; index++) {
			if (index > 0) {
				body.append(',');
			}
			body.append("{\"kind\":\"admin#directory#group\",\"id\":\"").append(groups[index][0])
					.append("\",\"email\":\"").append(groups[index][0]).append("@example.com\",")
					.append("\"name\":");
			if (groups[index][1] == null) {
				body.append("null");
			} else {
				body.append('"').append(groups[index][1]).append('"');
			}
			body.append('}');
		}
		body.append(']');
		if (nextToken != null) {
			body.append(",\"nextPageToken\":\"").append(nextToken).append('"');
		}
		body.append('}');
		return body.toString();
	}

	@Test
	@DisplayName("happy path returns id to name groups with one token call")
	void happyPath() throws Exception {
		tokenOk();
		activeUser();
		google.enqueue(new MockResponse().setResponseCode(200).setBody(
				groupsPage(new String[][]{{"01a", "Engineering"}, {"02b", "Ops"}}, null)));

		Optional<BackfillResult> result = client.fetch("op@example.com", "example.com");

		assertThat(result).isPresent();
		assertThat(result.get().disabled()).isFalse();
		assertThat(result.get().groups()).containsExactly(
				Map.entry("01a", "Engineering"), Map.entry("02b", "Ops"));
		assertThat(google.getRequestCount()).isEqualTo(3);
		assertThat(google.takeRequest().getPath()).contains("/token");
		assertThat(google.takeRequest().getPath()).contains("/admin/directory/v1/users/");
		assertThat(google.takeRequest().getPath()).contains("/admin/directory/v1/groups");
	}

	@Test
	@DisplayName("repeat fetches reuse the cached token")
	void tokenCachedAcrossFetches() {
		tokenOk();
		activeUser();
		google.enqueue(new MockResponse().setResponseCode(200).setBody(
				groupsPage(new String[][]{{"01a", "Engineering"}}, null)));
		activeUser();
		google.enqueue(new MockResponse().setResponseCode(200).setBody(
				groupsPage(new String[][]{{"01a", "Engineering"}}, null)));

		assertThat(client.fetch("op@example.com", "example.com")).isPresent();
		assertThat(client.fetch("op@example.com", "example.com")).isPresent();
	}

	@Test
	@DisplayName("empty token payloads fail closed")
	void emptyTokenDenied() {
		google.enqueue(new MockResponse().setResponseCode(200).setBody("{}"));

		assertThat(client.fetch("op@example.com", "example.com")).isEmpty();
	}

	@Test
	@DisplayName("blank string names fail the whole fetch closed")
	void blankStringNameDenied() {
		tokenOk();
		activeUser();
		google.enqueue(new MockResponse().setResponseCode(200).setBody(
				groupsPage(new String[][]{{"01a", ""}}, null)));

		assertThat(client.fetch("op@example.com", "example.com")).isEmpty();
	}

	@Test
	@DisplayName("blank page tokens end pagination")
	void blankPageTokenEnds() throws Exception {
		tokenOk();
		activeUser();
		google.enqueue(new MockResponse().setResponseCode(200).setBody(
				groupsPage(new String[][]{{"01a", "Engineering"}}, "")));

		Optional<BackfillResult> result = client.fetch("op@example.com", "example.com");

		assertThat(result).isPresent();
		assertThat(result.get().groups()).containsExactly(Map.entry("01a", "Engineering"));
	}

	@Test
	@DisplayName("present emails with unusable keys fail closed")
	void presentEmailUnusableKeyDenied() throws Exception {
		GoogleBackfillClient broken = new GoogleBackfillClient(
				"{\"client_email\":\"sa@x.iam.gserviceaccount.com\",\"private_key\":\"nope\"}",
				"reader@example.com", Clock.systemUTC(),
				google.url("/").toString().replaceAll("/$", ""),
				google.url("/token").toString());

		assertThat(broken.fetch("op@example.com", "example.com")).isEmpty();
	}

	@Test
	@DisplayName("blank key fields fail closed")
	void blankKeyFieldsDenied() throws Exception {
		GoogleBackfillClient broken = new GoogleBackfillClient(
				"{\"client_email\":\"\",\"private_key\":\"\"}",
				"reader@example.com", Clock.systemUTC(),
				google.url("/").toString().replaceAll("/$", ""),
				google.url("/token").toString());

		assertThat(broken.fetch("op@example.com", "example.com")).isEmpty();
		assertThat(google.getRequestCount()).isZero();
	}

	@Test
	@DisplayName("dead servers fail closed without hanging")
	void deadServerDenied() throws Exception {
		MockWebServer dead = new MockWebServer();
		dead.start();
		String deadBase = dead.url("/").toString().replaceAll("/$", "");
		dead.shutdown();
		GoogleBackfillClient broken = new GoogleBackfillClient(SA_JSON, "reader@example.com",
				Clock.systemUTC(), deadBase, deadBase + "/token");

		assertThat(broken.fetch("op@example.com", "example.com")).isEmpty();
	}

	@Test
	@DisplayName("identifier lookups share the email flow")
	void fetchByIdSharesFlow() throws Exception {
		tokenOk();
		activeUser();
		google.enqueue(new MockResponse().setResponseCode(200).setBody(
				groupsPage(new String[][]{{"01a", "Engineering"}}, null)));

		Optional<BackfillResult> result = client.fetchById("123456789", "example.com");

		assertThat(result).isPresent();
		assertThat(result.get().groups()).containsExactly(Map.entry("01a", "Engineering"));
		assertThat(google.takeRequest().getPath()).contains("/token");
	}

	@Test
	@DisplayName("malformed identifiers fail closed without network calls")
	void fetchByIdMalformedDenied() {
		assertThat(client.fetchById("not-an-id", "example.com")).isEmpty();
		assertThat(client.fetchById(null, "example.com")).isEmpty();
		assertThat(client.fetchById("123", null)).isEmpty();
		assertThat(client.fetchById("123", "  ")).isEmpty();
	}

	@Test
	@DisplayName("foreign-domain emails fail closed without network calls")
	void foreignDomainDenied() {
		assertThat(client.fetch("op@other.com", "example.com")).isEmpty();
		assertThat(client.fetch("not-an-email", "example.com")).isEmpty();
		assertThat(client.fetch("", "example.com")).isEmpty();
		assertThat(client.fetch(null, "example.com")).isEmpty();
		assertThat(client.fetch("op@example.com", null)).isEmpty();
		assertThat(google.getRequestCount()).isZero();
	}

	@Test
	@DisplayName("token mint errors fail closed")
	void tokenErrorsDenied() {
		google.enqueue(new MockResponse().setResponseCode(400).setBody("{}"));

		assertThat(client.fetch("op@example.com", "example.com")).isEmpty();
		assertThat(google.getRequestCount()).isEqualTo(1);
	}

	@Test
	@DisplayName("suspended users short-circuit as disabled")
	void suspendedShortCircuits() throws Exception {
		tokenOk();
		google.enqueue(new MockResponse().setResponseCode(200).setBody(
				"{\"kind\":\"admin#directory#user\",\"suspended\":true}"));

		Optional<BackfillResult> result = client.fetch("op@example.com", "example.com");

		assertThat(result).isPresent();
		assertThat(result.get().disabled()).isTrue();
		assertThat(google.getRequestCount()).isEqualTo(2);
	}

	@Test
	@DisplayName("archived users short-circuit as disabled")
	void archivedShortCircuits() throws Exception {
		tokenOk();
		google.enqueue(new MockResponse().setResponseCode(200).setBody(
				"{\"kind\":\"admin#directory#user\",\"suspended\":false,\"archived\":true}"));

		assertThat(client.fetch("op@example.com", "example.com"))
				.hasValueSatisfying(view -> assertThat(view.disabled()).isTrue());
		assertThat(google.getRequestCount()).isEqualTo(2);
	}

	@Test
	@DisplayName("deleted users fail closed as disabled")
	void deletedUserDenied() throws Exception {
		tokenOk();
		google.enqueue(new MockResponse().setResponseCode(404).setBody("{}"));

		assertThat(client.fetch("op@example.com", "example.com"))
				.hasValueSatisfying(view -> assertThat(view.disabled()).isTrue());
		assertThat(google.getRequestCount()).isEqualTo(2);
	}

	@Test
	@DisplayName("user read errors fail closed")
	void userErrorsDenied() throws Exception {
		tokenOk();
		google.enqueue(new MockResponse().setResponseCode(500).setBody("{}"));

		assertThat(client.fetch("op@example.com", "example.com")).isEmpty();
		assertThat(google.getRequestCount()).isEqualTo(2);
	}

	@Test
	@DisplayName("group read errors fail closed")
	void groupErrorsDenied() throws Exception {
		tokenOk();
		activeUser();
		google.enqueue(new MockResponse().setResponseCode(403).setBody("{}"));

		assertThat(client.fetch("op@example.com", "example.com")).isEmpty();
	}

	@Test
	@DisplayName("malformed group payloads fail closed")
	void malformedGroupsDenied() throws Exception {
		tokenOk();
		activeUser();
		google.enqueue(new MockResponse().setResponseCode(200).setBody("not-json"));

		assertThat(client.fetch("op@example.com", "example.com")).isEmpty();
	}

	@Test
	@DisplayName("blank names fail the whole fetch closed")
	void blankNamesDenied() throws Exception {
		tokenOk();
		activeUser();
		google.enqueue(new MockResponse().setResponseCode(200).setBody(
				groupsPage(new String[][]{{"01a", null}}, null)));

		assertThat(client.fetch("op@example.com", "example.com")).isEmpty();
	}

	@Test
	@DisplayName("paged groups merge across tokens")
	void pagedGroupsMerge() throws Exception {
		tokenOk();
		activeUser();
		google.enqueue(new MockResponse().setResponseCode(200).setBody(
				groupsPage(new String[][]{{"01a", "One"}}, "tok-1")));
		google.enqueue(new MockResponse().setResponseCode(200).setBody(
				groupsPage(new String[][]{{"02b", "Two"}}, null)));

		Optional<BackfillResult> result = client.fetch("op@example.com", "example.com");

		assertThat(result).isPresent();
		assertThat(result.get().groups()).containsExactly(
				Map.entry("01a", "One"), Map.entry("02b", "Two"));
		assertThat(google.getRequestCount()).isEqualTo(4);
	}

	@Test
	@DisplayName("truncation past the page cap fails closed")
	void truncationDenied() throws Exception {
		tokenOk();
		activeUser();
		for (int page = 0; page < 3; page++) {
			google.enqueue(new MockResponse().setResponseCode(200).setBody(
					groupsPage(new String[][]{{"0" + page, "G" + page}}, "tok-" + page)));
		}

		assertThat(client.fetch("op@example.com", "example.com")).isEmpty();
	}

	@Test
	@DisplayName("unusable service-account keys fail closed")
	void unusableKeyDenied() throws Exception {
		GoogleBackfillClient broken = new GoogleBackfillClient("{\"nope\":true}",
				"reader@example.com", Clock.systemUTC(),
				google.url("/").toString().replaceAll("/$", ""),
				google.url("/token").toString());

		assertThat(broken.fetch("op@example.com", "example.com")).isEmpty();
		assertThat(google.getRequestCount()).isZero();
	}
}
