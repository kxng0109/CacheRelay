package io.github.kxng0109.cacherelay.proxy.failover;

import io.github.kxng0109.cacherelay.security.SsrfValidator;
import io.github.kxng0109.cacherelay.security.SsrfViolationException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * {@link UpstreamUrlValidator} backed by the gateway's SSRF control.
 *
 * <p>Provider URLs originate from trusted configuration rather than client
 * input, so the SSRF check is a defense in depth layer that guards against misconfiguration and future dynamic targets.
 * The check runs once per provider, lazily, before the first attempt.</p>
 *
 * <p>Local development backends (LM Studio, Ollama, vLLM) resolve to private
 * addresses by design. They are allowlisted explicitly via
 * {@code gateway.dev.allow-private-hosts} (comma-separated, empty by default):
 * allowlisting is exact-host, never a subnet, and every non-allowlisted private
 * target still fails closed.</p>
 */
@Component
public class SsrfUpstreamUrlValidator implements UpstreamUrlValidator {

	private final SsrfValidator delegate;
	private final Set<String> allowPrivateHosts;

	/**
	 * @param delegate          the SSRF validator to delegate to
	 * @param allowPrivateHosts exact hostnames permitted to resolve privately
	 */
	public SsrfUpstreamUrlValidator(
			SsrfValidator delegate,
			@Value("${gateway.dev.allow-private-hosts:}") Set<String> allowPrivateHosts
	) {
		this.delegate = delegate;
		this.allowPrivateHosts = allowPrivateHosts.stream()
		                                          .map(SsrfValidator::normalizeHost)
		                                          .collect(Collectors.toUnmodifiableSet());
	}

	/**
	 * @param targetUrl the URL about to be contacted
	 * @throws SsrfViolationException when the target is unsafe or unresolvable
	 */
	@Override
	public void validate(URI targetUrl) {
		String rawHost = targetUrl != null ? targetUrl.getHost() : null;
		String host = rawHost != null ? SsrfValidator.normalizeHost(rawHost) : null;
		if (host != null && allowPrivateHosts.contains(host)) {
			// CIDR verdict waived: exact-host dev trust; scheme/userinfo/resolvability still enforced.
			delegate.validateAllowlistedSchemeAndResolvability(targetUrl);
			return;
		}
		delegate.validate(targetUrl);
	}

	/**
	 * @param host the target host
	 * @return true when the host is explicitly allowlisted for private resolution
	 */
	@Override
	public boolean isPrivateHostAllowed(String host) {
		return host != null && allowPrivateHosts.contains(SsrfValidator.normalizeHost(host));
	}
}