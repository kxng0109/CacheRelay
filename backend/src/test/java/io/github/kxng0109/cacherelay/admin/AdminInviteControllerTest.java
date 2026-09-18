package io.github.kxng0109.cacherelay.admin;

import java.util.UUID;

import io.github.kxng0109.cacherelay.auth.InviteService;
import io.github.kxng0109.cacherelay.auth.dto.InviteRequest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.mock.web.MockHttpServletRequest;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit tests for invite creation: attributions and link bases without a live server.
 */
@DisplayName("AdminInviteController")
class AdminInviteControllerTest {

	private InviteService invites;
	private AdminInviteController controller;

	@BeforeEach
	void setUp() {
		invites = mock(InviteService.class);
		controller = new AdminInviteController(invites);
		when(invites.create(any(), any(), anyBoolean(), any(), any())).thenReturn(
				new InviteService.CreatedInvite("http://h/redeem?token=t", false));
	}

	@Test
	@DisplayName("admin sessions attribute the invite; default ports stay bare")
	void jwtAttributedBarePort() {
		UUID adminId = UUID.randomUUID();
		MockHttpServletRequest request = post("http", "example.com", 80);
		request.setAttribute(AdminAuthFilter.ATTRIBUTE_ADMIN_ID, adminId);

		var response = controller.create(new InviteRequest(null, true), request);

		assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
		verify(invites).create(eq(adminId), isNull(), eq(true), eq("http://example.com"),
				isNull());
	}

	@Test
	@DisplayName("master-key invites attribute null with an https default port")
	void masterKeyHttpsDefault() {
		MockHttpServletRequest request = post("https", "example.com", 443);

		controller.create(new InviteRequest("op@example.com", false), request);

		verify(invites).create(isNull(), eq("op@example.com"), eq(false),
				eq("https://example.com"), isNull());
	}

	@Test
	@DisplayName("custom ports are kept in the link base")
	void customPortKept() {
		MockHttpServletRequest request = post("http", "example.com", 8080);

		controller.create(new InviteRequest(null, false), request);

		verify(invites).create(isNull(), isNull(), eq(false),
				eq("http://example.com:8080"), isNull());
	}

	private MockHttpServletRequest post(String scheme, String host, int port) {
		MockHttpServletRequest request = new MockHttpServletRequest("POST", "/v1/admin/invites");
		request.setScheme(scheme);
		request.setServerName(host);
		request.setServerPort(port);
		return request;
	}
}
