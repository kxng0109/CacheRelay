package io.github.kxng0109.cacherelay.proxy.protocol;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.net.URI;
import java.time.Duration;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.bind.BindResult;
import org.springframework.boot.context.properties.source.ConfigurationPropertySources;
import org.springframework.core.env.MapPropertySource;
import tools.jackson.databind.ObjectMapper;

import io.github.kxng0109.cacherelay.config.SensitiveString;
import io.github.kxng0109.cacherelay.contracts.ProviderConfig;
import io.github.kxng0109.cacherelay.contracts.ProviderType;

/**
 * Exhaustive URL-join coverage for per-provider chat-completions paths.
 */
@DisplayName("ProviderChatPath")
class ProviderChatPathTest {

	private final OpenAiPassthroughAdapter adapter =
			new OpenAiPassthroughAdapter(new ObjectMapper());

	private static ProviderConfig config(String baseUrl, String path) {
		return new ProviderConfig(
				"p", ProviderType.OPENAI, URI.create(baseUrl), null,
				Duration.ofSeconds(3), Duration.ofSeconds(30), false, path);
	}

	@ParameterizedTest(name = "{0} -> {1}")
	@CsvSource({
			"https://api.openai.com, https://api.openai.com/v1/chat/completions",
			"https://api.openai.com/, https://api.openai.com/v1/chat/completions",
			"https://openrouter.ai/api, https://openrouter.ai/api/v1/chat/completions",
			"https://api.together.ai, https://api.together.ai/v1/chat/completions",
			"https://api.groq.com/openai, https://api.groq.com/openai/v1/chat/completions",
			"https://api.mistral.ai, https://api.mistral.ai/v1/chat/completions",
			"https://api.x.ai, https://api.x.ai/v1/chat/completions",
			"https://api.fireworks.ai/inference, https://api.fireworks.ai/inference/v1/chat/completions",
			"https://api.cerebras.ai, https://api.cerebras.ai/v1/chat/completions",
			"https://api.sambanova.ai, https://api.sambanova.ai/v1/chat/completions",
			"https://api.studio.nebius.com, https://api.studio.nebius.com/v1/chat/completions",
			"https://api.novita.ai/openai, https://api.novita.ai/openai/v1/chat/completions",
			"https://api.moonshot.ai, https://api.moonshot.ai/v1/chat/completions",
			"https://api.minimax.io, https://api.minimax.io/v1/chat/completions",
			"https://api.stepfun.com, https://api.stepfun.com/v1/chat/completions",
			"https://api.hyperbolic.xyz, https://api.hyperbolic.xyz/v1/chat/completions",
			"https://api.intelligence.io.solutions/api, https://api.intelligence.io.solutions/api/v1/chat/completions",
			"https://api.friendli.ai/serverless, https://api.friendli.ai/serverless/v1/chat/completions",
			"https://api.cloudflare.com/client/v4/accounts/123/ai, https://api.cloudflare.com/client/v4/accounts/123/ai/v1/chat/completions",
			"https://dashscope.aliyuncs.com/compatible-mode, https://dashscope.aliyuncs.com/compatible-mode/v1/chat/completions",
			"https://bedrock-runtime.us-east-1.amazonaws.com/openai, https://bedrock-runtime.us-east-1.amazonaws.com/openai/v1/chat/completions",
			"http://localhost:8000, http://localhost:8000/v1/chat/completions",
			"http://localhost:8081/, http://localhost:8081/v1/chat/completions",
			"http://localhost:1234, http://localhost:1234/v1/chat/completions",
			"https://api.deepinfra.com, https://api.deepinfra.com/v1/chat/completions",
			"https://open.bigmodel.cn/api/paas/v4, https://open.bigmodel.cn/api/paas/v4/v1/chat/completions"
	})
	@DisplayName("default path joins every configured base shape without doubling")
	void defaultPathJoins(String baseUrl, String expected) {
		assertThat(adapter.buildUpstreamUrl(config(baseUrl, null)).toString()).isEqualTo(expected);
	}

