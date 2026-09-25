package io.github.kxng0109.cacherelay.contracts;

import io.github.kxng0109.cacherelay.SharedContainersBase;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Verifies that the multi provider configuration in application.yml binds correctly, in particular the model aliases
 * whose names contain dots. Dotted YAML keys are quoted so they bind as literal map keys rather than nested property
 * paths.
 */
@SpringBootTest
@DisplayName("GatewayProperties binding")
class GatewayPropertiesBindingTest extends SharedContainersBase {

	@DynamicPropertySource
	static void sharedContainers(DynamicPropertyRegistry registry) {
		registry.add("spring.datasource.url", SharedContainersBase::postgresJdbcUrl);
		registry.add("spring.datasource.username", SharedContainersBase::postgresUsername);
		registry.add("spring.datasource.password", SharedContainersBase::postgresPassword);
		registry.add("spring.data.redis.host", SharedContainersBase::redisHost);
		registry.add("spring.data.redis.port", SharedContainersBase::redisPort);
	}

	@Autowired
	private GatewayProperties gatewayProperties;

	@Test
	@DisplayName("providers bind from application.yml")
	void providersBind() {
		assertNotNull(gatewayProperties.getProviders());
		assertTrue(
				gatewayProperties.getProviders().containsKey("openai"),
				"the openai provider must be configured"
		);
		assertTrue(
				gatewayProperties.getProviders().containsKey("ollama"),
				"the ollama provider must be configured"
		);
	}

	@Test
	@DisplayName("aliases bind as literal map keys")
	void dottedAliasesBind() {
		assertNotNull(gatewayProperties.getAliases());
		assertTrue(
				gatewayProperties.getAliases().containsKey("local-llama"),
				"the alias local-llama must bind as a single key"
		);
		assertTrue(
				gatewayProperties.getAliases().containsKey("local-embed"),
				"the alias local-embed must bind as a single key"
		);
	}

	@Test
	@DisplayName("alias chains and strategies bind correctly")
	void aliasDetailsBind() {
		ModelAlias localLlama = gatewayProperties.getAliases().get("local-llama");
		assertNotNull(localLlama);
		assertEquals(1, localLlama.chain().size(), "the local-llama alias must chain one provider");
		assertEquals("ollama", localLlama.chain().getFirst().providerName());
		assertEquals(FailoverStrategy.SEQUENTIAL, localLlama.strategy());
	}
}