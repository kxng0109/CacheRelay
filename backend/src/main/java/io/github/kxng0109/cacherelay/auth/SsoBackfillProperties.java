package io.github.kxng0109.cacherelay.auth;

import java.util.List;
import java.util.Optional;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.validation.annotation.Validated;

/**
 * First-login backfill wiring bound from
 * {@code gateway.sso.backfill.registrations[]}, joined to
 * {@link SsoClaimProperties} by registration id.
 *
 * <p>Token claims stay the primary source; backfill runs only when claims
 * cannot carry membership and blocks the first login, failing closed on any
 * error. Registrations without an entry use claims only.</p>
 *
 * @param registrations per-registration backfill entries, never {@code null}
 */
@ConfigurationProperties("gateway.sso.backfill")
@Validated
public record SsoBackfillProperties(
		@Valid List<RegistrationBackfill> registrations
) {

	/**
	 * The documented defaults: no backfill anywhere.
	 */
	public static final SsoBackfillProperties DEFAULTS = new SsoBackfillProperties(List.of());

	/**
	 * Canonical constructor normalizing absent lists to empty.
	 */
	public SsoBackfillProperties {
		registrations = registrations == null ? List.of() : List.copyOf(registrations);
	}

	/**
	 * Finds the backfill entry for one Spring registration id.
	 *
	 * @param registrationId Spring registration id, never {@code null}
	 * @return the entry when configured
	 */
	public Optional<RegistrationBackfill> forRegistration(String registrationId) {
		for (RegistrationBackfill entry : registrations) {
			if (entry.registrationId().equals(registrationId)) {
				return Optional.of(entry);
			}
		}
		return Optional.empty();
	}

	/**
	 * Backfill wiring for one Spring registration id.
	 *
	 * @param registrationId Spring registration id, never blank
	 * @param mode           backfill strategy, never {@code null}
	 * @param apiScope       tenant id, workspace domain, or org login, never blank
	 */
	public record RegistrationBackfill(
			@NotBlank String registrationId,
			@DefaultValue("NONE") BackfillMode mode,
			@NotBlank String apiScope
	) {

		/**
		 * Canonical constructor normalizing an absent mode to none.
		 */
		public RegistrationBackfill {
			mode = mode == null ? BackfillMode.NONE : mode;
		}
	}
}
