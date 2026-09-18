package io.github.kxng0109.cacherelay.admin;

import io.github.kxng0109.cacherelay.admin.dto.ProblemDetailResponse;
import io.github.kxng0109.cacherelay.auth.AuthAuditService;
import io.github.kxng0109.cacherelay.auth.JwtService;
import io.github.kxng0109.cacherelay.auth.UserAccount;
import io.github.kxng0109.cacherelay.auth.UserAccountRepository;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.filter.OncePerRequestFilter;
import tools.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Optional;
import java.util.UUID;

/**
 * Enforces authentication on administrative endpoints under {@code /v1/admin/**}.
 *
 * <p>Requests must provide the configured master key via {@code Authorization: Bearer
 * <master-key>} or {@code X-Admin-Key}, or a live admin access JWT as {@code Authorization:
 * Bearer <jwt>}. If the master key is not configured, the filter fails closed and returns
 * HTTP 403 Forbidden with an RFC 9457 Problem Details payload.</p>
 *
 * <p>Every other rejection answers 404, indistinguishable from a missing route, so probing
 * cannot confirm the control plane exists. Successful admin-JWT authentication exposes the
 * account id as {@link #ATTRIBUTE_ADMIN_ID} for downstream attribution.</p>
 */
@Slf4j
@RequiredArgsConstructor
public class AdminAuthFilter extends OncePerRequestFilter {

	private static final String BEARER_PREFIX = "Bearer ";
	private static final String HEADER_AUTHORIZATION = "Authorization";
	private static final String HEADER_X_ADMIN_KEY = "X-Admin-Key";

	/**
	 * Request attribute carrying the authenticated admin account id (JWT path only;
	 * {@code null} for master-key callers).
	 */
	public static final String ATTRIBUTE_ADMIN_ID = "cacherelay.adminId";

	private final String masterKey;
	private final ObjectMapper objectMapper;
	private final JwtService jwtService;
	private final UserAccountRepository users;
	private final AuthAuditService audit;

	/**
	 * Creates a master-key-only filter (JWT path and mutation audit disabled).
	 *
	 * @param masterKey    configured master secret
	 * @param objectMapper JSON serializer for Problem Details responses
	 */
	public AdminAuthFilter(String masterKey, ObjectMapper objectMapper) {
		this(masterKey, objectMapper, null, null, null);
	}

	@Override
	protected void doFilterInternal(
			HttpServletRequest request,
			HttpServletResponse response,
			FilterChain filterChain
	) throws ServletException, IOException {

		if (masterKey == null || masterKey.isBlank()) {
			log.warn(
					"Rejected access to admin endpoint {}: master admin key is not configured",
					request.getRequestURI()
			);
			writeProblem(
					response,
					HttpServletResponse.SC_FORBIDDEN,
					"Admin Interface Disabled",
					"Master admin key is not configured. Administrative access is disabled.",
					request.getRequestURI()
			);
			return;
		}

		String token = extractToken(request);
		if (token == null || token.isBlank()) {
			writeStealth(response, request.getRequestURI());
			return;
		}

		byte[] expectedBytes = masterKey.getBytes(StandardCharsets.UTF_8);
		byte[] actualBytes = token.getBytes(StandardCharsets.UTF_8);

		if (MessageDigest.isEqual(expectedBytes, actualBytes)) {
			filterChain.doFilter(request, response);
			auditMutation(request, response, null);
			return;
		}

		if (acceptAdminJwt(request, token)) {
			filterChain.doFilter(request, response);
			auditMutation(request, response,
					request.getAttribute(ATTRIBUTE_ADMIN_ID).toString());
			return;
		}

		log.warn("Invalid admin authentication attempt on {}", request.getRequestURI());
		writeStealth(response, request.getRequestURI());
	}

	/**
	 * Accepts a live admin access JWT presented as a Bearer token, attributing the account.
	 *
	 * @param request current request (receives the admin id attribute)
	 * @param token   presented bearer value (already failed the master-key match)
	 * @return whether the token authenticates an enabled admin account
	 */
	private boolean acceptAdminJwt(HttpServletRequest request, String token) {
		if (jwtService == null || users == null) {
			return false;
		}
		String authHeader = request.getHeader(HEADER_AUTHORIZATION);
		if (authHeader == null || !authHeader.startsWith(BEARER_PREFIX)) {
			return false;
		}
		try {
			Jwt decoded = jwtService.validate(token);
			Object adminClaim = decoded.getClaim("admin");
			if (!Boolean.TRUE.equals(adminClaim)) {
				return false;
			}
			Optional<UserAccount> account =
					users.findById(UUID.fromString(decoded.getSubject()));
			if (account.isEmpty() || account.get().isDisabled() || !account.get().isAdmin()) {
				return false;
			}
			request.setAttribute(ATTRIBUTE_ADMIN_ID, account.get().getId());
			return true;
		} catch (RuntimeException e) {
			return false;
		}
	}

	/**
	 * Records every admin mutation (non-read method): actor, path, and outcome derived
	 * from the response status. Reads stay out of the ledger to keep it signal-dense.
	 *
	 * @param request  current request
	 * @param response current response (status already set by the controller)
	 * @param adminId  JWT admin id, or {@code null} for master-key callers
	 */
	private void auditMutation(HttpServletRequest request, HttpServletResponse response,
			String adminId) {
		if (audit == null) {
			return;
		}
		String method = request.getMethod();
		if ("GET".equalsIgnoreCase(method) || "HEAD".equalsIgnoreCase(method)
				|| "OPTIONS".equalsIgnoreCase(method)) {
			return;
		}
		int status = response.getStatus();
		audit.record(AuthAuditService.ACTION_ADMIN_MUTATION,
				status < 400 ? AuthAuditService.SEVERITY_WARN : AuthAuditService.SEVERITY_ERROR,
				adminId != null ? adminId : "master-key",
				request.getRequestURI(),
				status < 400 ? AuthAuditService.OUTCOME_SUCCESS : AuthAuditService.OUTCOME_FAILURE,
				request.getRemoteAddr(),
				request.getHeader("X-Request-ID"));
	}

	/**
	 * Hides the control plane: denial looks exactly like a missing route.
	 *
	 * @param response current response
	 * @param instance request path
	 */
	private void writeStealth(HttpServletResponse response, String instance) throws IOException {
		writeProblem(
				response,
				HttpServletResponse.SC_NOT_FOUND,
				"Not Found",
				"No such endpoint.",
				instance
		);
	}

	private String extractToken(HttpServletRequest request) {
		String authHeader = request.getHeader(HEADER_AUTHORIZATION);
		if (authHeader != null && authHeader.startsWith(BEARER_PREFIX)) {
			return authHeader.substring(BEARER_PREFIX.length()).trim();
		}
		String xAdminKey = request.getHeader(HEADER_X_ADMIN_KEY);
		if (xAdminKey != null) {
			return xAdminKey.trim();
		}
		return null;
	}

	private void writeProblem(
			HttpServletResponse response,
			int status,
			String title,
			String detail,
			String instance
	) throws IOException {
		response.setStatus(status);
		response.setContentType("application/problem+json");
		response.setCharacterEncoding("UTF-8");
		ProblemDetailResponse problem = ProblemDetailResponse.of(title, status, detail, instance);
		objectMapper.writeValue(response.getWriter(), problem);
	}
}
