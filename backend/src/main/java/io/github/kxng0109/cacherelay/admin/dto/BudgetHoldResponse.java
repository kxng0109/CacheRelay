package io.github.kxng0109.cacherelay.admin.dto;

import io.github.kxng0109.cacherelay.budget.BudgetSettlement;
import io.swagger.v3.oas.annotations.media.Schema;

/**
 * Point-in-time hold-then-settle view for one request.
 *
 * @param requestId     request identifier the hold was created under
 * @param subject       subject ref recorded at admission
 * @param heldMicros    micros held at admission
 * @param settledMicros micros applied at settle, or {@code null} before settle
 * @param state         hold lifecycle state
 */
@Schema(name = "BudgetHoldResponse", description = "Hold-versus-settled view for one request")
public record BudgetHoldResponse(
		@Schema(description = "Request identifier", example = "3f6a…")
		String requestId,

		@Schema(description = "Subject ref recorded at admission")
		String subject,

		@Schema(description = "Micros held at admission", example = "1250")
		long heldMicros,

		@Schema(description = "Micros applied at settle; null before settle")
		Long settledMicros,

		@Schema(description = "Hold lifecycle state (ACTIVE, SETTLED, ABORTED, EXPIRED)", example = "SETTLED")
		String state
) {
	/**
	 * Adapts the service view.
	 *
	 * @param view service hold view
	 * @return response DTO
	 */
	public static BudgetHoldResponse from(BudgetSettlement.HoldView view) {
		return new BudgetHoldResponse(
				view.holdId(),
				view.subject(),
				view.heldMicros(),
				view.settledMicros(),
				view.state());
	}
}
