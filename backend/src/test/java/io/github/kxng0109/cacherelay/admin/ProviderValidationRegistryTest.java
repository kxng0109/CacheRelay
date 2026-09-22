package io.github.kxng0109.cacherelay.admin;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.stream.Stream;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

import io.github.kxng0109.cacherelay.admin.dto.ProviderValidationStatus;

/**
 * Pins the validation evidence table: every configured provider name resolves to its
 * researched status, and unknown names never read as trusted.
 */
@DisplayName("ProviderValidationRegistry")
class ProviderValidationRegistryTest {

	private static Stream<Arguments> authReachable() {
		return Stream.of("openai", "openrouter", "anthropic", "together", "groq", "mistral",
				"xai", "deepinfra", "fireworks", "cerebras", "sambanova", "nebius", "novita",
				"moonshot", "zhipu", "minimax", "qwen", "stepfun", "cloudflare", "hyperbolic",
				"ionet", "friendli", "bedrock").map(Arguments::of);
	}

	private static Stream<Arguments> contractChecked() {
		return Stream.of("ollama", "vllm", "llamacpp", "lmstudio").map(Arguments::of);
	}

	@ParameterizedTest(name = "{0} is auth-reachable")
	@MethodSource("authReachable")
	@DisplayName("dummy-probed providers resolve to auth-reachable")
	void authReachableProviders(String name) {
		assertThat(ProviderValidationRegistry.statusOf(name))
				.isEqualTo(ProviderValidationStatus.AUTH_REACHABLE);
	}

	@ParameterizedTest(name = "{0} is contract-checked")
	@MethodSource("contractChecked")
	@DisplayName("self-hosted providers resolve to contract-checked")
	void contractCheckedProviders(String name) {
		assertThat(ProviderValidationRegistry.statusOf(name))
				.isEqualTo(ProviderValidationStatus.CONTRACT_CHECKED);
	}

	@ParameterizedTest
	@NullAndEmptySource
	@ValueSource(strings = {"unknown-provider", "OpenAI", "  "})
	@DisplayName("unknown names resolve to unverified, never trusted")
	void unknownNamesUnverified(String name) {
		assertThat(ProviderValidationRegistry.statusOf(name))
				.isEqualTo(ProviderValidationStatus.UNVERIFIED);
	}

	@Test
	@DisplayName("nothing is marked live-verified without live inference evidence")
	void nothingLiveVerified() {
		Stream.concat(authReachable(), contractChecked())
				.map(args -> (String) args.get()[0])
				.forEach(name -> assertThat(ProviderValidationRegistry.statusOf(name))
						.as(name + " is not live-verified")
						.isNotEqualTo(ProviderValidationStatus.LIVE_VERIFIED));
	}
}
