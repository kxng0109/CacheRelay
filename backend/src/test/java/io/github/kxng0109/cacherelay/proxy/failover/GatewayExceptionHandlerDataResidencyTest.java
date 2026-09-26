package io.github.kxng0109.cacherelay.proxy.failover;

import io.github.kxng0109.cacherelay.security.compliance.DataResidencyBreachException;
import io.github.kxng0109.cacherelay.security.compliance.Jurisdiction;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("GatewayExceptionHandler Data Residency Tests")
class GatewayExceptionHandlerDataResidencyTest {

	private final GatewayExceptionHandler handler = new GatewayExceptionHandler();

	@Test
	@DisplayName("FS-B11: breach maps to 503 with a fixed message free of policy detail")
	void mapsDataResidencyBreachTo503() {
		DataResidencyBreachException exception = new DataResidencyBreachException(Jurisdiction.NG, "gpt-4o");
		ResponseEntity<Map<String, Object>> response = handler.handleDataResidencyBreach(exception);

		assertThat(response.getStatusCode()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
		assertThat(response.getBody()).isNotNull();

		@SuppressWarnings("unchecked")
		Map<String, Object> error = (Map<String, Object>) response.getBody().get("error");
		assertThat(error).containsEntry("code", "DATA_SOVEREIGNTY_VIOLATION");
		String message = (String) error.get("message");
		assertThat(message)
				.doesNotContain("NG")
				.doesNotContain("gpt-4o")
				.doesNotContain("designated sovereign zone");
	}
}
