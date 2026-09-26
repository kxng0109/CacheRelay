package io.github.kxng0109.cacherelay.auth.webhook;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("WebhookSecrets")
class WebhookSecretsTest {

	@Test
	@DisplayName("exact secrets match, everything else fails closed")
	void exactMatchOnly() {
		assertThat(WebhookSecrets.constantTimeEquals("secret-value", "secret-value")).isTrue();
		assertThat(WebhookSecrets.constantTimeEquals("secret-value", "wrong")).isFalse();
		assertThat(WebhookSecrets.constantTimeEquals("secret-value", "secret-valu")).isFalse();
		assertThat(WebhookSecrets.constantTimeEquals("secret-value", "secret-value!")).isFalse();
	}

	@Test
	@DisplayName("blank expected and null presented values never match")
	void blankAndNullFailClosed() {
		assertThat(WebhookSecrets.constantTimeEquals("", "anything")).isFalse();
		assertThat(WebhookSecrets.constantTimeEquals("   ", "   ")).isFalse();
		assertThat(WebhookSecrets.constantTimeEquals(null, "anything")).isFalse();
		assertThat(WebhookSecrets.constantTimeEquals("secret-value", null)).isFalse();
		assertThat(WebhookSecrets.constantTimeEquals(null, null)).isFalse();
	}
}
