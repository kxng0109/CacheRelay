package io.github.kxng0109.cacherelay.auth;

import java.io.IOException;
import java.security.SecureRandom;
import java.util.Base64;

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
 * {@code object-src 'none'}, {@code base-uri 'none'}, and {@code frame-ancestors 'none'}.
 */
public class CspNonceFilter extends OncePerRequestFilter {

	/**
	 * Request attribute carrying the response nonce for shell rendering.
	 */
	public static final String NONCE_ATTRIBUTE = "cacherelay.csp.nonce";

	private final SecureRandom random = new SecureRandom();

	@Override
	protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
			FilterChain filterChain) throws ServletException, IOException {
		byte[] bytes = new byte[16];
		random.nextBytes(bytes);
		String nonce = Base64.getEncoder().encodeToString(bytes);
		request.setAttribute(NONCE_ATTRIBUTE, nonce);
		response.setHeader("Content-Security-Policy",
				"default-src 'self'; "
						+ "script-src 'nonce-" + nonce + "' 'strict-dynamic'; "
						+ "style-src 'self' 'nonce-" + nonce + "'; "
						+ "connect-src 'self'; "
						+ "img-src 'self' data:; "
						+ "font-src 'self'; "
						+ "object-src 'none'; "
						+ "base-uri 'none'; "
						+ "frame-ancestors 'none'");
		filterChain.doFilter(request, response);
	}
}