	@ParameterizedTest(name = "{0} + {1} -> {2}")
	@CsvSource({
			"https://open.bigmodel.cn/api/paas/v4, /chat/completions, https://open.bigmodel.cn/api/paas/v4/chat/completions",
			"https://api.deepinfra.com, /v1/openai/chat/completions, https://api.deepinfra.com/v1/openai/chat/completions",
			"https://api.example.com/, /v1/chat/completions, https://api.example.com/v1/chat/completions"
	})
	@DisplayName("per-provider path override replaces the default")
	void overridePathUsed(String baseUrl, String path, String expected) {
		assertThat(adapter.buildUpstreamUrl(config(baseUrl, path)).toString()).isEqualTo(expected);
	}

	@ParameterizedTest
	@NullAndEmptySource
	@ValueSource(strings = {"   ", "\t"})
	@DisplayName("absent or blank path falls back to the default")
	void blankPathDefaults(String path) {
		assertThat(config("https://api.example.com", path).chatCompletionsPath())
				.isEqualTo("/v1/chat/completions");
	}

	@Test
	@DisplayName("missing leading slash is prepended")
	void missingLeadingSlashPrepended() {
		assertThat(config("https://api.example.com", "chat/completions").chatCompletionsPath())
				.isEqualTo("/chat/completions");
	}

	@Test
	@DisplayName("legacy constructors default to the standard path")
	void backCompatCtorsDefault() {
		ProviderConfig sixArg = new ProviderConfig(
				"p", ProviderType.OPENAI, URI.create("https://api.example.com"), null,
				Duration.ofSeconds(3), Duration.ofSeconds(30));
		ProviderConfig sevenArg = new ProviderConfig(
				"p", ProviderType.OPENAI, URI.create("https://api.example.com"), null,
				Duration.ofSeconds(3), Duration.ofSeconds(30), false);

		assertThat(sixArg.chatCompletionsPath()).isEqualTo("/v1/chat/completions");
		assertThat(sevenArg.chatCompletionsPath()).isEqualTo("/v1/chat/completions");
		assertThat(adapter.buildUpstreamUrl(sixArg).toString())
				.isEqualTo("https://api.example.com/v1/chat/completions");
	}

	@Test
	@DisplayName("canonical constructor carries a custom path")
	void canonicalCtorCarriesPath() {
		ProviderConfig custom = new ProviderConfig(
				"p", ProviderType.OPENAI, URI.create("https://api.example.com"), new SensitiveString("k"),
				Duration.ofSeconds(3), Duration.ofSeconds(30), true, "/v1/openai/chat/completions");

		assertThat(custom.chatCompletionsPath()).isEqualTo("/v1/openai/chat/completions");
		assertThat(custom.isEmbeddingSingleAsString()).isTrue();
	}

	@Test
	@DisplayName("space in path fails closed at URL build time")
	void spaceInPathFailsClosed() {
		ProviderConfig bad = config("https://api.example.com", "/chat comp/letions");

		assertThatThrownBy(() -> adapter.buildUpstreamUrl(bad))
				.isInstanceOf(IllegalArgumentException.class);
	}

	@Test
	@DisplayName("CRLF in path fails closed at URL build time")
	void crlfInPathFailsClosed() {
		ProviderConfig bad = config("https://api.example.com", "/chat/completions\r\nX-Injected: 1");

		assertThatThrownBy(() -> adapter.buildUpstreamUrl(bad))
				.isInstanceOf(IllegalArgumentException.class);
	}

	@Test
	@DisplayName("kebab-case chat-completions-path binds to the record component")
	void kebabCaseBinds() {
		Map<String, Object> values = new HashMap<>();
		values.put("p.name", "p");
		values.put("p.type", "OPENAI");
		values.put("p.base-url", "https://api.example.com");
		values.put("p.chat-completions-path", "/chat/completions");
		Binder binder = new Binder(ConfigurationPropertySources.from(new MapPropertySource("test", values)));

		BindResult<ProviderConfig> result = binder.bind("p", ProviderConfig.class);

		assertThat(result.isBound()).as("chat-completions-path binds").isTrue();
		ProviderConfig bound = Objects.requireNonNull(result.get());

		assertThat(bound.chatCompletionsPath()).isEqualTo("/chat/completions");
		assertThat(adapter.buildUpstreamUrl(bound).toString())
				.isEqualTo("https://api.example.com/chat/completions");
	}
}
