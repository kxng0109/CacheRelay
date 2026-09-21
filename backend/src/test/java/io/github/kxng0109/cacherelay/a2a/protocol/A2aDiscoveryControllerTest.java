package io.github.kxng0109.cacherelay.a2a.protocol;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.kxng0109.cacherelay.a2a.config.A2aGatewayProperties;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * Unit tests for {@link A2aDiscoveryController}: the public card advertises the
 * gateway without disclosing any agent inventory.
 */
@DisplayName("A2aDiscoveryController")
class A2aDiscoveryControllerTest {

	private final ObjectMapper objectMapper = new ObjectMapper();

	private A2aGatewayProperties properties;

	private A2aDiscoveryController controller;

	@BeforeEach
	void setUp() {
		properties = new A2aGatewayProperties();
		properties.setPublicBaseUrl("https://gateway.example.com/");
		properties.setGatewayVersion("9.9.9");
		controller = new A2aDiscoveryController(properties, objectMapper);
	}

	@Test
	@DisplayName("serves a well-formed v0.3 card without agent inventory")
	void servesMinimalCard() throws Exception {
		ResponseEntity<String> response = controller.gatewayCard();

		assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
		JsonNode card = objectMapper.readTree(response.getBody());
		assertThat(card.get("protocolVersion").asString()).isEqualTo("0.3");
		assertThat(card.get("name").asString()).isEqualTo("CacheRelay A2A Gateway");
		assertThat(card.get("url").asString()).isEqualTo("https://gateway.example.com/v1/a2a");
		assertThat(card.get("version").asString()).isEqualTo("9.9.9");
		assertThat(card.get("skills").isArray()).isTrue();
		assertThat(card.get("skills").size()).isZero();
		assertThat(card.get("capabilities").get("streaming").asBoolean()).isFalse();
		assertThat(card.get("capabilities").get("pushNotifications").asBoolean()).isFalse();
		assertThat(card.get("securitySchemes").get("virtualKeyBearer").get("scheme").asString())
				.isEqualTo("bearer");
	}

	@Test
	@DisplayName("is invisible when the A2A subsystem is disabled")
	void disabledReturnsNotFound() {
		properties.setEnabled(false);

		ResponseEntity<String> response = controller.gatewayCard();

		assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
	}

	@Test
	@DisplayName("declares the modes required by the spec")
	void declaresSpecFields() throws Exception {
		JsonNode card = objectMapper.readTree(controller.gatewayCard().getBody());

		assertThat(card.get("defaultInputModes").isArray()).isTrue();
		assertThat(card.get("defaultOutputModes").isArray()).isTrue();
		assertThat(card.get("description").asString()).isNotBlank();
	}

	@Test
	@DisplayName("normalizes a base URL without a trailing slash")
	void baseUrlWithoutTrailingSlash() throws Exception {
		properties.setPublicBaseUrl("https://gateway.example.com");

		JsonNode card = objectMapper.readTree(controller.gatewayCard().getBody());

		assertThat(card.get("url").asString()).isEqualTo("https://gateway.example.com/v1/a2a");
	}
}
