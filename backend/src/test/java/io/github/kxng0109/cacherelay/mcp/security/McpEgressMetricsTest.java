package io.github.kxng0109.cacherelay.mcp.security;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("McpEgressMetrics")
class McpEgressMetricsTest {

	@Test
	@DisplayName("null registry falls back to an isolated in-memory registry")
	void nullRegistryIsolated() {
		McpEgressMetrics metrics = new McpEgressMetrics(null);

		metrics.blocked("tool");

		assertThat(metrics).isNotNull();
	}

	@Test
	@DisplayName("counters record detections, blocks, and unscanned content")
	void countersRecord() {
		SimpleMeterRegistry registry = new SimpleMeterRegistry();
		McpEgressMetrics metrics = new McpEgressMetrics(registry);

		metrics.injectionDetected("tool", "block");
		metrics.blocked("tool");
		metrics.unscanned("image");

		assertThat(registry.get("mcp_egress_injection_detected_total")
				.tag("tool", "tool").tag("mode", "block").counter().count()).isEqualTo(1.0);
		assertThat(registry.get("mcp_egress_blocked_total")
				.tag("tool", "tool").counter().count()).isEqualTo(1.0);
		assertThat(registry.get("mcp_egress_unscanned_total")
				.tag("type", "image").counter().count()).isEqualTo(1.0);
	}
}
