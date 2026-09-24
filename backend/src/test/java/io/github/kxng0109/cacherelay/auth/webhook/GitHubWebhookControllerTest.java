package io.github.kxng0109.cacherelay.auth.webhook;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;

import jakarta.servlet.http.HttpServletRequest;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

import io.github.kxng0109.cacherelay.auth.AuthAuditService;
import io.github.kxng0109.cacherelay.auth.SsoWebhookProperties;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.server.ResponseStatusException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@DisplayName("GitHubWebhookController")
class GitHubWebhookControllerTest {

	private static final String SECRET = "webhook-secret";

	private final WebhookInvalidator invalidator = mock(WebhookInvalidator.class);

	private final AuthAuditService audit = mock(AuthAuditService.class);

	private GitHubWebhookController controller() {
		SsoWebhookProperties props = new SsoWebhookProperties(List.of(
				new SsoWebhookProperties.RegistrationWebhook("github", SECRET, "", "", "")));
		return new GitHubWebhookController(props, invalidator, audit);
	}

	private MockHttpServletRequest request(String event, String delivery, byte[] body) {
		MockHttpServletRequest request = new MockHttpServletRequest("POST",
				"/v1/sso/webhooks/github");
		request.addHeader("X-Hub-Signature-256", sign(body));
		request.addHeader("X-GitHub-Event", event);
		request.addHeader("X-GitHub-Delivery", delivery);
		request.setContent(body);
		return request;
	}

