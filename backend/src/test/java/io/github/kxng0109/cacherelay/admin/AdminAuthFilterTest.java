package io.github.kxng0109.cacherelay.admin;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.oauth2.jwt.Jwt;
import tools.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.lang.reflect.Field;
import java.util.Optional;
import java.util.UUID;

import io.github.kxng0109.cacherelay.auth.AuthAuditService;
import io.github.kxng0109.cacherelay.auth.JwtService;
import io.github.kxng0109.cacherelay.auth.UserAccount;
import io.github.kxng0109.cacherelay.auth.UserAccountRepository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

@DisplayName("AdminAuthFilter")
@SuppressWarnings("DataFlowIssue")
class AdminAuthFilterTest {

	private final ObjectMapper objectMapper = new ObjectMapper();
	private final FilterChain filterChain = mock(FilterChain.class);

	@Test
	@DisplayName("fails closed with 403 Forbidden when master admin key is null or blank")
	void failsClosedWhenMasterKeyUnconfigured() throws ServletException, IOException {
		AdminAuthFilter filterNull = new AdminAuthFilter(null, objectMapper);
		AdminAuthFilter filterBlank = new AdminAuthFilter("   ", objectMapper);

		MockHttpServletRequest request = new MockHttpServletRequest("GET", "/v1/admin/keys");
		MockHttpServletResponse response1 = new MockHttpServletResponse();
		MockHttpServletResponse response2 = new MockHttpServletResponse();

		filterNull.doFilter(request, response1, filterChain);
		filterBlank.doFilter(request, response2, filterChain);

		assertThat(response1.getStatus()).isEqualTo(403);
		assertThat(response1.getContentType()).contains("application/problem+json");
		assertThat(response1.getContentAsString()).contains("Admin Interface Disabled");

		assertThat(response2.getStatus()).isEqualTo(403);
		assertThat(response2.getContentAsString()).contains("Admin Interface Disabled");

		verifyNoInteractions(filterChain);
	}

	@Test
	@DisplayName("hides with 404 Not Found when credentials are missing")
	void rejectsMissingCredentials() throws ServletException, IOException {
		AdminAuthFilter filter = new AdminAuthFilter("master-secret-12345", objectMapper);

		MockHttpServletRequest request = new MockHttpServletRequest("GET", "/v1/admin/keys");
		MockHttpServletResponse response = new MockHttpServletResponse();

		filter.doFilter(request, response, filterChain);

		assertThat(response.getStatus()).isEqualTo(404);
		assertThat(response.getContentType()).contains("application/problem+json");
		assertThat(response.getContentAsString()).contains("No such endpoint");
		verifyNoInteractions(filterChain);
	}

	@Test
	@DisplayName("hides with 404 Not Found when Bearer token is incorrect")
	void rejectsInvalidBearerToken() throws ServletException, IOException {
		AdminAuthFilter filter = new AdminAuthFilter("master-secret-12345", objectMapper);

		MockHttpServletRequest request = new MockHttpServletRequest("GET", "/v1/admin/keys");
		request.addHeader("Authorization", "Bearer wrong-secret");
		MockHttpServletResponse response = new MockHttpServletResponse();

		filter.doFilter(request, response, filterChain);

		assertThat(response.getStatus()).isEqualTo(404);
		assertThat(response.getContentAsString()).contains("No such endpoint");
		verifyNoInteractions(filterChain);
	}

	@Test
	@DisplayName("hides with 404 Not Found when X-Admin-Key is incorrect")
	void rejectsInvalidXAdminKey() throws ServletException, IOException {
		AdminAuthFilter filter = new AdminAuthFilter("master-secret-12345", objectMapper);

		MockHttpServletRequest request = new MockHttpServletRequest("GET", "/v1/admin/keys");
		request.addHeader("X-Admin-Key", "wrong-secret-header");
		MockHttpServletResponse response = new MockHttpServletResponse();

		filter.doFilter(request, response, filterChain);

		assertThat(response.getStatus()).isEqualTo(404);
		assertThat(response.getContentAsString()).contains("No such endpoint");
		verifyNoInteractions(filterChain);
	}

	@Test
	@DisplayName("allows request when Authorization Bearer token matches master key")
	void allowsValidBearerToken() throws ServletException, IOException {
		AdminAuthFilter filter = new AdminAuthFilter("master-secret-12345", objectMapper);

		MockHttpServletRequest request = new MockHttpServletRequest("GET", "/v1/admin/keys");
		request.addHeader("Authorization", "Bearer master-secret-12345");
		MockHttpServletResponse response = new MockHttpServletResponse();

		filter.doFilter(request, response, filterChain);

		assertThat(response.getStatus()).isEqualTo(200);
		verify(filterChain).doFilter(request, response);
	}

