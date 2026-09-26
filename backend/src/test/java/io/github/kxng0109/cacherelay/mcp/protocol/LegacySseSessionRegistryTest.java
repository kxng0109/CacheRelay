package io.github.kxng0109.cacherelay.mcp.protocol;

import java.time.Duration;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.web.servlet.mvc.method.annotation.ResponseBodyEmitter;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

/**
 * Unit tests for {@link LegacySseSessionRegistry}: per-key caps, key binding, and TTL eviction.
 */
@DisplayName("LegacySseSessionRegistry")
class LegacySseSessionRegistryTest {

	@Test
	@DisplayName("sessions resolve for the owning key only")
	void sessionsAreKeyBound() {
		LegacySseSessionRegistry registry = new LegacySseSessionRegistry(Duration.ofMinutes(30));
		String id = registry.create("key-1", mock(ResponseBodyEmitter.class)).orElseThrow().id();

		assertThat(registry.find(id, "key-1")).isPresent();
		assertThat(registry.find(id, "key-2")).as("foreign key").isEmpty();
		assertThat(registry.find("no-such-id", "key-1")).as("unknown id").isEmpty();
		assertThat(registry.find(null, "key-1")).as("null id").isEmpty();
		assertThat(registry.find(id, null)).as("null key").isEmpty();
	}

	@Test
	@DisplayName("per-key cap refuses overflow and other keys are unaffected")
	void perKeyCapEnforced() {
		LegacySseSessionRegistry registry = new LegacySseSessionRegistry(Duration.ofMinutes(30));
		for (int i = 0; i < LegacySseSessionRegistry.MAX_SESSIONS_PER_KEY; i++) {
			assertThat(registry.create("key-1", mock(ResponseBodyEmitter.class))).isPresent();
		}

		assertThat(registry.create("key-1", mock(ResponseBodyEmitter.class))).as("overflow").isEmpty();
		assertThat(registry.create("key-2", mock(ResponseBodyEmitter.class))).as("other key").isPresent();
		assertThat(registry.countForKey("key-1")).isEqualTo(LegacySseSessionRegistry.MAX_SESSIONS_PER_KEY);
	}

	@Test
	@DisplayName("expired sessions miss, evict, and free cap room")
	void expiredSessionsEvict() throws Exception {
		LegacySseSessionRegistry registry = new LegacySseSessionRegistry(Duration.ofMillis(50));
		String id = registry.create("key-1", mock(ResponseBodyEmitter.class)).orElseThrow().id();

		Thread.sleep(120L);

		assertThat(registry.find(id, "key-1")).as("expired session").isEmpty();
		assertThat(registry.countForKey("key-1")).isZero();
		assertThat(registry.create("key-1", mock(ResponseBodyEmitter.class))).as("room freed").isPresent();
	}

	@Test
	@DisplayName("remove drops sessions and tolerates unknown ids")
	void removeDropsSession() {
		LegacySseSessionRegistry registry = new LegacySseSessionRegistry(Duration.ofMinutes(30));
		String id = registry.create("key-1", mock(ResponseBodyEmitter.class)).orElseThrow().id();

		registry.remove(id);

		assertThat(registry.find(id, "key-1")).isEmpty();
		registry.remove("no-such-id");
		registry.remove(null);
	}
}
