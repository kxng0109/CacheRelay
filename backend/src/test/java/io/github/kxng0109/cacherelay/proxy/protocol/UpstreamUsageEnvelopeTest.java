package io.github.kxng0109.cacherelay.proxy.protocol;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import tools.jackson.databind.ObjectMapper;

/**
 * Feeds every documented upstream usage envelope through the real normalizer and pins what
 * billing accounting extracts. Providers disagree on envelope extras (nested detail splits,
 * cost estimates, sensitivity flags); only the flat token fields feed accounting.
 */
@DisplayName("UpstreamUsageEnvelope")
class UpstreamUsageEnvelopeTest {

	private final ObjectMapper objectMapper = new ObjectMapper();

	private record Feed(List<String> lines, SseNormalizer.UsageInfo usage) {
	}

	private Feed feed(String usageJson, String choicesJson) {
		OpenAiSseNormalizer normalizer = new OpenAiSseNormalizer(objectMapper, "m", false);
		String chunk = "data: {\"id\":\"c\",\"object\":\"chat.completion.chunk\",\"model\":\"m\","
				+ "\"choices\":" + choicesJson + ",\"usage\":" + usageJson + "}";
		List<String> lines = new ArrayList<>(normalizer.normalizeLine(chunk));
		lines.addAll(normalizer.normalizeLine("data: [DONE]"));
		return new Feed(lines, normalizer.usage());
	}

	private static Stream<Arguments> envelopes() {
		return Stream.of(
				Arguments.of("openai standard",
						"{\"prompt_tokens\":125,\"completion_tokens\":48,\"total_tokens\":173}", 125L, 48L),
				Arguments.of("together flat",
						"{\"prompt_tokens\":10,\"completion_tokens\":20,\"total_tokens\":30}", 10L, 20L),
				Arguments.of("cerebras nested details and timings",
						"{\"prompt_tokens\":7,\"completion_tokens\":9,\"total_tokens\":16,"
								+ "\"prompt_tokens_details\":{\"cached_tokens\":7},"
								+ "\"completion_tokens_details\":{\"reasoning_tokens\":0},"
								+ "\"time_info\":{\"queue_time\":0.1}}", 7L, 9L),
				Arguments.of("moonshot hybrid flat plus nested",
						"{\"prompt_tokens\":19,\"completion_tokens\":21,\"total_tokens\":40,\"cached_tokens\":10,"
								+ "\"prompt_tokens_details\":{\"cached_tokens\":10,\"cache_write_tokens\":0}}",
						19L, 21L),
				Arguments.of("deepinfra cost estimate",
						"{\"prompt_tokens\":5,\"completion_tokens\":6,\"estimated_cost\":0.0001}", 5L, 6L),
				Arguments.of("minimax sensitivity flags",
						"{\"prompt_tokens\":100,\"completion_tokens\":50,\"total_tokens\":150,"
								+ "\"total_characters\":200,\"prompt_tokens_details\":{\"cached_tokens\":114}}",
						100L, 50L),
				Arguments.of("zhipu flat",
						"{\"prompt_tokens\":12,\"completion_tokens\":3,\"total_tokens\":15}", 12L, 3L),
				Arguments.of("stepfun flat cached split",
						"{\"prompt_tokens\":30,\"completion_tokens\":11,\"total_tokens\":41,\"cached_tokens\":5}",
						30L, 11L),
				Arguments.of("stepfun nested splits",
						"{\"prompt_tokens\":30,\"completion_tokens\":11,\"total_tokens\":41,"
								+ "\"prompt_tokens_details\":{\"cached_tokens\":5},"
								+ "\"completion_tokens_details\":{\"reasoning_tokens\":2}}", 30L, 11L),
				Arguments.of("groq timing fields",
						"{\"prompt_tokens\":8,\"completion_tokens\":4,\"total_tokens\":12,"
								+ "\"prompt_time\":0.01,\"completion_time\":0.02}", 8L, 4L),
				Arguments.of("xai token splits",
						"{\"prompt_tokens\":125,\"completion_tokens\":48,\"total_tokens\":173,"
								+ "\"prompt_tokens_details\":{\"text_tokens\":125,\"cached_tokens\":98},"
								+ "\"completion_tokens_details\":{\"reasoning_tokens\":0}}", 125L, 48L),
				Arguments.of("fireworks final chunk",
						"{\"prompt_tokens\":15,\"completion_tokens\":25,\"total_tokens\":40}", 15L, 25L),
				Arguments.of("missing token fields read as zero",
						"{\"total_tokens\":10}", 0L, 0L));
	}

	@ParameterizedTest(name = "{0}")
	@MethodSource("envelopes")
	@DisplayName("documented usage envelopes feed accounting with flat token fields")
	void documentedEnvelopes(String label, String usageJson, long prompt, long completion) {
		Feed feed = feed(usageJson, "[]");

		assertThat(feed.lines()).as(label + " chunk is consumed for accounting")
				.containsExactly("data: [DONE]");
		assertThat(feed.usage()).as(label + " is captured").isNotNull();
		assertThat(feed.usage().promptTokens()).as(label + " prompt").isEqualTo(prompt);
		assertThat(feed.usage().completionTokens()).as(label + " completion").isEqualTo(completion);
	}

	@Test
	@DisplayName("openrouter final chunk with non-empty choices is not captured (known limitation)")
	void openRouterFinalChunkKnownLimitation() {
		String usage = "{\"prompt_tokens\":9,\"completion_tokens\":9,\"total_tokens\":18,\"cost\":0.0}";
		String choices = "[{\"index\":0,\"delta\":{\"role\":\"assistant\",\"content\":\"hi\"},\"finish_reason\":\"stop\"}]";
		Feed feed = feed(usage, choices);

		assertThat(feed.lines()).as("non-usage chunk is relayed verbatim").hasSize(2);
		assertThat(feed.usage())
				.as("usage beside non-empty choices is ignored; queued follow-up with cost-router work")
				.isNull();
	}

	@Test
	@DisplayName("stepfun per-chunk usage beside deltas is not captured (known limitation)")
	void stepfunPerChunkKnownLimitation() {
		String usage = "{\"prompt_tokens\":30,\"completion_tokens\":4,\"total_tokens\":34}";
		String choices = "[{\"index\":0,\"delta\":{\"content\":\"hi\"}}]";
		Feed feed = feed(usage, choices);

		assertThat(feed.lines()).as("non-usage chunk is relayed verbatim").hasSize(2);
		assertThat(feed.usage())
				.as("incremental usage beside deltas is ignored; queued follow-up with cost-router work")
				.isNull();
	}

	@Test
	@DisplayName("non-object usage is ignored without failing")
	void nonObjectUsageIgnored() {
		assertThat(feed("\"oops\"", "[]").usage()).isNull();
		assertThat(feed("null", "[]").usage()).isNull();
	}

	@Test
	@DisplayName("unparseable data line passes through without usage")
	void malformedLinePassesThrough() {
		OpenAiSseNormalizer normalizer = new OpenAiSseNormalizer(objectMapper, "m", false);

		List<String> lines = normalizer.normalizeLine("data: not-json{{{");

		assertThat(lines).containsExactly("data: not-json{{{");
		assertThat(normalizer.usage()).isNull();
	}
}