	@Test
	@DisplayName("allows request when X-Admin-Key matches master key")
	void allowsValidXAdminKey() throws ServletException, IOException {
		AdminAuthFilter filter = new AdminAuthFilter("master-secret-12345", objectMapper);

		MockHttpServletRequest request = new MockHttpServletRequest("GET", "/v1/admin/keys");
		request.addHeader("X-Admin-Key", "master-secret-12345");
		MockHttpServletResponse response = new MockHttpServletResponse();

		filter.doFilter(request, response, filterChain);

		assertThat(response.getStatus()).isEqualTo(200);
		verify(filterChain).doFilter(request, response);
	}

	@Test
	@DisplayName("hides when Authorization or X-Admin-Key is blank or non-bearer")
	void rejectsBlankOrNonBearerHeaders() throws ServletException, IOException {
		AdminAuthFilter filter = new AdminAuthFilter("master-secret-12345", objectMapper);

		// Blank Bearer
		MockHttpServletRequest req1 = new MockHttpServletRequest("GET", "/v1/admin/keys");
		req1.addHeader("Authorization", "Bearer   ");
		MockHttpServletResponse resp1 = new MockHttpServletResponse();
		filter.doFilter(req1, resp1, filterChain);
		assertThat(resp1.getStatus()).isEqualTo(404);

		// Non-Bearer Auth without X-Admin-Key
		MockHttpServletRequest req2 = new MockHttpServletRequest("GET", "/v1/admin/keys");
		req2.addHeader("Authorization", "Basic dXNlcjpwYXNz");
		MockHttpServletResponse resp2 = new MockHttpServletResponse();
		filter.doFilter(req2, resp2, filterChain);
		assertThat(resp2.getStatus()).isEqualTo(404);

		// Blank X-Admin-Key
		MockHttpServletRequest req3 = new MockHttpServletRequest("GET", "/v1/admin/keys");
		req3.addHeader("X-Admin-Key", "   ");
		MockHttpServletResponse resp3 = new MockHttpServletResponse();
		filter.doFilter(req3, resp3, filterChain);
		assertThat(resp3.getStatus()).isEqualTo(404);
	}

	@Test
	@DisplayName("accepts a live admin JWT and attributes the account")
	void acceptsValidAdminJwt() throws ServletException, IOException {
		JwtService jwtService = mock(JwtService.class);
		UserAccountRepository users = mock(UserAccountRepository.class);
		AdminAuthFilter filter =
				new AdminAuthFilter("master-secret-12345", objectMapper, jwtService, users, null);
		UUID adminId = UUID.randomUUID();
		Jwt decoded = mock(Jwt.class);
		when(decoded.getClaim("admin")).thenReturn(true);
		when(decoded.getSubject()).thenReturn(adminId.toString());
		when(jwtService.validate("jwt-token")).thenReturn(decoded);
		UserAccount account = new UserAccount("root", "hash", null, true);
		setId(account, adminId);
		when(users.findById(adminId)).thenReturn(Optional.of(account));

		MockHttpServletRequest request = new MockHttpServletRequest("GET", "/v1/admin/keys");
		request.addHeader("Authorization", "Bearer jwt-token");
		MockHttpServletResponse response = new MockHttpServletResponse();

		filter.doFilter(request, response, filterChain);

		assertThat(response.getStatus()).isEqualTo(200);
		assertThat(request.getAttribute(AdminAuthFilter.ATTRIBUTE_ADMIN_ID)).isEqualTo(adminId);
		verify(filterChain).doFilter(request, response);
	}

	@Test
	@DisplayName("hides non-admin JWTs")
	void rejectsNonAdminJwt() throws ServletException, IOException {
		JwtService jwtService = mock(JwtService.class);
		UserAccountRepository users = mock(UserAccountRepository.class);
		AdminAuthFilter filter =
				new AdminAuthFilter("master-secret-12345", objectMapper, jwtService, users, null);
		Jwt decoded = mock(Jwt.class);
		when(decoded.getClaim("admin")).thenReturn(false);
		when(jwtService.validate("user-jwt")).thenReturn(decoded);

		MockHttpServletRequest request = new MockHttpServletRequest("GET", "/v1/admin/keys");
		request.addHeader("Authorization", "Bearer user-jwt");
		MockHttpServletResponse response = new MockHttpServletResponse();

		filter.doFilter(request, response, filterChain);

		assertThat(response.getStatus()).isEqualTo(404);
		verifyNoInteractions(filterChain);
	}

