package io.github.kxng0109.cacherelay.auth;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.access.AccessDeniedException;
import tools.jackson.databind.ObjectMapper;

import jakarta.servlet.http.HttpServletRequest;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Unit tests for stealth denials: admin paths vanish, others stay forbidden.
 */
@DisplayName("StealthAccessDeniedHandler")
class StealthAccessDeniedHandlerTest {

	private final StealthAccessDeniedHandler handler =
			new StealthAccessDeniedHandler(new ObjectMapper());

	@Test
	@DisplayName("admin-path denials answer 404 like a missing route")
	void adminPathStealth() throws Exception {
		MockHttpServletRequest request = new MockHttpServletRequest("GET", "/v1/admin/keys");
		MockHttpServletResponse response = new MockHttpServletResponse();

		handler.handle(request, response, new AccessDeniedException("denied"));

		assertThat(response.getStatus()).isEqualTo(404);
		assertThat(response.getContentAsString()).contains("No such endpoint");
	}

	@Test
	@DisplayName("non-admin denials stay 403")
	void otherPathForbidden() throws Exception {
		MockHttpServletRequest request = new MockHttpServletRequest("GET", "/v1/other");
		MockHttpServletResponse response = new MockHttpServletResponse();

		handler.handle(request, response, new AccessDeniedException("denied"));

		assertThat(response.getStatus()).isEqualTo(403);
	}

	@Test
	@DisplayName("missing paths fail closed with 403")
	void nullPathForbidden() throws Exception {
		HttpServletRequest request = mock(HttpServletRequest.class);
		when(request.getRequestURI()).thenReturn(null);
		MockHttpServletResponse response = new MockHttpServletResponse();

		handler.handle(request, response, new AccessDeniedException("denied"));

		assertThat(response.getStatus()).isEqualTo(403);
	}
}
