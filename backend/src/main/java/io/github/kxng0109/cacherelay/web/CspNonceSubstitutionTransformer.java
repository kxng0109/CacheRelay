package io.github.kxng0109.cacherelay.web;

import io.github.kxng0109.cacherelay.auth.CspNonceFilter;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.core.io.Resource;
import org.springframework.web.servlet.resource.ResourceTransformer;
import org.springframework.web.servlet.resource.ResourceTransformerChain;
import org.springframework.web.servlet.resource.TransformedResource;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

/**
 * Substitutes the per-response CSP nonce into the SPA shell (FE-01).
 *
 * <p>{@link CspNonceFilter} mints a fresh nonce per response and enforces
 * {@code script-src 'nonce-…' 'strict-dynamic'}, but the shipped shell only
 * carries placeholders ({@code __CSP_NONCE__} on script tags and the meta
 * tag, {@code CSP_NONCE_PLACEHOLDER} from the Vite build). Without
 * substitution the parser-inserted entry script is blocked. This transformer
 * replaces both placeholders with the request's nonce, so every
 * {@code nonce=} in the served HTML equals the header's {@code nonce-}
 * value.</p>
 *
 * <p>Registered with an uncached resource chain and a {@code no-store}
 * policy: substituted HTML is per-response and must never be cached.</p>
 *
 * @since 1.8.0
 */
public class CspNonceSubstitutionTransformer implements ResourceTransformer {

	/**
	 * Placeholder on script tags and the nonce meta tag.
	 */
	static final String LEGACY_PLACEHOLDER = "__CSP_NONCE__";

	/**
	 * Placeholder emitted by the Vite build ({@code html.cspNonce}).
	 */
	static final String VITE_PLACEHOLDER = "CSP_NONCE_PLACEHOLDER";

	@Override
	public Resource transform(HttpServletRequest request, Resource resource,
	                          ResourceTransformerChain chain) throws IOException {
		Resource resolved = chain.transform(request, resource);
		Object nonce = request.getAttribute(CspNonceFilter.NONCE_ATTRIBUTE);
		if (!(nonce instanceof String value) || value.isEmpty()) {
			return resolved;
		}
		String html = new String(resolved.getContentAsByteArray(), StandardCharsets.UTF_8);
		if (!html.contains(LEGACY_PLACEHOLDER) && !html.contains(VITE_PLACEHOLDER)) {
			return resolved;
		}
		byte[] substituted = html.replace(LEGACY_PLACEHOLDER, value)
				.replace(VITE_PLACEHOLDER, value)
				.getBytes(StandardCharsets.UTF_8);
		return new TransformedResource(resolved, substituted);
	}
}