	@Test
	@DisplayName("audits admin mutations but not reads")
	void auditsMutations() throws ServletException, IOException {
		AuthAuditService audit = mock(AuthAuditService.class);
		AdminAuthFilter filter = new AdminAuthFilter("master-secret-12345", objectMapper, null,
				null, audit);

		MockHttpServletRequest mutation = new MockHttpServletRequest("POST", "/v1/admin/keys");
		mutation.addHeader("X-Admin-Key", "master-secret-12345");
		filter.doFilter(mutation, new MockHttpServletResponse(), filterChain);

		MockHttpServletRequest read = new MockHttpServletRequest("GET", "/v1/admin/keys");
		read.addHeader("X-Admin-Key", "master-secret-12345");
		filter.doFilter(read, new MockHttpServletResponse(), filterChain);

		verify(audit).record(
				eq(AuthAuditService.ACTION_ADMIN_MUTATION),
				any(), any(), any(), any(), any(), any());
	}

	@Test
	@DisplayName("hides when user lookup is unavailable")
	void rejectsWithoutUserLookup() throws ServletException, IOException {
		JwtService jwtService = mock(JwtService.class);
		AdminAuthFilter filter = new AdminAuthFilter("master-secret-12345", objectMapper,
				jwtService, null, null);

		MockHttpServletRequest request = new MockHttpServletRequest("GET", "/v1/admin/keys");
		request.addHeader("Authorization", "Bearer jwt-token");
		MockHttpServletResponse response = new MockHttpServletResponse();

		filter.doFilter(request, response, filterChain);

		assertThat(response.getStatus()).isEqualTo(404);
		verifyNoInteractions(filterChain);
		verifyNoInteractions(jwtService);
	}

	@Test
	@DisplayName("hides missing or non-bearer credentials when sessions are enabled")
	void rejectsMissingOrNonBearerWithServices() throws ServletException, IOException {
		AdminAuthFilter filter = new AdminAuthFilter("master-secret-12345", objectMapper,
				mock(JwtService.class), mock(UserAccountRepository.class), null);

		MockHttpServletRequest bare = new MockHttpServletRequest("GET", "/v1/admin/keys");
		MockHttpServletResponse bareResponse = new MockHttpServletResponse();
		filter.doFilter(bare, bareResponse, filterChain);
		assertThat(bareResponse.getStatus()).isEqualTo(404);

		MockHttpServletRequest basic = new MockHttpServletRequest("GET", "/v1/admin/keys");
		basic.addHeader("Authorization", "Basic dXNlcjpwYXNz");
		MockHttpServletResponse basicResponse = new MockHttpServletResponse();
		filter.doFilter(basic, basicResponse, filterChain);
		assertThat(basicResponse.getStatus()).isEqualTo(404);
		verifyNoInteractions(filterChain);
	}

	@Test
	@DisplayName("hides admin JWTs for unknown, disabled, or non-admin accounts")
	void rejectsUnusableAdminJwt() throws ServletException, IOException {
		JwtService jwtService = mock(JwtService.class);
		UserAccountRepository users = mock(UserAccountRepository.class);
		AdminAuthFilter filter =
				new AdminAuthFilter("master-secret-12345", objectMapper, jwtService, users, null);
		UUID adminId = UUID.randomUUID();
		when(users.findById(adminId)).thenReturn(Optional.empty());

		MockHttpServletRequest unknown = new MockHttpServletRequest("GET", "/v1/admin/keys");
		unknown.addHeader("Authorization", "Bearer unknown-jwt");
		jwtFor(adminId, true, jwtService, "unknown-jwt");
		MockHttpServletResponse unknownResponse = new MockHttpServletResponse();
		filter.doFilter(unknown, unknownResponse, filterChain);
		assertThat(unknownResponse.getStatus()).isEqualTo(404);

		UserAccount disabled = new UserAccount("off", "hash", null, true);
		setId(disabled, adminId);
		setDisabled(disabled);
		when(users.findById(adminId)).thenReturn(Optional.of(disabled));
		MockHttpServletRequest off = new MockHttpServletRequest("GET", "/v1/admin/keys");
		off.addHeader("Authorization", "Bearer disabled-jwt");
		jwtFor(adminId, true, jwtService, "disabled-jwt");
		MockHttpServletResponse offResponse = new MockHttpServletResponse();
		filter.doFilter(off, offResponse, filterChain);
		assertThat(offResponse.getStatus()).isEqualTo(404);

		UserAccount plain = new UserAccount("user", "hash", null, false);
		setId(plain, adminId);
		when(users.findById(adminId)).thenReturn(Optional.of(plain));
		MockHttpServletRequest user = new MockHttpServletRequest("GET", "/v1/admin/keys");
		user.addHeader("Authorization", "Bearer user-jwt");
		jwtFor(adminId, true, jwtService, "user-jwt");
		MockHttpServletResponse userResponse = new MockHttpServletResponse();
		filter.doFilter(user, userResponse, filterChain);
		assertThat(userResponse.getStatus()).isEqualTo(404);
		verifyNoInteractions(filterChain);
	}

