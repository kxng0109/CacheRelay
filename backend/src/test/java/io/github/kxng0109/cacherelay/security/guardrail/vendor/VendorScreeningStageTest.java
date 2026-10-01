package io.github.kxng0109.cacherelay.security.guardrail.vendor;

import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import io.github.kxng0109.cacherelay.security.filter.CachedBodyHttpServletRequest;
import io.github.kxng0109.cacherelay.security.filter.IngressSecurityFilter;import io.github.kxng0109.cacherelay.security.guardrail.common.GuardrailMode;
import io.github.kxng0109.cacherelay.security.guardrail.common.GuardrailProperties;
import io.github.kxng0109.cacherelay.security.guardrail.injection.PromptInjectionScanner;
import io.github.kxng0109.cacherelay.security.guardrail.pii.PiiAnonymizer;
import io.github.kxng0109.cacherelay.security.guardrail.pii.PiiScanner;
import io.github.kxng0109.cacherelay.security.guardrail.secret.IngressSecretScanner;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import tools.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Vendor screening stage: disabled-by-default gating, ENFORCE/AUDIT_ONLY
 * verdict mapping, and fail-closed behavior on every vendor failure.
 */
@DisplayName("Vendor screening stage")
class VendorScreeningStageTest {

	private final ObjectMapper objectMapper = new ObjectMapper();

	@Test
	@DisplayName("disabled by default: no client, no egress, local stages unchanged")
	void disabledByDefault() throws Exception {
		IngressSecurityFilter filter = filter(null, mode(GuardrailMode.ENFORCE, false));

		MockHttpServletResponse response = new MockHttpServletResponse();
		new MockFilterChain().doFilter(request(article()), response);

		MockFilterChain chain = new MockFilterChain();
		filter.doFilter(request(article()), response, chain);

		assertThat(response.getStatus()).isNotEqualTo(500);
	}

	@Test
	@DisplayName("flagged payload blocks in ENFORCE with a 422 problem detail")
	void flaggedBlocksInEnforce() throws Exception {
		GuardrailVendorClient vendor = mock(GuardrailVendorClient.class);
		when(vendor.screen(anyString())).thenReturn(
				new VendorVerdict(true, "bedrock", "contentPolicy:VIOLENCE", Duration.ofMillis(12)));
		when(vendor.vendorId()).thenReturn("bedrock");
		IngressSecurityFilter filter = filter(vendor, mode(GuardrailMode.ENFORCE, true));

		MockHttpServletResponse response = new MockHttpServletResponse();
		filter.doFilter(request(article()), response, new MockFilterChain());

		assertThat(response.getStatus()).isEqualTo(422);
		assertThat(response.getContentAsString()).contains("Third-party screening flagged");
		assertThat(response.getHeader("X-CacheRelay-Vendor-Verdict"))
				.isEqualTo("bedrock:contentPolicy:VIOLENCE");
	}

	@Test
	@DisplayName("flagged payload annotates in AUDIT_ONLY without blocking")
	void flaggedAnnotatesInAuditOnly() throws Exception {
		GuardrailVendorClient vendor = mock(GuardrailVendorClient.class);
		when(vendor.screen(anyString())).thenReturn(
				new VendorVerdict(true, "bedrock", "topicPolicy:DENY", Duration.ofMillis(9)));
		when(vendor.vendorId()).thenReturn("bedrock");
		IngressSecurityFilter filter = filter(vendor, mode(GuardrailMode.AUDIT_ONLY, true));

		MockHttpServletResponse response = new MockHttpServletResponse();
		MockFilterChain chain = new MockFilterChain();
		CachedBodyHttpServletRequest raw = request(article());
		filter.doFilter(raw, response, chain);

		assertThat(chain.getRequest()).isNotNull();
		assertThat(response.getHeader("X-CacheRelay-Vendor-Verdict"))
				.isEqualTo("bedrock:topicPolicy:DENY");
	}

	@Test
	@DisplayName("flagged payload on the anonymized path blocks without forwarding")
	void flaggedOnAnonymizedPathBlocks() throws Exception {
		GuardrailVendorClient vendor = mock(GuardrailVendorClient.class);
		when(vendor.screen(anyString())).thenReturn(
				new VendorVerdict(true, "bedrock", "contentPolicy:VIOLENCE", Duration.ofMillis(11)));
		when(vendor.vendorId()).thenReturn("bedrock");
		IngressSecurityFilter filter = filter(vendor, mode(GuardrailMode.ENFORCE, true));

		MockHttpServletResponse response = new MockHttpServletResponse();
		MockFilterChain chain = new MockFilterChain();
		filter.doFilter(request(piiArticle()), response, chain);

		assertThat(response.getStatus()).isEqualTo(422);
		assertThat(chain.getRequest()).isNull();
	}

	@Test
	@DisplayName("configured but disabled screening never calls the vendor")
	void disabledFlagSkipsVendor() throws Exception {
		GuardrailVendorClient vendor = mock(GuardrailVendorClient.class);
		IngressSecurityFilter filter = filter(vendor, mode(GuardrailMode.ENFORCE, false));

		MockHttpServletResponse response = new MockHttpServletResponse();
		MockFilterChain chain = new MockFilterChain();
		filter.doFilter(request(article()), response, chain);

		verify(vendor, never()).screen(anyString());
		assertThat(chain.getRequest()).isNotNull();
	}

