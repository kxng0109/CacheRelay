package io.github.kxng0109.cacherelay.security.filter;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

/**
 * Wraps eligible {@code POST} requests in a {@link CachedBodyHttpServletRequest} so the body can
 * be read multiple times: once by the auth/rate-limit filter and again by the controller's {@code @RequestBody}.
 *
 * <p>The response is deliberately never touched: SSE streaming downstream must
 * remain zero-buffer. Bodies larger than the configured cap are rejected with HTTP {@code 413 Payload Too Large}
 * before buffering completes (declared length is checked first; unknown lengths abort past the cap).</p>
 *
 * <p>Registered at {@link #ORDER} (0), the maximum order allowed for a
 * request-wrapping filter (see the Boot reference: wrapping filters must be ordered
 * {@code <= OrderedFilter.REQUEST_WRAPPER_FILTER_MAX_ORDER} so they run after the character-encoding filter).</p>
 */
public class RequestBodyCachingFilter extends OncePerRequestFilter {

	/**
	 * Registration order: runs first among the gateway filters.
	 */
	public static final int ORDER = 0;

	/**
	 * The paths this filter applies to. Chat and embeddings are wrapped so the body can be
	 * read multiple times (auth/rate-limit filter, then the controller). MCP and A2A are wrapped
	 * for the same cap, which must hold pre-auth: their controllers buffer {@code @RequestBody}
	 * with no bound otherwise (MCP-B04).
	 */
	public static final String TARGET_PATH_CHAT = "/v1/chat/completions";
	public static final String TARGET_PATH_EMBEDDINGS = "/v1/embeddings";
	public static final String TARGET_PATH_MCP = "/v1/mcp";
	public static final String TARGET_PATH_A2A = "/v1/a2a";

	/**
	 * Default body cap, matching {@link CachedBodyHttpServletRequest#DEFAULT_MAX_BODY_BYTES}.
	 */
	public static final int DEFAULT_MAX_BODY_BYTES = CachedBodyHttpServletRequest.DEFAULT_MAX_BODY_BYTES;

	private final int maxBodyBytes;

	/**
	 * Creates the filter with the default 1 MiB body cap.
	 */
	public RequestBodyCachingFilter() {
		this(DEFAULT_MAX_BODY_BYTES);
	}

	/**
	 * @param maxBodyBytes body-size cap (must be {@code > 0})
	 * @throws IllegalArgumentException if {@code maxBodyBytes <= 0}
	 */
	public RequestBodyCachingFilter(int maxBodyBytes) {
		if (maxBodyBytes <= 0) {
			throw new IllegalArgumentException("maxBodyBytes must be > 0, was " + maxBodyBytes);
		}
		this.maxBodyBytes = maxBodyBytes;
	}

	@Override
	protected boolean shouldNotFilter(HttpServletRequest request) {
		if (!HttpMethod.POST.matches(request.getMethod())) {
			return true;
		}
		String path = request.getServletPath();
		return !TARGET_PATH_CHAT.equals(path)
				&& !TARGET_PATH_EMBEDDINGS.equals(path)
				&& !TARGET_PATH_MCP.equals(path)
				&& !TARGET_PATH_A2A.equals(path)
				&& !path.startsWith(TARGET_PATH_MCP + "/")
				&& !path.startsWith(TARGET_PATH_A2A + "/");
	}

	@Override
	protected void doFilterInternal(
			HttpServletRequest request,
			HttpServletResponse response,
			FilterChain filterChain
	) throws ServletException, IOException {
		try {
			filterChain.doFilter(new CachedBodyHttpServletRequest(request, maxBodyBytes), response);
		} catch (BodyTooLargeException ex) {
			response.setStatus(HttpServletResponse.SC_REQUEST_ENTITY_TOO_LARGE);
			response.setContentType(MediaType.APPLICATION_JSON_VALUE);
			response.setCharacterEncoding(StandardCharsets.UTF_8.name());
			response.getWriter().write("{\"error\":{\"message\":\"request body too large\"}}");
		}
	}
}