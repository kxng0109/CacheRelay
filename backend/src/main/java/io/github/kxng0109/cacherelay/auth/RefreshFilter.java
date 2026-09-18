package io.github.kxng0109.cacherelay.auth;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Optional;
import java.util.UUID;

import io.github.kxng0109.cacherelay.admin.dto.ProblemDetailResponse;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.ResponseCookie;
import org.springframework.web.filter.OncePerRequestFilter;
import tools.jackson.databind.ObjectMapper;

/**
 * Rotates opaque refresh sessions on {@code POST /v1/auth/refresh}.
 *
 * <p>The request must carry the refresh {@code httpOnly} cookie plus the
 * {@code X-CacheRelay-Refresh: 1} header: browsers never auto-attach custom headers on
 * cross-site form posts, so the header is a CSRF guard that needs no server-side token
 * store. Success returns a fresh access token and rotates the cookie; replay of a rotated
 * token revokes the whole family and clears the cookie. All failures share one 401.
 */
public class RefreshFilter extends OncePerRequestFilter {

	static final String REFRESH_PATH = "/v1/auth/refresh";
	static final String CSRF_HEADER = "X-CacheRelay-Refresh";

	private final RefreshService refresh;
	private final JwtService jwt;
	private final UserAccountRepository users;
	private final AuthCookieService cookies;
	private final AuthProperties properties;
	private final AuthAuditService audit;
	private final ObjectMapper objectMapper;

	/**
	 * Creates the filter.
	 *
	 * @param refresh      refresh lifecycle
	 * @param jwt          access-token issuer
	 * @param users        account lookup for claim stamping
	 * @param cookies      cookie handling
	 * @param properties   auth tuning surface
	 * @param audit        audit log
	 * @param objectMapper JSON serializer for responses
	 */
	public RefreshFilter(RefreshService refresh, JwtService jwt, UserAccountRepository users,
			AuthCookieService cookies, AuthProperties properties, AuthAuditService audit,
			ObjectMapper objectMapper) {
		this.refresh = refresh;
		this.jwt = jwt;
		this.users = users;
		this.cookies = cookies;
		this.properties = properties;
		this.audit = audit;
		this.objectMapper = objectMapper;
	}

	@Override
	protected boolean shouldNotFilter(HttpServletRequest request) {
		return !("POST".equalsIgnoreCase(request.getMethod())
				&& REFRESH_PATH.equals(request.getRequestURI()));
	}

	@Override
	protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
			FilterChain filterChain) throws ServletException, IOException {
		if (!"1".equals(request.getHeader(CSRF_HEADER))) {
			writeProblem(response, HttpServletResponse.SC_BAD_REQUEST, "Bad Request",
					"Refresh requires the X-CacheRelay-Refresh header.", REFRESH_PATH);
			return;
		}
		String presented = cookies.extract(request);
		if (presented == null) {
			writeUnauthorized(response);
			return;
		}
		RefreshService.RotationResult result = refresh.rotate(presented);
		if (result instanceof RefreshService.RotationSuccess success) {
			Optional<UserAccount> account = users.findById(success.userId());
			if (account.isEmpty()) {
				writeUnauthorized(response);
				return;
			}
			UserAccount user = account.get();
			long ttl = (user.isAdmin()
					? properties.adminAccessTtl()
					: properties.accessTtl()).toSeconds();
			String access = jwt.issueAccessToken(user.getId(), user.getUsername(),
					user.isAdmin(), ttl);
			ResponseCookie rotated = cookies.createCookie(success.token(), user.isAdmin());
			response.setHeader("Set-Cookie", rotated.toString());
			audit.record(AuthAuditService.ACTION_REFRESH, AuthAuditService.SEVERITY_INFO,
					user.getUsername(), REFRESH_PATH, AuthAuditService.OUTCOME_SUCCESS,
					request.getRemoteAddr(), request.getHeader("X-Request-ID"));
			response.setStatus(HttpServletResponse.SC_OK);
			response.setContentType("application/json");
			response.setCharacterEncoding("UTF-8");
			objectMapper.writeValue(response.getWriter(), new RefreshBody(access, ttl,
					user.isAdmin()));
			return;
		}
		if (result instanceof RefreshService.RotationReuse) {
			response.setHeader("Set-Cookie", cookies.clearCookie().toString());
			audit.record(AuthAuditService.ACTION_REUSE_DETECTED,
					AuthAuditService.SEVERITY_CRITICAL, "unknown", REFRESH_PATH,
					AuthAuditService.OUTCOME_FAILURE, request.getRemoteAddr(),
					request.getHeader("X-Request-ID"));
		}
		writeUnauthorized(response);
	}

	private void writeUnauthorized(HttpServletResponse response) throws IOException {
		writeProblem(response, HttpServletResponse.SC_UNAUTHORIZED, "Unauthorized",
				"Invalid or expired session. Log in again.", REFRESH_PATH);
	}

	private void writeProblem(HttpServletResponse response, int status, String title,
			String detail, String instance) throws IOException {
		response.setStatus(status);
		response.setContentType("application/problem+json");
		response.setCharacterEncoding("UTF-8");
		objectMapper.writeValue(response.getWriter(),
				ProblemDetailResponse.of(title, status, detail, instance));
	}

	/**
	 * Refresh response body (access token only; the rotated refresh travels by cookie).
	 *
	 * @param accessToken      fresh signed JWT
	 * @param expiresInSeconds seconds until access expiry
	 * @param admin            whether the session is administrative
	 */
	public record RefreshBody(String accessToken, long expiresInSeconds, boolean admin) {
	}
}
