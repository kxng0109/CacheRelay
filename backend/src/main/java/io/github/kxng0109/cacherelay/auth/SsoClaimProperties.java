package io.github.kxng0109.cacherelay.auth;

import java.util.List;
import java.util.Optional;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import org.jspecify.annotations.Nullable;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.validation.annotation.Validated;

/**
 * Per-registration SSO team-provisioning mappings bound from
 * {@code gateway.sso.teams.registrations[]}.
 *
 * <p>Each entry binds one Spring registration id to its org, the claim names
 * carrying groups, roles, and tenant, the tenant allowlist, and the group
 * patterns that materialize teams. Registrations without an entry keep legacy
 * behavior (shadow account, no teams) so existing SSO keeps working until the
 * operator configures teams.</p>
 *
 * @param registrations per-registration mappings, never {@code null}
 */
@ConfigurationProperties("gateway.sso.teams")
@Validated
public record SsoClaimProperties(
		List<@Valid RegistrationTeams> registrations
) {

	/**
	 * The documented defaults: no mappings, legacy behavior everywhere.
	 */
	public static final SsoClaimProperties DEFAULTS = new SsoClaimProperties(List.of());

	/**
	 * Canonical constructor normalizing absent lists to empty.
	 */
	public SsoClaimProperties {
		registrations = registrations == null ? List.of() : List.copyOf(registrations);
	}

	/**
	 * Finds the mapping for one Spring registration id.
	 *
	 * @param registrationId Spring registration id, never {@code null}
	 * @return the mapping when configured
	 */
	public Optional<RegistrationTeams> forRegistration(String registrationId) {
		for (RegistrationTeams mapping : registrations) {
			if (mapping.registrationId().equals(registrationId)) {
				return Optional.of(mapping);
			}
		}
		return Optional.empty();
	}

	/**
	 * Team-provisioning mapping for one Spring registration id.
	 *
	 * <p>Group patterns accept exact IdP group ids, {@code prefix*} wildcards,
	 * and an optional {@code :LEAD} or {@code :MEMBER} suffix (default
	 * {@code MEMBER}). Team lead is the maximum IdP-derivable privilege.</p>
	 *
	 * @param registrationId Spring registration id, never blank
	 * @param orgSlug        org slug provisioned for this registration, never blank
	 * @param groupsClaim    groups claim name
	 * @param rolesClaim     roles claim name
	 * @param tenantClaim    tenant claim name, or {@code ""} for none
	 * @param allowedTenants tenant allowlist, empty disables the check
	 * @param groupPatterns  group patterns materializing teams, empty matches nothing
	 */
	public record RegistrationTeams(
			@NotBlank String registrationId,
			@NotBlank String orgSlug,
			@DefaultValue("groups") String groupsClaim,
			@DefaultValue("roles") String rolesClaim,
			@DefaultValue("") String tenantClaim,
			List<String> allowedTenants,
			List<String> groupPatterns
	) {

		/**
		 * Canonical constructor normalizing absent values to safe defaults.
		 */
		public RegistrationTeams {
			groupsClaim = groupsClaim == null || groupsClaim.isBlank() ? "groups" : groupsClaim;
			rolesClaim = rolesClaim == null || rolesClaim.isBlank() ? "roles" : rolesClaim;
			tenantClaim = tenantClaim == null ? "" : tenantClaim;
			allowedTenants = copyOfNullable(allowedTenants);
			groupPatterns = copyOfNullable(groupPatterns);
		}

		private static List<String> copyOfNullable(@Nullable List<String> values) {
			return values == null ? List.of() : List.copyOf(values);
		}
	}
}
