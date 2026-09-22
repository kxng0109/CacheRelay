package io.github.kxng0109.cacherelay.infra;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.Reader;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.yaml.snakeyaml.Yaml;
import tools.jackson.databind.ObjectMapper;

import io.github.kxng0109.cacherelay.config.SensitiveString;
import io.github.kxng0109.cacherelay.contracts.ProviderConfig;
import io.github.kxng0109.cacherelay.contracts.ProviderType;
import io.github.kxng0109.cacherelay.proxy.protocol.OpenAiPassthroughAdapter;

/**
 * Locks the researched provider contracts into {@code application.yml}: every OpenAI-dialect
 * provider joins to its verified chat URL (no doubled version prefix), the two odd-path
 * providers carry overrides, and non-OpenAI entries keep their bases and types.
 */
@DisplayName("Provider URL contract matrix (27 researched providers)")
class ProviderUrlMatrixTest {

	private static final Path APP_YML = Paths.get(
			System.getProperty("user.dir"), "src", "main", "resources", "application.yml");
	private static final Pattern PLACEHOLDER = Pattern.compile("\\$\\{([^}]*)}");

	private final OpenAiPassthroughAdapter adapter = new OpenAiPassthroughAdapter(new ObjectMapper());

	@SuppressWarnings("unchecked")
	private static Map<String, Map<String, Object>> providers() throws Exception {
		assertThat(Files.exists(APP_YML)).as("application.yml resolves").isTrue();
		Map<String, Object> root;
		try (Reader reader = Files.newBufferedReader(APP_YML)) {
			root = new Yaml().load(reader);
		}
		Map<String, Object> gateway = (Map<String, Object>) root.get("gateway");
		return (Map<String, Map<String, Object>>) gateway.get("providers");
	}

	private static String resolve(String raw) {
		if (raw == null) {
			return null;
		}
		Matcher matcher = PLACEHOLDER.matcher(raw);
		StringBuffer out = new StringBuffer();
		while (matcher.find()) {
			String inner = matcher.group(1);
			int colon = inner.indexOf(':');
			String name = colon < 0 ? inner : inner.substring(0, colon);
			String def = colon < 0 ? "" : inner.substring(colon + 1);
			String env = System.getenv(name);
			matcher.appendReplacement(out, Matcher.quoteReplacement(env != null ? env : def));
		}
		matcher.appendTail(out);
		return out.toString();
	}

	private String join(Map<String, Object> entry) {
		ProviderConfig config = new ProviderConfig(
				"p", ProviderType.OPENAI, URI.create(resolve((String) entry.get("base-url"))),
				new SensitiveString(""), Duration.ofSeconds(5), Duration.ofSeconds(60),
				false, (String) entry.get("chat-completions-path"));
		return adapter.buildUpstreamUrl(config).toString();
	}

	@Test
	@DisplayName("provider count is locked at 27")
	void providerCountLocked() throws Exception {
		assertThat(providers()).hasSize(27);
	}

	@Test
	@DisplayName("fixed providers join to their verified chat URLs")
	void fixedProvidersJoinToVerifiedUrls() throws Exception {
		Map<String, Map<String, Object>> all = providers();
		Map<String, String> expected = Map.ofEntries(
				Map.entry("openai", "https://api.openai.com/v1/chat/completions"),
				Map.entry("openrouter", "https://openrouter.ai/api/v1/chat/completions"),
				Map.entry("together", "https://api.together.ai/v1/chat/completions"),
				Map.entry("groq", "https://api.groq.com/openai/v1/chat/completions"),
				Map.entry("mistral", "https://api.mistral.ai/v1/chat/completions"),
				Map.entry("xai", "https://api.x.ai/v1/chat/completions"),
				Map.entry("deepinfra", "https://api.deepinfra.com/v1/openai/chat/completions"),
				Map.entry("fireworks", "https://api.fireworks.ai/inference/v1/chat/completions"),
				Map.entry("cerebras", "https://api.cerebras.ai/v1/chat/completions"),
				Map.entry("sambanova", "https://api.sambanova.ai/v1/chat/completions"),
				Map.entry("nebius", "https://api.studio.nebius.com/v1/chat/completions"),
				Map.entry("novita", "https://api.novita.ai/openai/v1/chat/completions"),
				Map.entry("moonshot", "https://api.moonshot.ai/v1/chat/completions"),
				Map.entry("zhipu", "https://open.bigmodel.cn/api/paas/v4/chat/completions"),
				Map.entry("minimax", "https://api.minimax.io/v1/chat/completions"),
				Map.entry("stepfun", "https://api.stepfun.com/v1/chat/completions"),
				Map.entry("hyperbolic", "https://api.hyperbolic.xyz/v1/chat/completions"),
				Map.entry("ionet", "https://api.intelligence.io.solutions/api/v1/chat/completions"),
				Map.entry("friendli", "https://api.friendli.ai/serverless/v1/chat/completions"));

		assertThat(all.keySet()).containsAll(expected.keySet());
		for (Map.Entry<String, String> want : expected.entrySet()) {
			Map<String, Object> entry = all.get(want.getKey());
			assertThat(entry.get("type")).as(want.getKey() + " stays OPENAI").isEqualTo("OPENAI");
			assertThat(join(entry)).as(want.getKey() + " joins correctly").isEqualTo(want.getValue());
		}
	}

	@Test
	@DisplayName("env-capable providers join without a doubled version prefix")
	void envCapableProvidersJoinWithoutDoubling() throws Exception {
		Map<String, Map<String, Object>> all = providers();

		for (String name : List.of("qwen", "bedrock", "cloudflare", "vllm", "llamacpp", "lmstudio")) {
			Map<String, Object> entry = all.get(name);
			String joined = join(entry);
			assertThat(joined).as(name + " has no doubled prefix").doesNotContain("/v1/v1/");
			assertThat(joined).as(name + " ends on the chat path").endsWith("/v1/chat/completions");
		}
		assertThat(join(all.get("qwen")))
				.as("qwen default is Beijing legacy").startsWith("https://dashscope.aliyuncs.com/compatible-mode");
		assertThat(join(all.get("bedrock")))
				.as("bedrock default is regional runtime").startsWith("https://bedrock-runtime.");
		assertThat(join(all.get("llamacpp")))
				.as("llamacpp avoids the gateway port").startsWith("http://localhost:8081");
	}

	@Test
	@DisplayName("non-OpenAI entries keep their bases and types")
	void nonOpenAiBasesPinned() throws Exception {
		Map<String, Map<String, Object>> all = providers();

		assertThat(all.get("anthropic").get("type")).isEqualTo("ANTHROPIC");
		assertThat(resolve((String) all.get("anthropic").get("base-url")))
				.isEqualTo("https://api.anthropic.com");
		assertThat(all.get("ollama").get("type")).isEqualTo("OLLAMA");
		assertThat(resolve((String) all.get("ollama").get("base-url")))
				.isEqualTo("http://localhost:11434");
	}

	@Test
	@DisplayName("no joined URL anywhere contains a doubled version prefix")
	void noDoubledVersionPrefixAnywhere() throws Exception {
		Map<String, Map<String, Object>> all = providers();

		for (Map.Entry<String, Map<String, Object>> provider : all.entrySet()) {
			String type = (String) provider.getValue().get("type");
			if (!"OPENAI".equals(type)) {
				continue;
			}
			assertThat(join(provider.getValue()))
					.as(provider.getKey() + " has no doubled prefix")
					.doesNotContain("/v1/v1/");
		}
	}
}
