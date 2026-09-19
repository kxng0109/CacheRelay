package io.github.kxng0109.cacherelay.mcp.security;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Component;

/**
 * Micrometer counters for MCP egress injection screening (FS-05): operators can
 * distinguish blocked vs warned deliveries and see unscanned (non-text) content without
 * reading logs. Blocking happens after the upstream call completes, so the blocked
 * counter also attributes wasted upstream spend.
 *
 * @since 1.7.0
 */
@Component
public class McpEgressMetrics {

	private final MeterRegistry meterRegistry;

	/**
	 * Creates the metrics with an explicit registry (tests use {@link SimpleMeterRegistry}).
	 *
	 * @param meterRegistry registry, or {@code null} for an isolated in-memory one
	 */
	public McpEgressMetrics(@Nullable MeterRegistry meterRegistry) {
		this.meterRegistry = meterRegistry != null ? meterRegistry : new SimpleMeterRegistry();
	}

	/**
	 * Records injection markers found in a tool's output.
	 *
	 * @param tool namespaced tool name
	 * @param mode {@code block} when the key blocks delivery, {@code warn} otherwise
	 */
	public void injectionDetected(String tool, String mode) {
		meterRegistry.counter("mcp_egress_injection_detected_total", "tool", tool, "mode", mode)
		             .increment();
	}

	/**
	 * Records a delivery blocked (content withheld) by the egress policy.
	 *
	 * @param tool namespaced tool name
	 */
	public void blocked(String tool) {
		meterRegistry.counter("mcp_egress_blocked_total", "tool", tool).increment();
	}

	/**
	 * Records a content item that bypassed injection screening because it carries no
	 * scannable text (binary/image/audio blocks are documented as unscreened).
	 *
	 * @param contentType MCP content block type, or {@code unknown}
	 */
	public void unscanned(String contentType) {
		meterRegistry.counter("mcp_egress_unscanned_total", "type", contentType).increment();
	}
}
