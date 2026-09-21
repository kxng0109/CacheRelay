package io.github.kxng0109.cacherelay.proxy.sse;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("DefaultSseLineGuardFactory meter sharing (PERF-06)")
class DefaultSseLineGuardFactoryTest {

	private final DefaultSseLineGuardFactory factory = new DefaultSseLineGuardFactory(
			SseLineGuardProperties.DEFAULTS, new SimpleMeterRegistry(), new ObjectMapper());

	@Test
	@DisplayName("same provider and action share one meter set")
	void sameKeySharesMeters() {
		SseLineMeters first =
				factory.metersFor("openai", SseLineGuard.Action.REJECT_LINE_AND_CLOSE);
		SseLineMeters second =
				factory.metersFor("openai", SseLineGuard.Action.REJECT_LINE_AND_CLOSE);

		assertThat(second).isSameAs(first);
	}

	@Test
	@DisplayName("null action builds a private default meter set")
	void nullActionBuildsDefault() {
		SseLineMeters meters = SseLineMeters.create(
				new SimpleMeterRegistry(), "openai", null);

		assertThat(meters.lineRejectedTooLong().getId().getTag("action"))
				.isEqualTo(SseLineGuard.Action.REJECT_LINE_AND_CLOSE.name());
	}

	@Test
	@DisplayName("different providers or actions get distinct meter sets")
	void distinctKeysDistinctMeters() {
		SseLineMeters base =
				factory.metersFor("openai", SseLineGuard.Action.REJECT_LINE_AND_CLOSE);

		assertThat(factory.metersFor("other", SseLineGuard.Action.REJECT_LINE_AND_CLOSE))
				.isNotSameAs(base);
		assertThat(factory.metersFor("openai", SseLineGuard.Action.REJECT_LINE_CONTINUE))
				.isNotSameAs(base);
	}
}
