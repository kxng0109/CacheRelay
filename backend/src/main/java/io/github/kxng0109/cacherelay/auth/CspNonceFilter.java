package io.github.kxng0109.cacherelay.auth;

import java.io.IOException;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.List;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Writes a strict per-response Content Security Policy with a fresh cryptographic nonce.
 *
 * <p>Spring Security's CSP writer only supports static directives, so nonces are minted
 * here: 128 random bits per response, exposed as {@link #NONCE_ATTRIBUTE} for server-side
 * shell rendering, and enforced via {@code script-src 'nonce-…' 'strict-dynamic'} with
 * {@code object-src 'none'}, {@code base-uri 'none'}, and {@code frame-ancestors 'none'}.</p>
 *
 * <p>Observability probes use the management base (loopback {@code :9091} in development),
 * which is cross-origin to the gateway: {@code connect-src 'self'} alone would block them.
 * Extra origins from {@code gateway.csp.extra-connect-src} are appended verbatim — they are
 * startup-validated absolute {@code http(s)} origins, so the interpolation is header-safe.</p>
 */
public class CspNonceFilter extends OncePerRequestFilter {

	/**
	 * Request attribute carrying the response nonce for shell rendering.
	 */
	public static final String NONCE_ATTRIBUTE = "cacherelay.csp.nonce";

	private final SecureRandom random = new SecureRandom();

	private final List<String> extraConnectSrc;

	/**
	 * Creates the filter with same-origin {@code connect-src}.
	 */
	public CspNonceFilter() {
		this(List.of());
	}

	/**
	 * Creates the filter with extra {@code connect-src} origins.
	 *
	 * @param extraConnectSrc validated absolute origins, never {@code null}
	 */
	public CspNonceFilter(List<String> extraConnectSrc) {
		this.extraConnectSrc = extraConnectSrc == null ? List.of() : List.copyOf(extraConnectSrc);
	}

	@Override
	protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
			FilterChain filterChain) throws ServletException, IOException {
		byte[] bytes = new byte[16];
		random.nextBytes(bytes);
		String nonce = Base64.getEncoder().encodeToString(bytes);
		request.setAttribute(NONCE_ATTRIBUTE, nonce);
		StringBuilder connectSrc = new StringBuilder("connect-src 'self'");
		for (String origin : extraConnectSrc) {
			connectSrc.append(' ').append(origin);
		}
		response.setHeader("Content-Security-Policy",
				"default-src 'self'; "
						+ "script-src 'nonce-" + nonce + "' 'strict-dynamic'; "
						+ "style-src 'self' 'nonce-" + nonce + "'; "
						+ connectSrc + "; "
						+ "img-src 'self' data:; "
						+ "font-src 'self'; "
						+ "object-src 'none'; "
						+ "base-uri 'none'; "
						+ "frame-ancestors 'none'");
		filterChain.doFilter(request, response);
	}
}
