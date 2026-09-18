package io.github.kxng0109.cacherelay.admin.dto;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * Representation of an upstream provider circuit breaker state.
 *
 * @param provider             provider name
 * @param state                current state (CLOSED, OPEN, HALF_OPEN)
 * @param failures             consecutive failures recorded in CLOSED
 * @param cooldownMsRemaining  milliseconds until the cooldown elapses while OPEN
 *                             ({@code 0} otherwise; {@code -1} when uncomputable)
 * @param halfOpenProbe        whether a half-open probe is currently admitted
 */
@Schema(name = "CircuitStateResponse", description = "Current operational status of an upstream provider circuit breaker")
public record CircuitStateResponse(
		@Schema(description = "Provider identifier", example = "openai")
		String provider,

		@Schema(description = "Circuit state: CLOSED (healthy), OPEN (tripped), or HALF_OPEN (probing)", example = "CLOSED")
		String state,

		@Schema(description = "Consecutive failures recorded while CLOSED", example = "0")
		int failures,

		@Schema(description = "Milliseconds until cooldown elapses while OPEN (0 otherwise, -1 when uncomputable)", example = "0")
		long cooldownMsRemaining,

		@Schema(description = "Whether a half-open probe is currently admitted", example = "false")
		boolean halfOpenProbe
) {
}
