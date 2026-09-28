package io.github.kxng0109.cacherelay.auth;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

import java.util.ArrayList;
import java.util.List;

/**
 * Configuration properties for the Content Security Policy surface under
 * {@code gateway.csp}.
 *
 * <p>The management origin is cross-origin in development (loopback
 * {@code :9091}) while {@code connect-src 'self'} only allows same-origin
 * fetches. Listing that origin here appends it to the policy's
 * {@code connect-src} list; the default stays empty (same-origin only).
 * Malformed values fail startup via {@link Validated} binding.</p>
 *
 * @since 1.8.0
 */
@Getter
@Setter
@Validated
@ConfigurationProperties(prefix = "gateway.csp")
public class CspProperties {

	/**
	 * Extra absolute {@code http(s)} origins appended to {@code connect-src}.
	 */
	@CspConnectSrc
	private List<String> extraConnectSrc = new ArrayList<>();
}
