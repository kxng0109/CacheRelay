package io.github.kxng0109.cacherelay.auth;

import java.io.IOException;

import io.github.kxng0109.cacherelay.admin.dto.ProblemDetailResponse;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.web.access.AccessDeniedHandler;
import tools.jackson.databind.ObjectMapper;

/**
 * Hides admin-path existence from unauthorized callers: denials under
 * {@code /v1/admin/**} answer 404 (indistinguishable from a missing route) instead of
 * 403, so probing cannot confirm the control plane exists. Deny-by-default elsewhere is
 * untouched — non-admin routes still answer 403.
 */
@RequiredArgsConstructor
public class StealthAccessDeniedHandler implements AccessDeniedHandler {

	private static final String ADMIN_PREFIX = "/v1/admin/";

	private final ObjectMapper objectMapper;

	@Override
	public void handle(HttpServletRequest request, HttpServletResponse response,
			AccessDeniedException accessDeniedException) throws IOException {
		String path = request.getRequestURI();
		if (path != null && path.startsWith(ADMIN_PREFIX)) {
			writeProblem(response, HttpServletResponse.SC_NOT_FOUND, "Not Found",
					"No such endpoint.", path);
			return;
		}
		writeProblem(response, HttpServletResponse.SC_FORBIDDEN, "Forbidden",
				"Access is denied.", path);
	}

	private void writeProblem(HttpServletResponse response, int status, String title,
			String detail, String instance) throws IOException {
		response.setStatus(status);
		response.setContentType("application/problem+json");
		response.setCharacterEncoding("UTF-8");
		objectMapper.writeValue(response.getWriter(),
				ProblemDetailResponse.of(title, status, detail, instance));
	}
}
