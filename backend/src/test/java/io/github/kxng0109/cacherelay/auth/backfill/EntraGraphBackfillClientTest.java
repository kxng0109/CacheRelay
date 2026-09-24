package io.github.kxng0109.cacherelay.auth.backfill;

import java.time.Clock;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("EntraGraphBackfillClient")
class EntraGraphBackfillClientTest {

	private static final String OID = "11111111-2222-3333-4444-555555555555";

	private MockWebServer login;

	private MockWebServer graph;

	private EntraGraphBackfillClient client;

	@BeforeEach
	void setUp() throws Exception {
		login = new MockWebServer();
		login.start();
		graph = new MockWebServer();
		graph.start();
		String loginBase = login.url("/").toString().replaceAll("/$", "");
		String graphBase = graph.url("/").toString().replaceAll("/$", "");
		client = new EntraGraphBackfillClient("app-id", "app-secret", loginBase, graphBase,
				Clock.systemUTC());
	}

	@AfterEach
	void tearDown() throws Exception {
		login.shutdown();
		graph.shutdown();
	}

	private void tokenOk() {
		login.enqueue(new MockResponse().setResponseCode(200).setBody(
				"{\"token_type\":\"Bearer\",\"expires_in\":3599,\"access_token\":\"graph-token\"}"));
	}

	private void enabledUser() {
		graph.enqueue(new MockResponse().setResponseCode(200).setBody(
				"{\"@odata.context\":\"ctx\",\"id\":\"" + OID + "\",\"accountEnabled\":true}"));
	}

	private String groupsPage(String[][] groups, String nextLink) {
		StringBuilder body = new StringBuilder("{\"@odata.context\":\"ctx\",\"value\":[");
		for (int index = 0; index < groups.length; index++) {
			if (index > 0) {
				body.append(',');
			}
			body.append("{\"@odata.type\":\"#microsoft.graph.group\",\"id\":\"")
					.append(groups[index][0]).append("\",\"displayName\":");
			if (groups[index][1] == null) {
				body.append("null");
			} else {
				body.append('"').append(groups[index][1]).append('"');
			}
			body.append('}');
		}
		body.append(']');
		if (nextLink != null) {
			body.append(",\"@odata.nextLink\":\"").append(nextLink).append('"');
		}
		body.append('}');
		return body.toString();
	}

	@Test
	@DisplayName("happy path returns id to display-name groups with one token call")
	void happyPath() throws Exception {
		tokenOk();
		enabledUser();
		graph.enqueue(new MockResponse().setResponseCode(200).setBody(
				groupsPage(new String[][]{{"group-1", "Engineering"}, {"group-2", "Ops"}}, null)));

		Optional<BackfillResult> result = client.fetch(OID, "tenant-1");

		assertThat(result).isPresent();
		assertThat(result.get().disabled()).isFalse();
		assertThat(result.get().groups()).containsExactly(
				Map.entry("group-1", "Engineering"), Map.entry("group-2", "Ops"));
		assertThat(login.getRequestCount()).isEqualTo(1);
		assertThat(graph.getRequestCount()).isEqualTo(2);
		assertThat(graph.takeRequest().getPath()).contains("accountEnabled");
		assertThat(graph.takeRequest().getPath()).contains("transitiveMemberOf");
	}

	@Test
	@DisplayName("malformed identifiers fail closed without network calls")
	void malformedIdentifiersDenied() {
		assertThat(client.fetch("oid/../x", "tenant-1")).isEmpty();
		assertThat(client.fetch(OID, "tenant/x")).isEmpty();
		assertThat(client.fetch("", "tenant-1")).isEmpty();
		assertThat(client.fetch(null, "tenant-1")).isEmpty();
		assertThat(client.fetch(OID, null)).isEmpty();
		assertThat(login.getRequestCount()).isZero();
		assertThat(graph.getRequestCount()).isZero();
	}

	@Test
	@DisplayName("token endpoint errors fail closed")
	void tokenErrorsDenied() {
		login.enqueue(new MockResponse().setResponseCode(400).setBody("{}"));

		assertThat(client.fetch(OID, "tenant-1")).isEmpty();
		assertThat(graph.getRequestCount()).isZero();
	}

