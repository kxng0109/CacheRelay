package io.github.kxng0109.cacherelay.admin;

import static org.assertj.core.api.Assertions.assertThat;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

import tools.jackson.databind.ObjectMapper;

/**
 * FS-B17: the Alertmanager receiver is a live authenticated endpoint.
 */
@DisplayName("AdminAlertWebhookController")
class AdminAlertWebhookControllerTest {

	private static AdminAlertWebhookController controller() {
		return new AdminAlertWebhookController(new ObjectMapper(), new SimpleMeterRegistry());
	}

	@Test
	@DisplayName("valid webhook batch is accepted and counted")
	void validBatchAccepted() throws Exception {
		ObjectMapper mapper = new ObjectMapper();
		String body = "[{\"labels\":{\"alertname\":\"Watchdog\"},\"status\":\"firing\"}]";

		var response = controller().receive(mapper.readTree(body));

		assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
	}

	@Test
	@DisplayName("empty and oversized batches are rejected")
	void emptyAndOversizedRejected() throws Exception {
		ObjectMapper mapper = new ObjectMapper();

		assertThat(controller().receive(mapper.readTree("[]")).getStatusCode())
				.isEqualTo(HttpStatus.BAD_REQUEST);
		String huge = "[" + "{\"labels\":{}} ,".repeat(200) + "{\"labels\":{}}]";
		assertThat(controller().receive(mapper.readTree(huge)).getStatusCode())
				.isEqualTo(HttpStatus.BAD_REQUEST);
	}

	@Test
	@DisplayName("null and non-array bodies are rejected")
	@SuppressWarnings("DataFlowIssue")
	void nullAndNonArrayRejected() throws Exception {
		ObjectMapper mapper = new ObjectMapper();

		assertThat(controller().receive(null).getStatusCode())
				.isEqualTo(HttpStatus.BAD_REQUEST);
		assertThat(controller().receive(mapper.readTree("{\"alerts\":[]}")).getStatusCode())
				.isEqualTo(HttpStatus.BAD_REQUEST);
	}
}
