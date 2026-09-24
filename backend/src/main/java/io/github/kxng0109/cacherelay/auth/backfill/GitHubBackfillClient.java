package io.github.kxng0109.cacherelay.auth.backfill;

import java.io.IOException;
import java.net.http.HttpClient;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Pattern;

import tools.jackson.databind.JsonNode;

/**
 * GitHub REST backfill with the user's own OAuth token: self identity, active
 * org membership, then the org's team placements.
 *
 * <p>Pending memberships, missing memberships, and every transport, protocol,
 * or payload surprise fail closed. Only teams whose organization matches the
 * expected org count; other orgs never leak across. Team slugs key the
 * groups so provisioning patterns match stable identifiers.</p>
 */
public class GitHubBackfillClient {

	static final String DEFAULT_API_BASE = "https://api.github.com";

	static final String API_VERSION = "2022-11-28";

	private static final Map<String, String> API_HEADERS = Map.of(
			"Accept", "application/vnd.github+json",
			"X-GitHub-Api-Version", API_VERSION);

	private static final Pattern LOGIN_PATTERN = Pattern.compile("^[A-Za-z0-9-]+$");

	private static final int MAX_PAGES = 3;

	private final String apiBase;

	private final HttpClient http;

	/**
	 * Creates the client against the production API.
	 */
	public GitHubBackfillClient() {
		this(DEFAULT_API_BASE);
	}

	/**
	 * Creates the client against an explicit base (tests).
	 *
	 * @param apiBase API host without trailing slash, never {@code null}
	 */
	public GitHubBackfillClient(String apiBase) {
		this.apiBase = apiBase;
		this.http = BackfillHttp.newClient();
	}

	/**
	 * Fetches one user's org teams.
	 *
	 * @param expectedLogin login the token must belong to, never {@code null}
	 * @param org           expected org login, never {@code null}
	 * @param userToken     user's OAuth token, never {@code null} (never logged)
	 * @return slug to name teams, or empty to deny on any failure
	 */
	public Optional<BackfillResult> fetch(String expectedLogin, String org, String userToken) {
		if (expectedLogin == null || org == null || userToken == null
				|| !LOGIN_PATTERN.matcher(expectedLogin).matches()
				|| !LOGIN_PATTERN.matcher(org).matches()
				|| userToken.isBlank()) {
			return Optional.empty();
		}
		Map<String, String> headers = API_HEADERS;
		try {
			BackfillHttp.HttpResult self = BackfillHttp.get(http, apiBase + "/user", userToken,
					headers);
			if (self.status() != 200) {
				return Optional.empty();
			}
			String login = BackfillHttp.parseJson(self.body()).path("login").asString(null);
			if (login == null || !login.equalsIgnoreCase(expectedLogin)) {
				return Optional.empty();
			}
			BackfillHttp.HttpResult membership = BackfillHttp.get(http,
					apiBase + "/user/memberships/orgs/" + org, userToken, headers);
			if (membership.status() != 200) {
				return Optional.empty();
			}
			String state = BackfillHttp.parseJson(membership.body()).path("state").asString(null);
			if (!"active".equals(state)) {
				return Optional.empty();
			}
			return readTeams(userToken, headers, org);
		} catch (IOException | RuntimeException failed) {
			return Optional.empty();
		} catch (InterruptedException interrupted) {
			Thread.currentThread().interrupt();
			return Optional.empty();
		}
	}

	private Optional<BackfillResult> readTeams(String userToken, Map<String, String> headers,
			String org) {
		Map<String, String> teams = new LinkedHashMap<>();
		String url = apiBase + "/user/teams";
		for (int page = 0; ; page++) {
			BackfillHttp.HttpResult result;
			try {
				result = BackfillHttp.get(http, url, userToken, headers);
			} catch (IOException | RuntimeException failed) {
				return Optional.empty();
			} catch (InterruptedException interrupted) {
				Thread.currentThread().interrupt();
				return Optional.empty();
			}
			if (result.status() != 200) {
				return Optional.empty();
			}
			JsonNode root;
			try {
				root = BackfillHttp.parseJson(result.body());
			} catch (IOException malformed) {
				return Optional.empty();
			}
			if (!root.isArray()) {
				return Optional.empty();
			}
			for (JsonNode entry : root) {
				String teamOrg = entry.path("organization").path("login").asString(null);
				if (teamOrg == null || !teamOrg.equalsIgnoreCase(org)) {
					continue;
				}
				String slug = entry.path("slug").asString("");
				if (slug.isBlank()) {
					continue;
				}
				String name = entry.path("name").asString(null);
				if (name == null || name.isBlank()) {
					return Optional.empty();
				}
				teams.putIfAbsent(slug, name);
			}
			String next = BackfillHttp.parseLinkNext(result.header("Link"));
			if (next == null) {
				return Optional.of(new BackfillResult(
						Collections.unmodifiableMap(new LinkedHashMap<>(teams)), false));
			}
			if (page + 1 >= MAX_PAGES || !next.startsWith(apiBase)) {
				return Optional.empty();
			}
			url = next;
		}
	}
}