	@Test
	@DisplayName("malformed token payloads fail closed")
	void malformedTokenDenied() {
		login.enqueue(new MockResponse().setResponseCode(200).setBody("not-json"));

		assertThat(client.fetch(OID, "tenant-1")).isEmpty();
		assertThat(graph.getRequestCount()).isZero();
	}

	@Test
	@DisplayName("disabled accounts short-circuit before group reads")
	void disabledShortCircuits() throws Exception {
		tokenOk();
		graph.enqueue(new MockResponse().setResponseCode(200).setBody(
				"{\"@odata.context\":\"ctx\",\"id\":\"" + OID + "\",\"accountEnabled\":false}"));

		Optional<BackfillResult> result = client.fetch(OID, "tenant-1");

		assertThat(result).isPresent();
		assertThat(result.get().disabled()).isTrue();
		assertThat(result.get().groups()).isEmpty();
		assertThat(graph.getRequestCount()).isEqualTo(1);
	}

	@Test
	@DisplayName("deleted users fail closed as disabled")
	void deletedUserDenied() throws Exception {
		tokenOk();
		graph.enqueue(new MockResponse().setResponseCode(404).setBody(
				"{\"error\":{\"code\":\"Request_ResourceNotFound\"}}"));

		Optional<BackfillResult> result = client.fetch(OID, "tenant-1");

		assertThat(result).isPresent();
		assertThat(result.get().disabled()).isTrue();
		assertThat(graph.getRequestCount()).isEqualTo(1);
	}

	@Test
	@DisplayName("account check errors fail closed")
	void accountCheckErrorsDenied() throws Exception {
		tokenOk();
		graph.enqueue(new MockResponse().setResponseCode(500).setBody("{}"));

		assertThat(client.fetch(OID, "tenant-1")).isEmpty();
		assertThat(graph.getRequestCount()).isEqualTo(1);
	}

	@Test
	@DisplayName("group read errors fail closed")
	void groupErrorsDenied() throws Exception {
		tokenOk();
		enabledUser();
		graph.enqueue(new MockResponse().setResponseCode(429).setBody("{}"));

		assertThat(client.fetch(OID, "tenant-1")).isEmpty();
	}

	@Test
	@DisplayName("malformed group payloads fail closed")
	void malformedGroupsDenied() throws Exception {
		tokenOk();
		enabledUser();
		graph.enqueue(new MockResponse().setResponseCode(200).setBody("not-json"));

		assertThat(client.fetch(OID, "tenant-1")).isEmpty();
	}

	@Test
	@DisplayName("missing account flags fail closed")
	void missingAccountFlagDenied() throws Exception {
		tokenOk();
		graph.enqueue(new MockResponse().setResponseCode(200).setBody(
				"{\"@odata.context\":\"ctx\",\"id\":\"" + OID + "\"}"));

		assertThat(client.fetch(OID, "tenant-1")).isEmpty();
		assertThat(graph.getRequestCount()).isEqualTo(1);
	}

	@Test
	@DisplayName("repeat fetches reuse the cached token")
	void tokenCachedAcrossFetches() throws Exception {
		tokenOk();
		enabledUser();
		graph.enqueue(new MockResponse().setResponseCode(200).setBody(
				groupsPage(new String[][]{{"group-1", "Engineering"}}, null)));
		enabledUser();
		graph.enqueue(new MockResponse().setResponseCode(200).setBody(
				groupsPage(new String[][]{{"group-1", "Engineering"}}, null)));

		assertThat(client.fetch(OID, "tenant-1")).isPresent();
		assertThat(client.fetch(OID, "tenant-1")).isPresent();
		assertThat(login.getRequestCount()).isEqualTo(1);
	}

	@Test
	@DisplayName("empty token payloads fail closed")
	void emptyTokenDenied() {
		login.enqueue(new MockResponse().setResponseCode(200).setBody("{}"));

		assertThat(client.fetch(OID, "tenant-1")).isEmpty();
		assertThat(graph.getRequestCount()).isZero();
	}

