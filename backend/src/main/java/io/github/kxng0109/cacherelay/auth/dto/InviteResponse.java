package io.github.kxng0109.cacherelay.auth.dto;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * Created invite: always a copyable link, plus whether it was also emailed.
 *
 * @param link    redemption link (copyable from UI or terminal)
 * @param emailed whether the link was emailed to the invited address
 */
@Schema(name = "InviteResponse", description = "Single-use invite link")
public record InviteResponse(
		@Schema(description = "Redemption link")
		String link,

		@Schema(description = "Whether the link was emailed", example = "false")
		boolean emailed
) {
}
