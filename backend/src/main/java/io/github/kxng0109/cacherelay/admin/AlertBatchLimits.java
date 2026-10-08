package io.github.kxng0109.cacherelay.admin;

/**
 * Single owner of the Alertmanager batch bound.
 *
 * <p>Both the authenticated and secret-guarded receivers enforce the same limit; the constant lives here so a change
 * can never reach one receiver and miss the other.</p>
 */
public final class AlertBatchLimits {

	/** Maximum alerts accepted in one webhook batch. */
	public static final int MAX_ALERTS_PER_BATCH = 100;

	private AlertBatchLimits() {
	}
}
