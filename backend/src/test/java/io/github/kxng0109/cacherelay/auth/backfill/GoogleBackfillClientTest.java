package io.github.kxng0109.cacherelay.auth.backfill;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
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

	@Test
	@DisplayName("identifier lookups with dead tokens fail closed")
	void fetchByIdTokenFailureDenied() throws Exception {
		GoogleBackfillClient broken = new GoogleBackfillClient(
				"{\"client_email\":\"\",\"private_key\":\"\"}",
				"reader@example.com", Clock.systemUTC(),
				google.url("/").toString().replaceAll("/$", ""),
				google.url("/token").toString());

		assertThat(broken.fetchById("123456789", "example.com")).as("null token denies").isEmpty();
		assertThat(google.getRequestCount()).as("no network on token failure").isZero();
	}

	@Test
	@DisplayName("identifier lookups surface user read errors as empty")
	void fetchByIdUserErrorDenied() {
		tokenOk();
		google.enqueue(new MockResponse().setResponseCode(500).setBody("{}"));

		assertThat(client.fetchById("123456789", "example.com")).as("empty disabled denies").isEmpty();
		assertThat(google.getRequestCount()).as("token plus user calls").isEqualTo(2);
	}

	@Test
	@DisplayName("identifier lookups short-circuit suspended users as disabled")
	void fetchByIdDisabledShortCircuits() {
		tokenOk();
		google.enqueue(new MockResponse().setResponseCode(200).setBody(
				"{\"kind\":\"admin#directory#user\",\"suspended\":true}"));

		assertThat(client.fetchById("123456789", "example.com"))
				.as("suspended id denies as disabled")
				.hasValueSatisfying(view -> assertThat(view.disabled()).as("disabled flag").isTrue());
		assertThat(google.getRequestCount()).as("token plus user calls").isEqualTo(2);
	}

	@Test
	@DisplayName("expired cached tokens re-mint on the next fetch")
	void expiredTokenRemints() {
		Instant[] now = {Instant.now()};
		Clock ticking = new Clock() {
			@Override
			public ZoneId getZone() {
				return ZoneOffset.UTC;
			}

			@Override
			public Clock withZone(ZoneId zone) {
				return this;
			}

			@Override
			public Instant instant() {
				return now[0];
			}
		};
		String base = google.url("/").toString().replaceAll("/$", "");
		GoogleBackfillClient tickingClient = new GoogleBackfillClient(SA_JSON, "reader@example.com",
				ticking, base, base + "/token");

		tokenOk();
		activeUser();
		google.enqueue(new MockResponse().setResponseCode(200).setBody(
				groupsPage(new String[][]{{"01a", "Engineering"}}, null)));
		assertThat(tickingClient.fetch("op@example.com", "example.com")).as("first fetch").isPresent();

		now[0] = now[0].plusSeconds(4000L);
		tokenOk();
		activeUser();
		google.enqueue(new MockResponse().setResponseCode(200).setBody(
				groupsPage(new String[][]{{"01a", "Engineering"}}, null)));
		assertThat(tickingClient.fetch("op@example.com", "example.com")).as("second fetch").isPresent();

		assertThat(google.getRequestCount()).as("two token mints plus two reads each").isEqualTo(6);
	}

	@Test
	@DisplayName("assertion signing failures fail closed without network calls")
	void signingFailureDenied() throws Exception {
		Clock nullClock = new Clock() {
			@Override
			public ZoneId getZone() {
				return ZoneOffset.UTC;
			}

			@Override
			public Clock withZone(ZoneId zone) {
				return this;
			}

			@Override
			public Instant instant() {
				return null;
			}
		};
		GoogleBackfillClient broken = new GoogleBackfillClient(SA_JSON, "reader@example.com",
				nullClock, google.url("/").toString().replaceAll("/$", ""),
				google.url("/token").toString());

		assertThat(broken.fetch("op@example.com", "example.com")).as("null assertion denies").isEmpty();
		assertThat(google.getRequestCount()).as("no network on signing failure").isZero();
	}

	@Test
	@DisplayName("empty-string token payloads fail closed")
	void emptyStringTokenDenied() {
		google.enqueue(new MockResponse().setResponseCode(200).setBody(
				"{\"access_token\":\"\",\"expires_in\":3600,\"token_type\":\"Bearer\"}"));

		assertThat(client.fetch("op@example.com", "example.com")).as("blank token denies").isEmpty();
		assertThat(google.getRequestCount()).as("single token call").isEqualTo(1);
	}

	@Test
	@DisplayName("missing private keys fail closed")
	void missingPrivateKeyDenied() throws Exception {
		GoogleBackfillClient broken = new GoogleBackfillClient(
				"{\"client_email\":\"sa@example.iam.gserviceaccount.com\"}",
				"reader@example.com", Clock.systemUTC(),
				google.url("/").toString().replaceAll("/$", ""),
				google.url("/token").toString());

		assertThat(broken.fetch("op@example.com", "example.com")).as("null key denies").isEmpty();
		assertThat(google.getRequestCount()).as("no network on key failure").isZero();
	}

	@Test
	@DisplayName("blank private keys fail closed despite valid emails")
	void blankPrivateKeyDenied() throws Exception {
		GoogleBackfillClient broken = new GoogleBackfillClient(
				"{\"client_email\":\"sa@example.iam.gserviceaccount.com\",\"private_key\":\"   \"}",
				"reader@example.com", Clock.systemUTC(),
				google.url("/").toString().replaceAll("/$", ""),
				google.url("/token").toString());

		assertThat(broken.fetch("op@example.com", "example.com")).as("blank key denies").isEmpty();
		assertThat(google.getRequestCount()).as("no network on key failure").isZero();
	}

	@Test
	@DisplayName("key token URIs override the configured default")
	void keyTokenUriOverridesDefault() {
		String base = google.url("/").toString().replaceAll("/$", "");
		GoogleBackfillClient keyed = new GoogleBackfillClient(saJsonWithTokenUri(base + "/token"),
				"reader@example.com", Clock.systemUTC(), base, "http://127.0.0.1:9/unused");
		tokenOk();
		activeUser();
		google.enqueue(new MockResponse().setResponseCode(200).setBody(
				groupsPage(new String[][]{{"01a", "Engineering"}}, null)));

		Optional<BackfillResult> result = keyed.fetch("op@example.com", "example.com");

		assertThat(result).as("key URI mint succeeds").isPresent();
		assertThat(result.get().groups()).as("groups resolve")
				.containsExactly(Map.entry("01a", "Engineering"));
		assertThat(google.getRequestCount()).as("token plus user plus groups").isEqualTo(3);
	}

	@Test
	@DisplayName("blank key token URIs fall back to the configured default")
	void blankKeyTokenUriFallsBackToDefault() {
		String base = google.url("/").toString().replaceAll("/$", "");
		GoogleBackfillClient keyed = new GoogleBackfillClient(saJsonWithTokenUri("  "),
				"reader@example.com", Clock.systemUTC(), base, base + "/token");
		tokenOk();
		activeUser();
		google.enqueue(new MockResponse().setResponseCode(200).setBody(
				groupsPage(new String[][]{{"01a", "Engineering"}}, null)));

		assertThat(keyed.fetch("op@example.com", "example.com")).as("fallback mint succeeds")
				.isPresent();
		assertThat(google.getRequestCount()).as("token plus user plus groups").isEqualTo(3);
	}

	@Test
	@DisplayName("missing key token URIs with null defaults fail closed")
	void missingKeyTokenUriNullDefaultDenied() throws Exception {
		Clock nullClock = new Clock() {
			@Override
			public ZoneId getZone() {
				return ZoneOffset.UTC;
			}

			@Override
			public Clock withZone(ZoneId zone) {
				return this;
			}

			@Override
			public Instant instant() {
				return null;
			}
		};
		GoogleBackfillClient keyed = new GoogleBackfillClient(SA_JSON, "reader@example.com",
				nullClock, google.url("/").toString().replaceAll("/$", ""), null);

		assertThat(keyed.fetch("op@example.com", "example.com")).as("default URI path denies")
				.isEmpty();
		assertThat(google.getRequestCount()).as("no network on signing failure").isZero();
	}

	@Test
	@DisplayName("user payloads without flags fail closed")
	void userWithoutFlagsDenied() {
		tokenOk();
		google.enqueue(new MockResponse().setResponseCode(200).setBody(
				"{\"kind\":\"admin#directory#user\"}"));

		assertThat(client.fetch("op@example.com", "example.com")).as("flagless user denies").isEmpty();
		assertThat(google.getRequestCount()).as("token plus user calls").isEqualTo(2);
	}

	@Test
	@DisplayName("archived-only payloads proceed to group reads")
	void archivedOnlyUserProceeds() {
		tokenOk();
		google.enqueue(new MockResponse().setResponseCode(200).setBody(
				"{\"kind\":\"admin#directory#user\",\"archived\":false}"));
		google.enqueue(new MockResponse().setResponseCode(200).setBody(
				groupsPage(new String[][]{{"01a", "Engineering"}}, null)));

		Optional<BackfillResult> result = client.fetch("op@example.com", "example.com");

		assertThat(result).as("active user resolves").isPresent();
		assertThat(result.get().disabled()).as("disabled flag").isFalse();
		assertThat(google.getRequestCount()).as("token plus user plus groups").isEqualTo(3);
	}

	@Test
	@DisplayName("non-array group payloads fail closed")
	void nonArrayGroupsDenied() {
		tokenOk();
		activeUser();
		google.enqueue(new MockResponse().setResponseCode(200).setBody(
				"{\"kind\":\"admin#directory#groups\",\"groups\":{}}"));

		assertThat(client.fetch("op@example.com", "example.com")).as("object groups deny").isEmpty();
	}

	@Test
	@DisplayName("blank group ids are skipped while named groups resolve")
	void blankGroupIdsSkipped() {
		tokenOk();
		activeUser();
		google.enqueue(new MockResponse().setResponseCode(200).setBody(
				groupsPage(new String[][]{{"", "Ghost"}, {"01a", "Real"}}, null)));

		Optional<BackfillResult> result = client.fetch("op@example.com", "example.com");

		assertThat(result).as("fetch resolves").isPresent();
		assertThat(result.get().groups()).as("blank id skipped")
				.containsExactly(Map.entry("01a", "Real"));
	}

	private String saJsonWithTokenUri(String tokenUri) {
		return "{\"client_email\":\"sa@example.iam.gserviceaccount.com\","
				+ "\"private_key\":\""
				+ TestKeys.RSA_PRIVATE_PEM.replace("\n", "\\n").replace("\r", "")
				+ "\",\"token_uri\":\"" + tokenUri + "\"}";
	}
}
