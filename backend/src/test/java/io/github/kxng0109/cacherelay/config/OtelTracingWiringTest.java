package io.github.kxng0109.cacherelay.config;

import io.micrometer.tracing.Span;
import io.micrometer.tracing.Tracer;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.micrometer.tracing.opentelemetry.autoconfigure.OpenTelemetryTracingAutoConfiguration;
import org.springframework.boot.micrometer.tracing.opentelemetry.autoconfigure.otlp.OtlpTracingAutoConfiguration;
import org.springframework.boot.opentelemetry.autoconfigure.OpenTelemetrySdkAutoConfiguration;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * OpenTelemetry tracing wiring: the OTel bridge and OTLP export auto-configurations
 * supply a functional tracer, so every ingress request gets a server span once
 * a collector is pointed at {@code management.otlp.tracing.endpoint}. Export
 * stays fail-open (no collector means dropped spans, never failed requests).
 */
@DisplayName("OpenTelemetry tracing wiring")
class OtelTracingWiringTest {

	private final ApplicationContextRunner runner = new ApplicationContextRunner()
			.withConfiguration(AutoConfigurations.of(
					OpenTelemetrySdkAutoConfiguration.class,
					OpenTelemetryTracingAutoConfiguration.class,
					OtlpTracingAutoConfiguration.class));

	@Test
	@DisplayName("a working tracer is wired without any collector running")
	void tracerIsWired() {
		runner.run(context -> {
			assertThat(context).hasSingleBean(Tracer.class);
			Tracer tracer = context.getBean(Tracer.class);
			Span span = tracer.nextSpan().name("otel-probe").start();
			try {
				assertThat(span.context().traceId()).isNotBlank();
			} finally {
				span.end();
			}
		});
	}
}
