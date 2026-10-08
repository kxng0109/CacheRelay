package io.github.kxng0109.cacherelay.auth.webhook;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import java.io.IOException;

/**
 * Shared webhook body reader for the SSO receivers.
 *
 * <p>All four receivers (Entra, GitHub, Google, Okta) read the raw bytes identically and reject unreadable bodies
 * with the same frozen reason. Receiver configuration, signature, and payload errors stay in the controllers.</p>
 */
public final class WebhookBodies {

	private WebhookBodies() {
	}

	/**
	 * Reads the raw request body.
	 *
	 * @param request current request, never {@code null}
	 * @return raw body bytes
	 * @throws ResponseStatusException HTTP 400 {@code unreadable body} when the stream cannot be read
	 */
	public static byte[] rawBody(HttpServletRequest request) {
		try {
			return request.getInputStream().readAllBytes();
		} catch (IOException failed) {
			throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "unreadable body");
		}
	}
}
