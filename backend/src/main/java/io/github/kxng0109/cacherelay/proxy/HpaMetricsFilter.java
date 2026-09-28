package io.github.kxng0109.cacherelay.proxy;

import java.io.IOException;
import java.util.concurrent.TimeUnit;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.jspecify.annotations.Nullable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Autoscaling signals for the HPA (FS-B17): counts chat admissions and times
 * request latency on the GPU-bound chat path. CPU stays low while streams are
 * held open, so the HPA scales on {@code admission_rps} (rate of the counter)
 * and {@code p99_latency_seconds} (p99 of the timer) via the Prometheus
 * Adapter mapping in {@code backend/deploy/k8s/prometheus-adapter.yaml} —
 * never on CPU.
 *
 * <p>Once-per-request, path-scoped to {@code POST /v1/chat/completions};
 * every other path passes through untouched with zero metric overhead.</p>
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 10)
public class HpaMetricsFilter extends OncePerRequestFilter {

	static final String ADMISSION_COUNTER = "cacherelay.admission.total";

	static final String STREAM_TIMER = "cacherelay.stream.seconds";

	private volatile MeterRegistry meterRegistry = new SimpleMeterRegistry();

	private volatile Counter admissions;

	private volatile Timer streams;

	/**
	 * Creates the filter with a private registry (tests replace it).
	 */
	public HpaMetricsFilter() {
		this(new SimpleMeterRegistry());
	}

	HpaMetricsFilter(MeterRegistry registry) {
		this.meterRegistry = registry;
		this.admissions = Counter.builder(ADMISSION_COUNTER)
				.description("Chat completion admissions (HPA scales on its rate)")
				.register(registry);
		this.streams = Timer.builder(STREAM_TIMER)
				.description("Chat request latency (HPA scales on its p99)")
				.publishPercentileHistogram()
				.register(registry);
	}

	/**
	 * Wires the shared registry. Optional on purpose: without it the filter
	 * still measures into its private registry.
	 *
	 * @param meterRegistry shared registry, if available
	 */
	@Autowired
	public void setMeterRegistry(@Nullable MeterRegistry meterRegistry) {
		if (meterRegistry != null) {
			this.meterRegistry = meterRegistry;
			this.admissions = Counter.builder(ADMISSION_COUNTER)
					.description("Chat completion admissions (HPA scales on its rate)")
					.register(meterRegistry);
			this.streams = Timer.builder(STREAM_TIMER)
					.description("Chat request latency (HPA scales on its p99)")
					.publishPercentileHistogram()
					.register(meterRegistry);
		}
	}

	@Override
	protected boolean shouldNotFilter(HttpServletRequest request) {
		return !("POST".equalsIgnoreCase(request.getMethod())
				&& "/v1/chat/completions".equals(request.getRequestURI()));
	}

	@Override
	protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
	                                FilterChain chain) throws ServletException, IOException {
		admissions.increment();
		long startNanos = System.nanoTime();
		try {
			chain.doFilter(request, response);
		} finally {
			streams.record(System.nanoTime() - startNanos, TimeUnit.NANOSECONDS);
		}
	}
}
