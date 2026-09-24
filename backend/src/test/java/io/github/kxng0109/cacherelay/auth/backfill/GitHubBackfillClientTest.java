package io.github.kxng0109.cacherelay.auth.backfill;

import java.util.Map;
import java.util.Optional;

import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.RecordedRequest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("GitHubBackfillClient")
class GitHubBackfillClientTest {

	private MockWebServer github;

	private GitHubBackfillClient client;

	@BeforeEach
	void setUp() throws Exception {
		github = new MockWebServer();
		github.start();
		client = new GitHubBackfillClient(github.url("/").toString().replaceAll("/$", ""));
	}

	@AfterEach
	void tearDown() throws Exception {
		github.shutdown();
	}

	private void selfUser(String login) {
		github.enqueue(new MockResponse().setResponseCode(200).setBody(
				"{\"login\":\"" + login + "\",\"id\":123456,\"type\":\"User\"}"));
	}

	private void activeOrgMembership() {
		github.enqueue(new MockResponse().setResponseCode(200).setBody(
				"{\"url\":\"https://api.github.com/orgs/acme/memberships/op\","
						+ "\"state\":\"active\",\"role\":\"member\","
						+ "\"organization\":{\"login\":\"acme\",\"id\":1}}"));
	}

	private void teams(String body) {
		github.enqueue(new MockResponse().setResponseCode(200).setBody(body));
	}

	private String team(String slug, String name, String org) {
		return "{\"id\":1,\"slug\":\"" + slug + "\",\"name\":\"" + name + "\","
				+ "\"permission\":\"push\",\"organization\":{\"login\":\"" + org + "\",\"id\":1}}";
	}

	@Test
	@DisplayName("happy path returns slugs of the org teams")
	void happyPath() throws Exception {
		selfUser("op");
		activeOrgMembership();
		teams("[" + team("eng", "Engineering", "acme") + "," + team("ops", "Ops", "acme") + ","
				+ team("other", "Other", "unrelated") + "]");

		Optional<BackfillResult> result = client.fetch("op", "acme", "user-token");

		assertThat(result).isPresent();
		assertThat(result.get().disabled()).isFalse();
		assertThat(result.get().groups()).containsExactly(
				Map.entry("eng", "Engineering"), Map.entry("ops", "Ops"));
		assertThat(github.getRequestCount()).isEqualTo(3);
		assertThat(github.takeRequest().getPath()).isEqualTo("/user");
		assertThat(github.takeRequest().getPath()).contains("/user/memberships/orgs/acme");
		assertThat(github.takeRequest().getPath()).isEqualTo("/user/teams");
	}

	@Test
	@DisplayName("version and auth headers ride every call")
	void headersPresent() throws Exception {
		selfUser("op");
		activeOrgMembership();
		teams("[]");

		assertThat(client.fetch("op", "acme", "user-token")).isPresent();

		RecordedRequest first = github.takeRequest();
		assertThat(first.getHeader("Accept")).isEqualTo("application/vnd.github+json");
		assertThat(first.getHeader("X-GitHub-Api-Version")).isEqualTo("2022-11-28");
		assertThat(first.getHeader("Authorization")).isEqualTo("Bearer user-token");
	}

	@Test
	@DisplayName("malformed inputs fail closed without network calls")
	void malformedInputsDenied() {
		assertThat(client.fetch("op/x", "acme", "user-token")).isEmpty();
		assertThat(client.fetch("op", "acme org", "user-token")).isEmpty();
		assertThat(client.fetch("op", "acme", "")).isEmpty();
		assertThat(client.fetch("", "acme", "user-token")).isEmpty();
		assertThat(client.fetch(null, "acme", "user-token")).isEmpty();
		assertThat(client.fetch("op", null, "user-token")).isEmpty();
		assertThat(client.fetch("op", "acme", null)).isEmpty();
		assertThat(github.getRequestCount()).isZero();
	}

	@Test
	@DisplayName("login mismatches fail closed")
	void loginMismatchDenied() throws Exception {
		selfUser("someone-else");

		assertThat(client.fetch("op", "acme", "user-token")).isEmpty();
		assertThat(github.getRequestCount()).isEqualTo(1);
	}

	@Test
	@DisplayName("missing logins fail closed")
	void missingLoginDenied() throws Exception {
		github.enqueue(new MockResponse().setResponseCode(200).setBody("{}"));

		assertThat(client.fetch("op", "acme", "user-token")).isEmpty();
		assertThat(github.getRequestCount()).isEqualTo(1);
	}

	@Test
	@DisplayName("self read errors fail closed")
	void selfErrorsDenied() throws Exception {
		github.enqueue(new MockResponse().setResponseCode(401).setBody("{}"));

		assertThat(client.fetch("op", "acme", "user-token")).isEmpty();
		assertThat(github.getRequestCount()).isEqualTo(1);
	}

	@Test
	@DisplayName("pending memberships fail closed")
	void pendingMembershipDenied() throws Exception {
		selfUser("op");
		github.enqueue(new MockResponse().setResponseCode(200).setBody(
				"{\"state\":\"pending\",\"role\":\"member\","
						+ "\"organization\":{\"login\":\"acme\",\"id\":1}}"));

		assertThat(client.fetch("op", "acme", "user-token")).isEmpty();
		assertThat(github.getRequestCount()).isEqualTo(2);
	}

