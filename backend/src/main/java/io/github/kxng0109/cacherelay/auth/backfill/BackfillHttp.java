package io.github.kxng0109.cacherelay.auth.backfill;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Shared HTTP plumbing for IdP backfill clients: one redirect-never client
 * shape, strict body handling, and allowlisted path encoding.
 *
 * <p>Bases are always compile-time vendor constants or startup-validated
 * config; redirect following stays off and paged follow-ups accept same-host
 * URLs only, so user-derived identifiers can never steer requests
 * off-vendor.</p>
 */
public final class BackfillHttp {

	/**
	 * Total per-call budget mirroring the login-blocking allowance.
	 */
	public static final Duration CALL_TIMEOUT = Duration.ofSeconds(8);

	private static final JsonMapper MAPPER = JsonMapper.builder().build();

	private static final Pattern SAFE_SEGMENT = Pattern.compile("^[A-Za-z0-9._@=-]+$");

	private BackfillHttp() {
	}

	/**
	 * Creates a redirect-never client with a short connect timeout.
	 *
	 * @return configured client, never {@code null}
	 */
	public static HttpClient newClient() {
		return HttpClient.newBuilder()
				.connectTimeout(Duration.ofSeconds(2))
				.followRedirects(HttpClient.Redirect.NEVER)
				.build();
	}

	/**
	 * One HTTP response with its status, body, and headers (header names keep
	 * server casing; look them up case-insensitively).
	 *
	 * @param status  response status code
	 * @param body    response body, possibly empty but never {@code null}
	 * @param headers response headers, never {@code null}
	 */
	public record HttpResult(int status, String body, Map<String, List<String>> headers) {

		/**
		 * Reads the first value of a header regardless of casing.
		 *
		 * @param name header name, never {@code null}
		 * @return values, possibly empty but never {@code null}
		 */
		public List<String> header(String name) {
			for (Map.Entry<String, List<String>> entry : headers.entrySet()) {
				if (entry.getKey() != null && entry.getKey().equalsIgnoreCase(name)) {
					return entry.getValue();
				}
			}
			return List.of();
		}
	}

	/**
	 * Sends an authenticated GET.
	 *
	 * @param http    client, never {@code null}
	 * @param url    absolute URL, never {@code null}
	 * @param bearer bearer token, never {@code null}
	 * @return status plus body
	 * @throws IOException on transport failure
	 * @throws InterruptedException on interruption
	 */
	public static HttpResult get(HttpClient http, String url, String bearer)
			throws IOException, InterruptedException {
		return get(http, url, bearer, Map.of());
	}

	/**
	 * Sends an authenticated GET with extra headers.
	 *
	 * @param http    client, never {@code null}
	 * @param url    absolute URL, never {@code null}
	 * @param bearer bearer token, never {@code null}
	 * @param headers extra headers, never {@code null}
	 * @return status plus body
	 * @throws IOException on transport failure
	 * @throws InterruptedException on interruption
	 */
	public static HttpResult get(HttpClient http, String url, String bearer,
			Map<String, String> headers)
			throws IOException, InterruptedException {
		HttpRequest.Builder builder = HttpRequest.newBuilder()
				.uri(URI.create(url))
				.timeout(CALL_TIMEOUT)
				.setHeader("Accept", "application/json")
				.setHeader("Authorization", "Bearer " + bearer);
		for (Map.Entry<String, String> header : headers.entrySet()) {
			builder.setHeader(header.getKey(), header.getValue());
		}
		HttpResponse<String> response = http.send(builder.build(),
				HttpResponse.BodyHandlers.ofString());
		return new HttpResult(response.statusCode(), response.body(),
				response.headers().map());
	}

	/**
	 * Sends a form POST without authorization.
	 *
	 * @param http client, never {@code null}
	 * @param url  absolute URL, never {@code null}
	 * @param form encoded form body, never {@code null}
	 * @return status plus body
	 * @throws IOException on transport failure
	 * @throws InterruptedException on interruption
	 */
	public static HttpResult postForm(HttpClient http, String url, String form)
			throws IOException, InterruptedException {
		HttpRequest request = HttpRequest.newBuilder()
				.uri(URI.create(url))
				.timeout(CALL_TIMEOUT)
				.header("Content-Type", "application/x-www-form-urlencoded")
				.POST(HttpRequest.BodyPublishers.ofString(form))
				.build();
		HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
		return new HttpResult(response.statusCode(), response.body(),
				response.headers().map());
	}

	/**
	 * Parses a JSON body, failing callers closed on malformed payloads.
	 *
	 * <p>Jackson 3 reports malformed payloads as unchecked
	 * {@code JacksonException}s; they are wrapped here so every caller handles
	 * transport and payload failures through one checked path.</p>
	 *
	 * @param body response body, never {@code null}
	 * @return parsed tree
	 * @throws IOException on malformed JSON
	 */
	public static JsonNode parseJson(String body) throws IOException {
		try {
			return MAPPER.readTree(body);
		} catch (JacksonException malformed) {
			throw new IOException("Malformed JSON payload", malformed);
		}
	}

	/**
	 * Encodes one URL path segment after allowlist validation.
	 *
	 * @param segment raw segment, never {@code null}
	 * @return encoded segment
	 * @throws IllegalArgumentException when the segment carries unsafe characters
	 */
	public static String encodeSegment(String segment) {
		if (!SAFE_SEGMENT.matcher(segment).matches()) {
			throw new IllegalArgumentException("Unsafe URL segment");
		}
		return URLEncoder.encode(segment, StandardCharsets.UTF_8).replace("+", "%20");
	}

	/**
	 * URL-encodes a form value.
	 *
	 * @param value raw value, never {@code null}
	 * @return encoded value
	 */
	public static String encodeForm(String value) {
		return URLEncoder.encode(value, StandardCharsets.UTF_8);
	}

	/**
	 * Extracts the {@code rel="next"} URL from Link headers, if any.
	 *
	 * @param headers Link header values, never {@code null}
	 * @return next URL, or {@code null} when absent or malformed
	 */
	public static String parseLinkNext(List<String> headers) {
		for (String value : headers) {
			for (String part : value.split(",")) {
				String[] link = part.trim().split(";");
				if (link.length == 2 && link[1].trim().equalsIgnoreCase("rel=\"next\"")
						&& link[0].trim().startsWith("<") && link[0].trim().endsWith(">")) {
					return link[0].trim().substring(1, link[0].trim().length() - 1);
				}
			}
		}
		return null;
	}
}
