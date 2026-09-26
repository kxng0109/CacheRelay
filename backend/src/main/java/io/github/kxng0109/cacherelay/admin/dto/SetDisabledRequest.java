package io.github.kxng0109.cacherelay.admin.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;

/**
 * Payload for toggling an account's disabled state. Disabling suspends access
 * and clears cached owner verdicts; terminal key tombstones are unaffected, and
 * re-enabling never resurrects keys.
 *
 * <p>The flag is a boxed {@link Boolean} with {@link NotNull}: an absent value
 * (an empty {@code {}} body) fails with 400 instead of silently re-enabling the
 * account through a primitive default.</p>
 *
 * @param disabled whether the account is disabled; must be explicit
 */
@Schema(name = "SetDisabledRequest", description = "Payload for toggling an account disabled state")
public record SetDisabledRequest(
		@Schema(description = "Whether the account is disabled", example = "true")
		@NotNull(message = "disabled must be explicit") Boolean disabled
) {
}
