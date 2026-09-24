package io.github.kxng0109.cacherelay.auth.webhook;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import io.github.kxng0109.cacherelay.auth.SsoLink;
import io.github.kxng0109.cacherelay.auth.SsoLinkRepository;
import io.github.kxng0109.cacherelay.auth.SsoRevalidation;
import io.github.kxng0109.cacherelay.auth.SsoRevalidationRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * Shared webhook effect: stamp matching links' watermarks at the epoch so
 * the sweep re-checks promptly. Never revokes directly; the sweep owns
 * revocation minutes later. Re-stamping is naturally idempotent.
 */
@Component
@RequiredArgsConstructor
public class WebhookInvalidator {

	private final SsoLinkRepository links;

	private final SsoRevalidationRepository watermarks;

	/**
	 * Touches every watermark for one registration and subject.
	 *
	 * @param registrationId Spring registration id, never {@code null}
	 * @param subject        IdP subject key, or {@code null} to skip
	 * @return {@code true} when at least one watermark moved
	 */
	public boolean invalidate(String registrationId, String subject) {
		if (subject == null || subject.isBlank()) {
			return false;
		}
		boolean touched = false;
		for (SsoLink link : links.findBySubject(subject)) {
			if (!registrationId.equals(link.getRegistrationId())) {
				continue;
			}
			touch(link.getUserId());
			touched = true;
		}
		return touched;
	}

	private void touch(UUID id) {
		Optional<SsoRevalidation> watermark = watermarks.findById(id);
		if (watermark.isEmpty()) {
			watermarks.save(new SsoRevalidation(id, Instant.EPOCH, null));
		} else {
			watermark.get().mark(Instant.EPOCH, null);
			watermarks.save(watermark.get());
		}
	}
}
