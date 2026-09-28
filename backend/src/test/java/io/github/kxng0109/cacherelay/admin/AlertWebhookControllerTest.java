package io.github.kxng0109.cacherelay.admin;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for the dedicated Alertmanager receiver (C5): a bearer secret
 * admits batches, wrong or missing credentials fail without data, and an
 * unconfigured secret answers 404.
 */
@DisplayName("AlertWebhookController")
class AlertWebhookControllerTest {

	private static final String SECRET = "test-only-alerts-webhook-secret-32b!";

	private final ObjectMapper mapper = new ObjectMapper();

	private AlertWebhookController controller(String secret) {
		AlertWebhookProperties properties = new AlertWebhookProperties();
		properties.setWebhookSecret(secret);
		return new AlertWebhookController(properties, mapper, new SimpleMeterRegistry());
	}

	private JsonNode batch(String body) throws Exception {
		return mapper.readTree(body);
	}

	@Test
	@DisplayName("valid bearer secret accepts the batch and counts it")
	void validSecretAccepted() throws Exception {
		var response = controller(SECRET).receive(
				batch("[{\"labels\":{\"alertname\":\"Watchdog\"},\"status\":\"firing\"}]"),
				"Bearer " + SECRET);

		assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
		assertThat(response.getBody().path("received").asInt()).isEqualTo(1);
	}

	@Test
	@DisplayName("wrong, missing, and malformed credentials fail with no data")
	void badCredentialsRejected() throws Exception {
		AlertWebhookController receiver = controller(SECRET);
		JsonNode body = batch("[{\"labels\":{}}]");

		assertThat(receiver.receive(body, "Bearer wrong-secret").getStatusCode())
				.isEqualTo(HttpStatus.UNAUTHORIZED);
		assertThat(receiver.receive(body, null).getStatusCode())
				.isEqualTo(HttpStatus.UNAUTHORIZED);
		assertThat(receiver.receive(body, "Token " + SECRET).getStatusCode())
				.isEqualTo(HttpStatus.UNAUTHORIZED);
		assertThat(receiver.receive(body, "Bearer ").getStatusCode())
				.isEqualTo(HttpStatus.UNAUTHORIZED);
	}

	@Test
	@DisplayName("unconfigured secret answers 404 without data")
	void unconfiguredReceiverNotFound() throws Exception {
		JsonNode body = batch("[{\"labels\":{}}]");

		assertThat(controller("").receive(body, "Bearer " + SECRET).getStatusCode())
				.isEqualTo(HttpStatus.NOT_FOUND);
		assertThat(controller(null).receive(body, "Bearer " + SECRET).getStatusCode())
				.isEqualTo(HttpStatus.NOT_FOUND);
	}

	@Test
	@DisplayName("null registry falls back to an isolated registry without failing")
	void nullRegistryFallsBack() throws Exception {
		AlertWebhookProperties properties = new AlertWebhookProperties();
		properties.setWebhookSecret(SECRET);
		AlertWebhookController receiver =
				new AlertWebhookController(properties, mapper, null);

		var response = receiver.receive(
				batch("[{\"labels\":{\"alertname\":\"Watchdog\"},\"status\":\"firing\"}]"),
				"Bearer " + SECRET);

		assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
	}

	@Test
	@DisplayName("null and non-array bodies are rejected, label-less alerts count as unknown")
	@SuppressWarnings("DataFlowIssue")
	void nullAndNonArrayBodiesRejected() throws Exception {
		AlertWebhookController receiver = controller(SECRET);
		String auth = "Bearer " + SECRET;

		assertThat(receiver.receive(null, auth).getStatusCode())
				.isEqualTo(HttpStatus.BAD_REQUEST);
		assertThat(receiver.receive(batch("{\"alerts\":[]}"), auth).getStatusCode())
				.isEqualTo(HttpStatus.BAD_REQUEST);

		var response = receiver.receive(batch("[{\"status\":\"firing\"}]"), auth);

		assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
		assertThat(response.getBody().path("received").asInt()).isEqualTo(1);
	}

	@Test
	@DisplayName("empty and oversized batches are rejected")
	void badBatchesRejected() throws Exception {
		AlertWebhookController receiver = controller(SECRET);
		String auth = "Bearer " + SECRET;

		assertThat(receiver.receive(batch("[]"), auth).getStatusCode())
				.isEqualTo(HttpStatus.BAD_REQUEST);
		String huge = "[" + "{\"labels\":{}} ,".repeat(200) + "{\"labels\":{}}]";
		assertThat(receiver.receive(batch(huge), auth).getStatusCode())
				.isEqualTo(HttpStatus.BAD_REQUEST);
	}
}
