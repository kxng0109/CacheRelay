package io.github.kxng0109.cacherelay.auth.backfill;

/**
 * One backfill attempt outcome.
 *
 * @param status verdict, never {@code null}
 * @param result groups plus disabled flag, present only on {@code SUCCEEDED}
 */
public record BackfillOutcome(
		BackfillStatus status,
		BackfillResult result
) {

	/**
	 * @return skipped verdict, never {@code null}
	 */
	public static BackfillOutcome skipped() {
		return new BackfillOutcome(BackfillStatus.SKIPPED, null);
	}

	/**
	 * @return failed verdict, never {@code null}
	 */
	public static BackfillOutcome failed() {
		return new BackfillOutcome(BackfillStatus.FAILED, null);
	}

	/**
	 * @param result fetch result, never {@code null}
	 * @return success verdict, never {@code null}
	 */
	public static BackfillOutcome succeeded(BackfillResult result) {
		return new BackfillOutcome(BackfillStatus.SUCCEEDED, result);
	}
}
