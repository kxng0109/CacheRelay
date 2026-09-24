package io.github.kxng0109.cacherelay.auth.webhook;

import java.nio.charset.StandardCharsets;
import java.util.List;

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
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

@DisplayName("GoogleWebhookController")
class GoogleWebhookControllerTest {

	private static final String TOKEN = "google-channel-token-value-32-chars";

	private final WebhookInvalidator invalidator = mock(WebhookInvalidator.class);

	private final AuthAuditService audit = mock(AuthAuditService.class);

	private GoogleWebhookController controller() {
		SsoWebhookProperties props = new SsoWebhookProperties(List.of(
				new SsoWebhookProperties.RegistrationWebhook("google", "", "", "", TOKEN)));
		return new GoogleWebhookController(props, invalidator, audit);
	}

	private MockHttpServletRequest request(String channelId, String token, String resourceId,
			String state, String body) {
		MockHttpServletRequest request = new MockHttpServletRequest("POST",
				"/v1/sso/webhooks/google");
		if (channelId != null) {
			request.addHeader("X-Goog-Channel-ID", channelId);
		}
		if (token != null) {
			request.addHeader("X-Goog-Channel-Token", token);
		}
		if (resourceId != null) {
			request.addHeader("X-Goog-Resource-ID", resourceId);
		}
		if (state != null) {
			request.addHeader("X-Goog-Resource-State", state);
		}
		request.setContent(body.getBytes(StandardCharsets.UTF_8));
		return request;
	}

	@Test
	@DisplayName("matching channels invalidate by immutable id")
	void eventInvalidates() {
		String body = "{\"kind\":\"admin#directory#user\",\"id\":\"987654\","
				+ "\"primaryEmail\":\"op@example.com\"}";

		ResponseEntity<Void> response = controller().receive("chan-1", TOKEN, "res-1", "update",
				request("chan-1", TOKEN, "res-1", "update", body));

		assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
		verify(invalidator).invalidate("google", "987654");
	}

	@Test
	@DisplayName("unconfigured receivers answer 404")
	void unconfiguredAnswers404() {
		GoogleWebhookController controller = new GoogleWebhookController(
				SsoWebhookProperties.DEFAULTS, invalidator, audit);

		assertThatThrownBy(() -> controller.receive("chan-1", TOKEN, "res-1", "update",
				request("chan-1", TOKEN, "res-1", "update", "{}")))
				.isInstanceOf(ResponseStatusException.class)
				.extracting(ex -> ((ResponseStatusException) ex).getStatusCode())
				.isEqualTo(HttpStatus.NOT_FOUND);
		verify(invalidator, never()).invalidate(any(), any());
	}

	@Test
	@DisplayName("unknown tokens answer 401 with an audit record")
	void unknownTokenAnswers401() {
		assertThatThrownBy(() -> controller().receive("chan-1", "wrong-token", "res-1", "update",
				request("chan-1", "wrong-token", "res-1", "update", "{}")))
				.isInstanceOf(ResponseStatusException.class)
				.extracting(ex -> ((ResponseStatusException) ex).getStatusCode())
				.isEqualTo(HttpStatus.UNAUTHORIZED);
		assertThatThrownBy(() -> controller().receive(null, TOKEN, "res-1", "update",
				request(null, TOKEN, "res-1", "update", "{}")))
				.isInstanceOf(ResponseStatusException.class)
				.extracting(ex -> ((ResponseStatusException) ex).getStatusCode())
				.isEqualTo(HttpStatus.UNAUTHORIZED);
		verify(audit, times(2)).record(eq(AuthAuditService.ACTION_WEBHOOK_AUTH),
				eq(AuthAuditService.SEVERITY_WARN), eq("webhook:google"),
				eq("/v1/sso/webhooks/google"), eq(AuthAuditService.OUTCOME_FAILURE), isNull(),
				any());
		verify(invalidator, never()).invalidate(any(), any());
	}

	@Test
	@DisplayName("sync messages answer 200 without invalidating")
	void syncIgnored() {
		ResponseEntity<Void> response = controller().receive("chan-1", TOKEN, "res-1", "sync",
				request("chan-1", TOKEN, "res-1", "sync", ""));

		assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
		verify(invalidator, never()).invalidate(any(), any());
	}

	@Test
	@DisplayName("bodies without ids answer 200 without invalidating")
	void missingIdIgnored() {
		String body = "{\"kind\":\"admin#directory#user\"}";

		ResponseEntity<Void> response = controller().receive("chan-1", TOKEN, "res-1", "update",
				request("chan-1", TOKEN, "res-1", "update", body));

		assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
		verify(invalidator).invalidate("google", null);
	}

	@Test
	@DisplayName("malformed bodies answer 200 without invalidating")
	void malformedIgnored() {		ResponseEntity<Void> response = controller().receive("chan-1", TOKEN, "res-1", "update",
				request("chan-1", TOKEN, "res-1", "update", "not-json"));

		assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
		verify(invalidator, never()).invalidate(any(), any());
	}
}
