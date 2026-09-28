package io.github.kxng0109.cacherelay.proxy;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import jakarta.servlet.FilterChain;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

/**
 * FS-B17 HPA signals: chat admissions counted, stream latency timed.
 */
@DisplayName("HpaMetricsFilter")
class HpaMetricsFilterTest {

	@Test
	@DisplayName("chat admissions increment the counter and record latency")
	void chatAdmissionCountedAndTimed() throws Exception {
		SimpleMeterRegistry registry = new SimpleMeterRegistry();
		HpaMetricsFilter filter = new HpaMetricsFilter(registry);
		MockHttpServletRequest request = new MockHttpServletRequest("POST", "/v1/chat/completions");
		MockHttpServletResponse response = new MockHttpServletResponse();
		FilterChain chain = mock(FilterChain.class);

		filter.doFilter(request, response, chain);

		assertThat(registry.get("cacherelay.admission.total").counter().count()).isEqualTo(1.0);
		assertThat(registry.get("cacherelay.stream.seconds").timer().count()).isEqualTo(1L);
	}

	@Test
	@DisplayName("non-chat paths are neither counted nor timed")
	void nonChatPathsIgnored() throws Exception {
		SimpleMeterRegistry registry = new SimpleMeterRegistry();
		HpaMetricsFilter filter = new HpaMetricsFilter(registry);
		MockHttpServletRequest request = new MockHttpServletRequest("GET", "/v1/models");
		FilterChain chain = mock(FilterChain.class);

		filter.doFilter(request, new MockHttpServletResponse(), chain);

		assertThat(registry.get("cacherelay.admission.total").counter().count()).isZero();
		assertThat(registry.get("cacherelay.stream.seconds").timer().count()).isZero();
	}
}
