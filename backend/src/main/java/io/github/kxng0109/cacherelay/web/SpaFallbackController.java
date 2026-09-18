package io.github.kxng0109.cacherelay.web;

import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;

/**
 * Single-page-application shell fallback for operator UI deep links.
 *
 * <p>A browser refresh on {@code /dashboard} asks the server for that exact path. With no matching handler the
 * request would die at the default-deny boundary, so this controller forwards every unmatched, extensionless route
 * to {@code /index.html} and the client router takes over.
 *
 * <p>The first path segment excludes reserved namespaces ({@code v1}, {@code actuator}, {@code v3},
 * {@code swagger-ui}, {@code error}, {@code assets}) via a zero-width negative lookahead — capturing groups are
 * forbidden in path-pattern constraints, so only {@code (?!...)} assertions are used. API, observability, docs,
 * error-dispatch and static-asset routes therefore never reach this controller even if the security chain is
 * relaxed later; conversely the chain still 403s them first today. Both layers must agree: the identical pattern
 * lives in {@code SecurityConfig} as a {@code permitAll} matcher, referenced here as
 * {@link #SPA_PATH_PATTERN} and inlined there at compile time (constant inlining means zero runtime coupling).
 *
 * @since 1.8.0
 */
@Controller
public class SpaFallbackController {

	/**
	 * First segment must not be a reserved namespace; any depth below it is an SPA route.
	 */
	public static final String SPA_PATH_PATTERN = "/{spa:^(?!v1$|actuator$|v3$|swagger-ui$|error$|assets$)[^.]*}/**";

	/**
	 * Forwards an SPA route to the shell.
	 *
	 * @return forward to {@code /index.html}
	 */
	@GetMapping(SPA_PATH_PATTERN)
	public String fallback() {
		return "forward:/index.html";
	}
}