	@Test
	@DisplayName("missing memberships fail closed")
	void missingMembershipDenied() throws Exception {
		selfUser("op");
		github.enqueue(new MockResponse().setResponseCode(404).setBody("{}"));

		assertThat(client.fetch("op", "acme", "user-token")).isEmpty();
		assertThat(github.getRequestCount()).isEqualTo(2);
	}

	@Test
	@DisplayName("membership read errors fail closed")
	void membershipErrorsDenied() throws Exception {
		selfUser("op");
		github.enqueue(new MockResponse().setResponseCode(500).setBody("{}"));

		assertThat(client.fetch("op", "acme", "user-token")).isEmpty();
		assertThat(github.getRequestCount()).isEqualTo(2);
	}

	@Test
	@DisplayName("team read errors fail closed")
	void teamErrorsDenied() throws Exception {
		selfUser("op");
		activeOrgMembership();
		github.enqueue(new MockResponse().setResponseCode(403).setBody("{}"));

		assertThat(client.fetch("op", "acme", "user-token")).isEmpty();
		assertThat(github.getRequestCount()).isEqualTo(3);
	}

	@Test
	@DisplayName("malformed team payloads fail closed")
	void malformedTeamsDenied() throws Exception {
		selfUser("op");
		activeOrgMembership();
		teams("not-json");

		assertThat(client.fetch("op", "acme", "user-token")).isEmpty();
	}

	@Test
	@DisplayName("entries without organizations are skipped")
	void missingOrgSkipped() throws Exception {
		selfUser("op");
		activeOrgMembership();
		teams("[{\"id\":9,\"slug\":\"lonely\",\"name\":\"Lonely\"},"
				+ team("eng", "Engineering", "acme") + "]");

		Optional<BackfillResult> result = client.fetch("op", "acme", "user-token");

		assertThat(result).isPresent();
		assertThat(result.get().groups()).containsExactly(Map.entry("eng", "Engineering"));
	}

	@Test
	@DisplayName("blank slugs are skipped while named teams survive")
	void blankSlugsSkipped() throws Exception {
		selfUser("op");
		activeOrgMembership();
		teams("[{\"id\":9,\"slug\":\"\",\"name\":\"Nameless\","
				+ "\"organization\":{\"login\":\"acme\",\"id\":1}},"
				+ team("eng", "Engineering", "acme") + "]");

		Optional<BackfillResult> result = client.fetch("op", "acme", "user-token");

		assertThat(result).isPresent();
		assertThat(result.get().groups()).containsExactly(Map.entry("eng", "Engineering"));
	}

	@Test
	@DisplayName("non-array team payloads fail closed")
	void nonArrayTeamsDenied() throws Exception {
		selfUser("op");
		activeOrgMembership();
		teams("{}");

		assertThat(client.fetch("op", "acme", "user-token")).isEmpty();
	}

	@Test
	@DisplayName("blank string names fail the whole fetch closed")
	void blankStringNameDenied() throws Exception {
		selfUser("op");
		activeOrgMembership();
		teams("[{\"id\":9,\"slug\":\"s\",\"name\":\"\","
				+ "\"organization\":{\"login\":\"acme\",\"id\":1}}]");

		assertThat(client.fetch("op", "acme", "user-token")).isEmpty();
	}

	@Test
	@DisplayName("blank names fail the whole fetch closed")
	void blankNamesDenied() throws Exception {
		selfUser("op");
		activeOrgMembership();
		teams("[{\"id\":9,\"slug\":\"nameless\",\"name\":null,"
				+ "\"organization\":{\"login\":\"acme\",\"id\":1}}]");

		assertThat(client.fetch("op", "acme", "user-token")).isEmpty();
	}

	@Test
	@DisplayName("truncation past the page cap fails closed")
	void truncationDenied() throws Exception {
		selfUser("op");
		activeOrgMembership();
		String base = github.url("/").toString().replaceAll("/$", "");
		for (int page = 0; page < 3; page++) {
			MockResponse paged = new MockResponse().setResponseCode(200)
					.setBody("[" + team("t" + page, "T" + page, "acme") + "]");
			paged.addHeader("Link", "<" + base + "/user/teams?page=" + page + ">; rel=\"next\"");
			github.enqueue(paged);
		}

		assertThat(client.fetch("op", "acme", "user-token")).isEmpty();
	}

	@Test
	@DisplayName("off-host follow-ups fail closed")
	void offHostNextDenied() throws Exception {
		selfUser("op");
		activeOrgMembership();
		MockResponse first = new MockResponse().setResponseCode(200)
				.setBody("[" + team("eng", "Engineering", "acme") + "]");
		first.addHeader("Link", "<https://evil.example/x>; rel=\"next\"");
		github.enqueue(first);

		assertThat(client.fetch("op", "acme", "user-token")).isEmpty();
	}

	@Test
	@DisplayName("paged teams merge across Link follow-ups")
	void pagedTeamsMerge() throws Exception {
		selfUser("op");
		activeOrgMembership();
		String base = github.url("/").toString().replaceAll("/$", "");
		MockResponse first = new MockResponse().setResponseCode(200)
				.setBody("[" + team("eng", "Engineering", "acme") + "]");
		first.addHeader("Link", "<" + base + "/user/teams?page=2>; rel=\"next\"");
		github.enqueue(first);
		teams("[" + team("ops", "Ops", "acme") + "]");

		Optional<BackfillResult> result = client.fetch("op", "acme", "user-token");

		assertThat(result).isPresent();
		assertThat(result.get().groups()).containsExactly(
				Map.entry("eng", "Engineering"), Map.entry("ops", "Ops"));
		assertThat(github.getRequestCount()).isEqualTo(4);
	}
}
