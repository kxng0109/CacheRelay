package io.github.kxng0109.cacherelay.auth;

import jakarta.servlet.FilterChain;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

/**
 * Unit tests for the per-response CSP nonce writer.
 */
@DisplayName("CspNonceFilter")
class CspNonceFilterTest {

	@Test
	@DisplayName("each response carries a fresh nonce in header and attribute")
	void freshNoncePerResponse() throws Exception {
		CspNonceFilter filter = new CspNonceFilter();
		FilterChain chain = mock(FilterChain.class);

		MockHttpServletRequest first = new MockHttpServletRequest("GET", "/");
		MockHttpServletResponse firstResponse = new MockHttpServletResponse();
		filter.doFilter(first, firstResponse, chain);

		MockHttpServletRequest second = new MockHttpServletRequest("GET", "/");
		MockHttpServletResponse secondResponse = new MockHttpServletResponse();
		filter.doFilter(second, secondResponse, chain);

		String firstHeader = firstResponse.getHeader("Content-Security-Policy");
		String secondHeader = secondResponse.getHeader("Content-Security-Policy");
		assertThat(firstHeader).contains("nonce-").contains("'strict-dynamic'")
				.contains("object-src 'none'");
		assertThat(first.getAttribute(CspNonceFilter.NONCE_ATTRIBUTE)).isNotNull();
		assertThat(firstHeader)
				.contains((String) first.getAttribute(CspNonceFilter.NONCE_ATTRIBUTE));
		assertThat(secondHeader).as("nonces differ per response").isNotEqualTo(firstHeader);
	}
}
