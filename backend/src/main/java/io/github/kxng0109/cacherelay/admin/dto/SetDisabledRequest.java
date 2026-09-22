package io.github.kxng0109.cacherelay.admin.dto;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * Payload for toggling an account's disabled state. Disabling suspends access
 * and clears cached owner verdicts; terminal key tombstones are unaffected, and
 * re-enabling never resurrects keys.
 *
 * @param disabled whether the account is disabled
 */
@Schema(name = "SetDisabledRequest", description = "Payload for toggling an account disabled state")
public record SetDisabledRequest(
		@Schema(description = "Whether the account is disabled", example = "true")
		boolean disabled
) {
}