	@Test
	@DisplayName("blank string names fail the whole fetch closed")
	void blankStringNameDenied() throws Exception {
		tokenOk();
		enabledUser();
		graph.enqueue(new MockResponse().setResponseCode(200).setBody(
				groupsPage(new String[][]{{"group-1", ""}}, null)));

		assertThat(client.fetch(OID, "tenant-1")).isEmpty();
	}

	@Test
	@DisplayName("blank follow-ups end pagination")
	void blankNextLinkEnds() throws Exception {
		tokenOk();
		enabledUser();
		graph.enqueue(new MockResponse().setResponseCode(200).setBody(
				groupsPage(new String[][]{{"group-1", "Engineering"}}, "")));

		Optional<BackfillResult> result = client.fetch(OID, "tenant-1");

		assertThat(result).isPresent();
		assertThat(result.get().groups()).containsExactly(Map.entry("group-1", "Engineering"));
	}

	@Test
	@DisplayName("dead servers fail closed without hanging")
	void deadServerDenied() throws Exception {
		MockWebServer deadLogin = new MockWebServer();
		deadLogin.start();
		String deadBase = deadLogin.url("/").toString().replaceAll("/$", "");
		deadLogin.shutdown();
		EntraGraphBackfillClient broken = new EntraGraphBackfillClient("app-id", "app-secret",
				deadBase, deadBase, Clock.systemUTC());

		assertThat(broken.fetch(OID, "tenant-1")).isEmpty();
	}

	@Test
	@DisplayName("blank group ids are skipped while named groups survive")
	void blankIdsSkipped() throws Exception {
		tokenOk();
		enabledUser();
		graph.enqueue(new MockResponse().setResponseCode(200).setBody(
				groupsPage(new String[][]{{"", "Nameless"}, {"group-1", "Engineering"}}, null)));

		Optional<BackfillResult> result = client.fetch(OID, "tenant-1");

		assertThat(result).isPresent();
		assertThat(result.get().groups()).containsExactly(Map.entry("group-1", "Engineering"));
	}

	@Test
	@DisplayName("null display names fail the whole fetch closed")
	void nullDisplayNameDenied() throws Exception {
		tokenOk();
		enabledUser();
		graph.enqueue(new MockResponse().setResponseCode(200).setBody(
				groupsPage(new String[][]{{"group-1", null}}, null)));

		assertThat(client.fetch(OID, "tenant-1")).isEmpty();
	}

	@Test
	@DisplayName("paged groups merge across follow-ups")
	void pagedGroupsMerge() throws Exception {
		tokenOk();
		enabledUser();
		String graphBase = graph.url("/").toString().replaceAll("/$", "");
		graph.enqueue(new MockResponse().setResponseCode(200).setBody(
				groupsPage(new String[][]{{"group-1", "One"}},
						graphBase + "/v1.0/users/" + OID + "/transitiveMemberOf?$skiptoken=abc")));
		graph.enqueue(new MockResponse().setResponseCode(200).setBody(
				groupsPage(new String[][]{{"group-2", "Two"}}, null)));

		Optional<BackfillResult> result = client.fetch(OID, "tenant-1");

		assertThat(result).isPresent();
		assertThat(result.get().groups()).containsExactly(
				Map.entry("group-1", "One"), Map.entry("group-2", "Two"));
		assertThat(graph.getRequestCount()).isEqualTo(3);
	}

	@Test
	@DisplayName("truncation past the page cap fails closed")
	void truncationDenied() throws Exception {
		tokenOk();
		enabledUser();
		String graphBase = graph.url("/").toString().replaceAll("/$", "");
		for (int page = 0; page < 3; page++) {
			graph.enqueue(new MockResponse().setResponseCode(200).setBody(
					groupsPage(new String[][]{{"group-" + page, "G" + page}},
							graphBase + "/v1.0/x?page=" + page)));
		}

		assertThat(client.fetch(OID, "tenant-1")).isEmpty();
	}

	@Test
	@DisplayName("off-host follow-ups fail closed")
	void offHostNextLinkDenied() throws Exception {
		tokenOk();
		enabledUser();
		graph.enqueue(new MockResponse().setResponseCode(200).setBody(
				groupsPage(new String[][]{{"group-1", "One"}}, "https://evil.example/x")));

		assertThat(client.fetch(OID, "tenant-1")).isEmpty();
	}
}