	@Test
	@DisplayName("hides JWTs that fail validation")
	void rejectsThrowingJwt() throws ServletException, IOException {
		JwtService jwtService = mock(JwtService.class);
		when(jwtService.validate(any())).thenThrow(new RuntimeException("bad token"));
		AdminAuthFilter filter = new AdminAuthFilter("master-secret-12345", objectMapper,
				jwtService, mock(UserAccountRepository.class), null);

		MockHttpServletRequest request = new MockHttpServletRequest("GET", "/v1/admin/keys");
		request.addHeader("Authorization", "Bearer broken");
		MockHttpServletResponse response = new MockHttpServletResponse();

		filter.doFilter(request, response, filterChain);

		assertThat(response.getStatus()).isEqualTo(404);
		verifyNoInteractions(filterChain);
	}

	@Test
	@DisplayName("reads skip the mutation audit")
	void readsSkipAudit() throws ServletException, IOException {
		AuthAuditService audit = mock(AuthAuditService.class);
		AdminAuthFilter filter = new AdminAuthFilter("master-secret-12345", objectMapper, null,
				null, audit);

		MockHttpServletRequest head = new MockHttpServletRequest("HEAD", "/v1/admin/keys");
		head.addHeader("X-Admin-Key", "master-secret-12345");
		filter.doFilter(head, new MockHttpServletResponse(), filterChain);

		MockHttpServletRequest options =
				new MockHttpServletRequest("OPTIONS", "/v1/admin/keys");
		options.addHeader("X-Admin-Key", "master-secret-12345");
		filter.doFilter(options, new MockHttpServletResponse(), filterChain);

		verifyNoInteractions(audit);
	}

	@Test
	@DisplayName("failed mutations audit error and failure")
	void failedMutationsAuditError() throws ServletException, IOException {
		AuthAuditService audit = mock(AuthAuditService.class);
		AdminAuthFilter filter = new AdminAuthFilter("master-secret-12345", objectMapper, null,
				null, audit);

		MockHttpServletRequest mutation = new MockHttpServletRequest("POST", "/v1/admin/keys");
		mutation.addHeader("X-Admin-Key", "master-secret-12345");
		MockHttpServletResponse response = new MockHttpServletResponse();
		response.setStatus(500);
		filter.doFilter(mutation, response, filterChain);

		verify(audit).record(
				eq(AuthAuditService.ACTION_ADMIN_MUTATION),
				eq(AuthAuditService.SEVERITY_ERROR),
				eq("master-key"),
				eq("/v1/admin/keys"),
				eq(AuthAuditService.OUTCOME_FAILURE),
				any(), any());
	}

	@Test
	@DisplayName("JWT mutations attribute the admin account")
	void jwtMutationsAttributeAdmin() throws ServletException, IOException {
		JwtService jwtService = mock(JwtService.class);
		UserAccountRepository users = mock(UserAccountRepository.class);
		AuthAuditService audit = mock(AuthAuditService.class);
		AdminAuthFilter filter = new AdminAuthFilter("master-secret-12345", objectMapper,
				jwtService, users, audit);
		UUID adminId = UUID.randomUUID();
		UserAccount account = new UserAccount("root", "hash", null, true);
		setId(account, adminId);
		when(users.findById(adminId)).thenReturn(Optional.of(account));
		jwtFor(adminId, true, jwtService, "admin-jwt");

		MockHttpServletRequest mutation = new MockHttpServletRequest("POST", "/v1/admin/keys");
		mutation.addHeader("Authorization", "Bearer admin-jwt");
		filter.doFilter(mutation, new MockHttpServletResponse(), filterChain);

		verify(audit).record(
				eq(AuthAuditService.ACTION_ADMIN_MUTATION),
				eq(AuthAuditService.SEVERITY_WARN),
				eq(adminId.toString()),
				eq("/v1/admin/keys"),
				eq(AuthAuditService.OUTCOME_SUCCESS),
				any(), any());
	}

	private void jwtFor(UUID id, boolean admin, JwtService jwtService, String token) {
		Jwt decoded = mock(Jwt.class);
		when(decoded.getClaim("admin")).thenReturn(admin);
		when(decoded.getSubject()).thenReturn(id.toString());
		when(jwtService.validate(token)).thenReturn(decoded);
	}

	private static void setDisabled(UserAccount account) {
		try {
			Field field = UserAccount.class.getDeclaredField("disabled");
			field.setAccessible(true);
			field.set(account, true);
		} catch (ReflectiveOperationException e) {
			throw new IllegalStateException("Test reflection failed", e);
		}
	}

	private static void setId(UserAccount account, UUID id) {
		try {
			Field field = UserAccount.class.getDeclaredField("id");
			field.setAccessible(true);
			field.set(account, id);
		} catch (ReflectiveOperationException e) {
			throw new IllegalStateException("Test reflection failed", e);
		}
	}
}
