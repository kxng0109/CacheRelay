package io.github.kxng0109.cacherelay.auth.webhook;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

import jakarta.servlet.http.HttpServletRequest;

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
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@DisplayName("OktaWebhookController")
class OktaWebhookControllerTest {

	private static final String SECRET = "okta-hook-secret-value-32-chars!!";

	private final WebhookInvalidator invalidator = mock(WebhookInvalidator.class);

	private final AuthAuditService audit = mock(AuthAuditService.class);

	private OktaWebhookController controller() {
		SsoWebhookProperties props = new SsoWebhookProperties(List.of(
				new SsoWebhookProperties.RegistrationWebhook("okta", "", SECRET, "", "")));
		return new OktaWebhookController(props, invalidator, audit);
	}

	private MockHttpServletRequest request(String body) {
		MockHttpServletRequest request = new MockHttpServletRequest("POST",
				"/v1/sso/webhooks/okta");
		request.addHeader("Authorization", SECRET);
		request.setContent(body.getBytes(StandardCharsets.UTF_8));
		return request;
	}

	@Test
	@DisplayName("verification echoes the challenge")
	void verificationEchoes() {
		ResponseEntity<Map<String, String>> response =
				controller().verify("challenge-1");

		assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
		assertThat(response.getBody()).isNotNull();
		assertThat(response.getBody().get("verification")).isEqualTo("challenge-1");
	}

	@Test
	@DisplayName("missing challenges answer 400")
	void missingChallengeAnswers400() {
		assertThatThrownBy(() -> controller().verify(null))
				.isInstanceOf(ResponseStatusException.class)
				.extracting(ex -> ((ResponseStatusException) ex).getStatusCode())
				.isEqualTo(HttpStatus.BAD_REQUEST);
		assertThatThrownBy(() -> controller().verify("  "))
				.isInstanceOf(ResponseStatusException.class)
				.extracting(ex -> ((ResponseStatusException) ex).getStatusCode())
				.isEqualTo(HttpStatus.BAD_REQUEST);
	}

	@Test
	@DisplayName("unconfigured receivers answer 404")
	void unconfiguredAnswers404() {
		OktaWebhookController controller = new OktaWebhookController(
				SsoWebhookProperties.DEFAULTS, invalidator, audit);

		assertThatThrownBy(() -> controller.verify("challenge-1"))
				.isInstanceOf(ResponseStatusException.class)
				.extracting(ex -> ((ResponseStatusException) ex).getStatusCode())
				.isEqualTo(HttpStatus.NOT_FOUND);
		assertThatThrownBy(() -> controller.receive(SECRET, request("{}")))
				.isInstanceOf(ResponseStatusException.class)
				.extracting(ex -> ((ResponseStatusException) ex).getStatusCode())
				.isEqualTo(HttpStatus.NOT_FOUND);
		verify(invalidator, never()).invalidate(any(), any());
	}

	@Test
	@DisplayName("bad secrets answer 401 with an audit record")
	void badSecretAnswers401() {
		MockHttpServletRequest request = new MockHttpServletRequest("POST",
				"/v1/sso/webhooks/okta");
		request.setContent("{}".getBytes(StandardCharsets.UTF_8));

		assertThatThrownBy(() -> controller().receive("wrong-secret", request))
				.isInstanceOf(ResponseStatusException.class)
				.extracting(ex -> ((ResponseStatusException) ex).getStatusCode())
				.isEqualTo(HttpStatus.UNAUTHORIZED);
		assertThatThrownBy(() -> controller().receive(null, request))
				.isInstanceOf(ResponseStatusException.class)
				.extracting(ex -> ((ResponseStatusException) ex).getStatusCode())
				.isEqualTo(HttpStatus.UNAUTHORIZED);
		verify(audit, times(2)).record(AuthAuditService.ACTION_WEBHOOK_AUTH,
				AuthAuditService.SEVERITY_WARN, "webhook:okta", "/v1/sso/webhooks/okta",
				AuthAuditService.OUTCOME_FAILURE, null, null);
		verify(invalidator, never()).invalidate(any(), any());
	}

	@Test
	@DisplayName("lifecycle events invalidate user targets")
	void lifecycleInvalidates() {
		String body = "{\"eventType\":\"com.okta.event_hook\",\"data\":{\"events\":[{"
				+ "\"eventType\":\"user.lifecycle.deactivate\","
				+ "\"target\":[{\"id\":\"00u1\",\"type\":\"User\",\"alternateId\":\"op\"}]}]}}";

		ResponseEntity<Void> response = controller().receive(SECRET, request(body));

		assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
		verify(invalidator).invalidate("okta", "00u1");
	}

	@Test
	@DisplayName("non-user targets and other types are skipped")
	void nonUserTargetsSkipped() {
		String body = "{\"eventType\":\"com.okta.event_hook\",\"data\":{\"events\":[{"
				+ "\"eventType\":\"user.session.start\","
				+ "\"target\":[{\"id\":\"ae1\",\"type\":\"AuthenticatorEnrollment\"}]},"
				+ "{\"eventType\":\"user.session.start\",\"target\":[]}]}}";

		ResponseEntity<Void> response = controller().receive(SECRET, request(body));

		assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
		verify(invalidator, never()).invalidate(any(), any());
	}

	@Test
	@DisplayName("non-array envelopes answer 200 without invalidating")
	void nonArrayEnvelopeIgnored() {
		ResponseEntity<Void> response = controller().receive(SECRET, request("{}"));

		assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
		verify(invalidator, never()).invalidate(any(), any());
	}

	@Test
	@DisplayName("lifecycle events with mixed targets invalidate users only")
	void mixedTargetsFilter() {
		String body = "{\"eventType\":\"com.okta.event_hook\",\"data\":{\"events\":[{"
				+ "\"eventType\":\"user.lifecycle.unsuspend\","
				+ "\"target\":[{\"id\":\"ae1\",\"type\":\"AuthenticatorEnrollment\"},"
				+ "{\"id\":\"00u9\",\"type\":\"User\"}]}]}}";

		ResponseEntity<Void> response = controller().receive(SECRET, request(body));

		assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
		verify(invalidator).invalidate("okta", "00u9");
	}

	@Test
	@DisplayName("unreadable bodies answer 400")
	void unreadableBodyAnswers400() throws Exception {
		HttpServletRequest broken = mock(HttpServletRequest.class);
		when(broken.getInputStream()).thenThrow(new IOException("gone"));

		assertThatThrownBy(() -> controller().receive(SECRET, broken))
				.isInstanceOf(ResponseStatusException.class)
				.extracting(ex -> ((ResponseStatusException) ex).getStatusCode())
				.isEqualTo(HttpStatus.BAD_REQUEST);
	}

	@Test
	@DisplayName("malformed payloads answer 200 without invalidating")
	void malformedIgnored() {
		ResponseEntity<Void> response = controller().receive(SECRET, request("not-json"));

		assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
		verify(invalidator, never()).invalidate(any(), any());
	}
}