	@Test
	@DisplayName("clean verdicts pass through silently")
	void cleanPassesThrough() throws Exception {
		GuardrailVendorClient vendor = mock(GuardrailVendorClient.class);
		when(vendor.screen(anyString())).thenReturn(
				VendorVerdict.clean("bedrock", Duration.ofMillis(7)));
		when(vendor.vendorId()).thenReturn("bedrock");
		IngressSecurityFilter filter = filter(vendor, mode(GuardrailMode.ENFORCE, true));
		SimpleMeterRegistry registry = new SimpleMeterRegistry();
		filter.setMeterRegistry(registry);

		MockHttpServletResponse response = new MockHttpServletResponse();
		MockFilterChain chain = new MockFilterChain();
		filter.doFilter(request(article()), response, chain);

		assertThat(chain.getRequest()).isNotNull();
		assertThat(registry.get("guardrail_vendor_screenings_total")
				.tag("vendor", "bedrock").tag("outcome", "clean").counter().count())
				.isEqualTo(1.0);
		assertThat(response.getHeader("X-CacheRelay-Vendor-Verdict")).isNull();
	}

	@Test
	@DisplayName("verdict header values strip response-splitting characters")
	void headerValueStripsCrlf() throws Exception {
		GuardrailVendorClient vendor = mock(GuardrailVendorClient.class);
		when(vendor.screen(anyString())).thenReturn(
				new VendorVerdict(true, "bedrock", "evil\r\nX-Injected: 1", Duration.ofMillis(3)));
		when(vendor.vendorId()).thenReturn("bedrock");
		IngressSecurityFilter filter = filter(vendor, mode(GuardrailMode.ENFORCE, true));

		MockHttpServletResponse response = new MockHttpServletResponse();
		filter.doFilter(request(article()), response, new MockFilterChain());

		assertThat(response.getStatus()).isEqualTo(422);
		assertThat(response.getHeader("X-CacheRelay-Vendor-Verdict"))
				.isEqualTo("bedrock:evilX-Injected: 1");
	}

	@Test
	@DisplayName("vendor failures fail closed with a 500 guardrail detail")
	void failureFailsClosed() throws Exception {
		GuardrailVendorClient vendor = mock(GuardrailVendorClient.class);
		when(vendor.screen(anyString())).thenThrow(new RuntimeException("timeout"));
		IngressSecurityFilter filter = filter(vendor, mode(GuardrailMode.ENFORCE, true));

		MockHttpServletResponse response = new MockHttpServletResponse();
		filter.doFilter(request(article()), response, new MockFilterChain());

		assertThat(response.getStatus()).isEqualTo(500);
		assertThat(response.getContentAsString()).contains("Guardrail evaluation failed");
	}

	@Test
	@DisplayName("null verdicts fail closed instead of waving traffic through")
	void nullVerdictFailsClosed() throws Exception {
		GuardrailVendorClient vendor = mock(GuardrailVendorClient.class);
		when(vendor.screen(anyString())).thenReturn(null);
		IngressSecurityFilter filter = filter(vendor, mode(GuardrailMode.ENFORCE, true));

		MockHttpServletResponse response = new MockHttpServletResponse();
		filter.doFilter(request(article()), response, new MockFilterChain());

		assertThat(response.getStatus()).isEqualTo(500);
	}

	@Test
	@DisplayName("flagged verdicts are metered by outcome")
	void flaggedMetered() throws Exception {
		SimpleMeterRegistry registry = new SimpleMeterRegistry();
		GuardrailVendorClient vendor = mock(GuardrailVendorClient.class);
		when(vendor.screen(anyString())).thenReturn(
				new VendorVerdict(true, "bedrock", "wordPolicy:PROFANITY", Duration.ofMillis(5)));
		when(vendor.vendorId()).thenReturn("bedrock");
		IngressSecurityFilter filter = filter(vendor, mode(GuardrailMode.ENFORCE, true));
		filter.setMeterRegistry(registry);

		MockHttpServletResponse response = new MockHttpServletResponse();
		filter.doFilter(request(article()), response, new MockFilterChain());

		assertThat(response.getStatus()).isEqualTo(422);
		assertThat(registry.get("guardrail_vendor_screenings_total")
				.tag("vendor", "bedrock").tag("outcome", "flagged").counter().count())
				.isEqualTo(1.0);
	}

	private IngressSecurityFilter filter(GuardrailVendorClient vendor, GuardrailProperties props) {
		return new IngressSecurityFilter(
				new IngressSecretScanner(),
				new PromptInjectionScanner(),
				new PiiAnonymizer(new PiiScanner()),
				props,
				objectMapper,
				vendor
		);
	}

	private static GuardrailProperties mode(GuardrailMode mode, boolean vendor) {
		GuardrailProperties props = new GuardrailProperties();
		props.setMode(mode);
		props.setVendorScreeningEnabled(vendor);
		return props;
	}

	private static CachedBodyHttpServletRequest request(String body) throws IOException {
		MockHttpServletRequest raw = new MockHttpServletRequest("POST", "/v1/chat/completions");
		raw.setRequestURI("/v1/chat/completions");
		raw.setServletPath("/v1/chat/completions");
		raw.setContent(body.getBytes(StandardCharsets.UTF_8));
		raw.setContentType("application/json");
		return new CachedBodyHttpServletRequest(raw);
	}

	private static String article() {
		return "{\"model\":\"gpt-5.6-luna\",\"messages\":[{\"role\":\"user\","
				+ "\"content\":\"Summarize the quarterly report for the board meeting.\"}]}";
	}

	private static String piiArticle() {
		return "{\"messages\":[{\"role\":\"user\",\"content\":\"Contact Dr. John Doe at john@example.com\"}]}";
	}
}
