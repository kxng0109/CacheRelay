package io.github.kxng0109.cacherelay.auth.webhook;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;

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
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@DisplayName("EntraWebhookController")
class EntraWebhookControllerTest {

	private static final String CLIENT_STATE = "entra-client-state-value-32-chars!";

	private final WebhookInvalidator invalidator = mock(WebhookInvalidator.class);

	private final AuthAuditService audit = mock(AuthAuditService.class);

	private EntraWebhookController controller() {
		SsoWebhookProperties props = new SsoWebhookProperties(List.of(
				new SsoWebhookProperties.RegistrationWebhook("azure", "", "", CLIENT_STATE,
						"")));
		return new EntraWebhookController(props, invalidator, audit);
	}

	private MockHttpServletRequest request(String body) {
		MockHttpServletRequest request = new MockHttpServletRequest("POST",
				"/v1/sso/webhooks/entra");
		request.setContent(body.getBytes(StandardCharsets.UTF_8));
		return request;
	}

	@Test
	@DisplayName("validation handshakes echo the token as text")
	void validationEchoes() {
		ResponseEntity<String> response = controller().receive("token-abc-123",
				new MockHttpServletRequest());

		assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
		assertThat(response.getHeaders().getContentType()).isNotNull();
		assertThat(response.getHeaders().getContentType().toString()).contains("text/plain");
		assertThat(response.getBody()).isEqualTo("token-abc-123");
		verify(invalidator, never()).invalidate(any(), any());
	}

	@Test
	@DisplayName("unconfigured receivers answer 404")
	void unconfiguredAnswers404() {
		EntraWebhookController controller = new EntraWebhookController(
				SsoWebhookProperties.DEFAULTS, invalidator, audit);

		assertThatThrownBy(() -> controller.receive(null, new MockHttpServletRequest()))
				.isInstanceOf(ResponseStatusException.class)
				.extracting(ex -> ((ResponseStatusException) ex).getStatusCode())
				.isEqualTo(HttpStatus.NOT_FOUND);
		verify(invalidator, never()).invalidate(any(), any());
	}

	@Test
	@DisplayName("client-state mismatches answer 401 with an audit record")
	void badClientStateAnswers401() {
		String body = "{\"value\":[{\"subscriptionId\":\"s\",\"clientState\":\"wrong\","
				+ "\"changeType\":\"updated\",\"resource\":\"users/oid-1\"}]}";

		assertThatThrownBy(() -> controller().receive(null, request(body)))
				.isInstanceOf(ResponseStatusException.class)
				.extracting(ex -> ((ResponseStatusException) ex).getStatusCode())
				.isEqualTo(HttpStatus.UNAUTHORIZED);
		verify(audit).record(AuthAuditService.ACTION_WEBHOOK_AUTH,
				AuthAuditService.SEVERITY_WARN, "webhook:entra", "/v1/sso/webhooks/entra",
				AuthAuditService.OUTCOME_FAILURE, null, null);
		verify(invalidator, never()).invalidate(any(), any());
	}

	@Test
	@DisplayName("user changes invalidate by object id")
	void userChangeInvalidates() {
		String body = "{\"value\":["
				+ "{\"subscriptionId\":\"s\",\"clientState\":\"" + CLIENT_STATE + "\","
				+ "\"changeType\":\"updated\",\"resource\":\"users/oid-1\"},"
				+ "{\"subscriptionId\":\"s\",\"clientState\":\"" + CLIENT_STATE + "\","
				+ "\"changeType\":\"deleted\",\"resource\":\"users/oid-2\"}]}";

		ResponseEntity<String> response = controller().receive(null, request(body));

		assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
		verify(invalidator).invalidate("azure", "oid-1");
		verify(invalidator).invalidate("azure", "oid-2");
	}

	@Test
	@DisplayName("member deltas invalidate the member id")
	void memberDeltaInvalidates() {
		String body = "{\"value\":[{\"subscriptionId\":\"s\",\"clientState\":\"" + CLIENT_STATE
				+ "\",\"changeType\":\"updated\","
				+ "\"resource\":\"groups/gid-1/members/mid-9\"}]}";

		ResponseEntity<String> response = controller().receive(null, request(body));

		assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
		verify(invalidator).invalidate("azure", "mid-9");
	}

	@Test
	@DisplayName("group-only changes carry no user and stay ignored")
	void groupOnlyIgnored() {
		String body = "{\"value\":[{\"subscriptionId\":\"s\",\"clientState\":\"" + CLIENT_STATE
				+ "\",\"changeType\":\"updated\",\"resource\":\"groups/gid-1\"}]}";

		ResponseEntity<String> response = controller().receive(null, request(body));

		assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
		verify(invalidator, never()).invalidate(any(), any());
	}

	@Test
	@DisplayName("unreadable bodies answer 400")
	void unreadableBodyAnswers400() throws Exception {
		HttpServletRequest broken = mock(HttpServletRequest.class);
		when(broken.getInputStream()).thenThrow(new IOException("gone"));

		assertThatThrownBy(() -> controller().receive(null, broken))
				.isInstanceOf(ResponseStatusException.class)
				.extracting(ex -> ((ResponseStatusException) ex).getStatusCode())
				.isEqualTo(HttpStatus.BAD_REQUEST);
	}

	@Test
	@DisplayName("malformed envelopes answer 400")
	void malformedAnswers400() {
		assertThatThrownBy(() -> controller().receive(null, request("not-json")))
				.isInstanceOf(ResponseStatusException.class)
				.extracting(ex -> ((ResponseStatusException) ex).getStatusCode())
				.isEqualTo(HttpStatus.BAD_REQUEST);
		assertThatThrownBy(() -> controller().receive(null, request("{\"value\":{}}")))
				.isInstanceOf(ResponseStatusException.class)
				.extracting(ex -> ((ResponseStatusException) ex).getStatusCode())
				.isEqualTo(HttpStatus.BAD_REQUEST);
	}
}
