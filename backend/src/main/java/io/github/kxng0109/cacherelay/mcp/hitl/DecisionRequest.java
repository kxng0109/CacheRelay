package io.github.kxng0109.cacherelay.mcp.hitl;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Size;

/**
 * Optional decision context for approving or rejecting a suspended tool call.
 *
 * @param reason    human reason for the decision, kept in the audit trail
 * @param decidedBy ignored by the server: the audit trail always records the authenticated
 *                  admin identity instead, so a caller can never self-attribute a decision
 */
@Schema(name = "DecisionRequest", description = "Optional reason for an approval decision")
public record DecisionRequest(
		@Schema(description = "Human reason (max 500 chars)")
		@Size(max = 500, message = "reason must be at most 500 characters")
		String reason,

		@Schema(description = "Decider identity as claimed (max 128 chars; ignored, server-derived)", example = "on-call")
		@Size(max = 128, message = "decidedBy must be at most 128 characters")
		String decidedBy
) {
}