	private String sign(byte[] body) {
		try {
			Mac mac = Mac.getInstance("HmacSHA256");
			mac.init(new SecretKeySpec(SECRET.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
			StringBuilder hex = new StringBuilder("sha256=");
			for (byte octet : mac.doFinal(body)) {
				hex.append(Character.forDigit((octet >> 4) & 0xF, 16));
				hex.append(Character.forDigit(octet & 0xF, 16));
			}
			return hex.toString();
		} catch (Exception failed) {
			throw new IllegalStateException("Test HMAC failed", failed);
		}
	}

	@Test
	@DisplayName("the documented test vector verifies")
	void rfcTestVector() throws Exception {
		Mac mac = Mac.getInstance("HmacSHA256");
		mac.init(new SecretKeySpec("It's a Secret to Everybody".getBytes(StandardCharsets.UTF_8),
				"HmacSHA256"));
		StringBuilder hex = new StringBuilder("sha256=");
		for (byte octet : mac.doFinal("Hello, World!".getBytes(StandardCharsets.UTF_8))) {
			hex.append(Character.forDigit((octet >> 4) & 0xF, 16));
			hex.append(Character.forDigit(octet & 0xF, 16));
		}

		assertThat(GitHubWebhookController.validSignature(
				"Hello, World!".getBytes(StandardCharsets.UTF_8), hex.toString(),
				"It's a Secret to Everybody")).isTrue();
		assertThat(hex.toString()).isEqualTo(
				"sha256=757107ea0eb2509fc211221cce984b8a37570b6d7586c22c46f4379c8b043e17");
		assertThat(GitHubWebhookController.validSignature(
				"Hello, World!".getBytes(StandardCharsets.UTF_8), null,
				"It's a Secret to Everybody")).isFalse();
		assertThat(GitHubWebhookController.validSignature(
				"Hello, World!".getBytes(StandardCharsets.UTF_8), "md5=deadbeef",
				"It's a Secret to Everybody")).isFalse();
	}

	@Test
	@DisplayName("unconfigured receivers answer 404")
	void unconfiguredAnswers404() {
		GitHubWebhookController controller = new GitHubWebhookController(
				SsoWebhookProperties.DEFAULTS, invalidator, audit);
		MockHttpServletRequest request = new MockHttpServletRequest("POST",
				"/v1/sso/webhooks/github");

		assertThatThrownBy(() -> controller.receive("sha256=x", "push", "d-1", request))
				.isInstanceOf(ResponseStatusException.class)
				.extracting(ex -> ((ResponseStatusException) ex).getStatusCode())
				.isEqualTo(HttpStatus.NOT_FOUND);
		verify(invalidator, never()).invalidate(any(), any());
	}

	@Test
	@DisplayName("bad signatures answer 401 with an audit record")
	void badSignatureAnswers401() {
		MockHttpServletRequest request = new MockHttpServletRequest("POST",
				"/v1/sso/webhooks/github");
		request.addHeader("X-GitHub-Event", "push");
		request.addHeader("X-GitHub-Delivery", "d-1");
		request.setContent("{}".getBytes(StandardCharsets.UTF_8));

		assertThatThrownBy(() -> controller().receive(
				"sha256=deadbeef", "push", "d-1", request))
				.isInstanceOf(ResponseStatusException.class)
				.extracting(ex -> ((ResponseStatusException) ex).getStatusCode())
				.isEqualTo(HttpStatus.UNAUTHORIZED);
		verify(audit).record(AuthAuditService.ACTION_WEBHOOK_AUTH,
				AuthAuditService.SEVERITY_WARN, "webhook:github", "/v1/sso/webhooks/github",
				AuthAuditService.OUTCOME_FAILURE, null, "d-1");
		verify(invalidator, never()).invalidate(any(), any());
	}

	@Test
	@DisplayName("missing signatures answer 401")
	void missingSignatureAnswers401() {
		MockHttpServletRequest request = new MockHttpServletRequest("POST",
				"/v1/sso/webhooks/github");
		request.setContent("{}".getBytes(StandardCharsets.UTF_8));

		assertThatThrownBy(() -> controller().receive(null, "push", "d-1", request))
				.isInstanceOf(ResponseStatusException.class)
				.extracting(ex -> ((ResponseStatusException) ex).getStatusCode())
				.isEqualTo(HttpStatus.UNAUTHORIZED);
	}

	@Test
	@DisplayName("pings answer 200 without invalidating")
	void pingIgnored() {
		byte[] body = "{\"zen\":\"hello\",\"hook_id\":1}".getBytes(StandardCharsets.UTF_8);

		ResponseEntity<Void> response = controller().receive(sign(body), "ping", "d-1",
				request("ping", "d-1", body));

		assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
		verify(invalidator, never()).invalidate(any(), any());
	}

	@Test
	@DisplayName("membership removals invalidate by numeric id")
	void membershipRemovalInvalidatesById() {
		when(invalidator.invalidate("github", "123456")).thenReturn(true);
		byte[] body = ("{\"action\":\"removed\",\"member\":{\"login\":\"op\",\"id\":123456},"
				+ "\"organization\":{\"login\":\"acme\"},\"team\":{\"slug\":\"eng\"}}").getBytes(
				StandardCharsets.UTF_8);

		ResponseEntity<Void> response = controller().receive(sign(body), "membership", "d-1",
				request("membership", "d-1", body));

		assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
		verify(invalidator).invalidate("github", "123456");
		verify(invalidator, never()).invalidate("github", "op");
	}

	@Test
	@DisplayName("organization removals fall back to login matching")
	void organizationRemovalFallsBackToLogin() {
		when(invalidator.invalidate("github", "123456")).thenReturn(false);
		when(invalidator.invalidate("github", "op")).thenReturn(true);
		byte[] body = ("{\"action\":\"member_removed\","
				+ "\"membership\":{\"user\":{\"login\":\"op\",\"id\":123456}},"
				+ "\"organization\":{\"login\":\"acme\"}}").getBytes(StandardCharsets.UTF_8);

		ResponseEntity<Void> response = controller().receive(sign(body), "organization", "d-2",
				request("organization", "d-2", body));

		assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
		verify(invalidator).invalidate("github", "123456");
		verify(invalidator).invalidate("github", "op");
	}

	@Test
	@DisplayName("unresolvable users still answer 200")
	void unknownUsersIgnored() {
		when(invalidator.invalidate("github", "123456")).thenReturn(false);
		when(invalidator.invalidate("github", "op")).thenReturn(false);
		byte[] body = ("{\"action\":\"removed\",\"member\":{\"login\":\"op\",\"id\":123456},"
				+ "\"organization\":{\"login\":\"acme\"}}").getBytes(StandardCharsets.UTF_8);

		ResponseEntity<Void> response = controller().receive(sign(body), "membership", "d-4",
				request("membership", "d-4", body));

		assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
	}

	@Test
	@DisplayName("signed malformed payloads answer 400")
	void malformedPayloadAnswers400() {
		byte[] body = "not-json".getBytes(StandardCharsets.UTF_8);

		assertThatThrownBy(() -> controller().receive(sign(body), "membership", "d-5",
				request("membership", "d-5", body)))
				.isInstanceOf(ResponseStatusException.class)
				.extracting(ex -> ((ResponseStatusException) ex).getStatusCode())
				.isEqualTo(HttpStatus.BAD_REQUEST);
	}

	@Test
	@DisplayName("login-only payloads fall back without identifiers")
	void loginOnlyFallsBack() {
		when(invalidator.invalidate("github", "op")).thenReturn(true);
		String json = "{\"action\":\"removed\",\"member\":{\"id\":\"\",\"login\":\"op\"}}";
		byte[] body = json.getBytes(StandardCharsets.UTF_8);

		ResponseEntity<Void> response = controller().receive(sign(body), "membership", "d-7",
				request("membership", "d-7", body));

		assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
		verify(invalidator).invalidate("github", "op");
	}

	@Test
	@DisplayName("identifier-free payloads answer 200 silently")
	void identifierFreeIgnored() {
		String json = "{\"action\":\"removed\",\"member\":{}}";
		byte[] payload = json.getBytes(StandardCharsets.UTF_8);
		String payloadSignature = sign(payload);
		MockHttpServletRequest payloadRequest = request("membership", "d-8", payload);

		ResponseEntity<Void> response =
				controller().receive(payloadSignature, "membership", "d-8", payloadRequest);

		assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
		verify(invalidator, never()).invalidate(any(), any());
	}

	@Test
	@DisplayName("unreadable bodies answer 400")
	void unreadableBodyAnswers400() throws Exception {
		HttpServletRequest broken = mock(HttpServletRequest.class);
		when(broken.getInputStream()).thenThrow(new IOException("gone"));

		assertThatThrownBy(() -> controller().receive(sign(new byte[0]), "membership", "d-9",
				broken))
				.isInstanceOf(ResponseStatusException.class)
				.extracting(ex -> ((ResponseStatusException) ex).getStatusCode())
				.isEqualTo(HttpStatus.BAD_REQUEST);
	}

	@Test
	@DisplayName("unrelated events answer 200 without invalidating")
	void unrelatedEventsIgnored() {
		byte[] body = "{}".getBytes(StandardCharsets.UTF_8);

		ResponseEntity<Void> response = controller().receive(sign(body), "push", "d-6",
				request("push", "d-6", body));

		assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
		verify(invalidator, never()).invalidate(any(), any());
	}
}
