package io.github.kxng0109.cacherelay.proxy.embeddings;

import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import io.github.kxng0109.cacherelay.budget.BudgetDecision;

/**
 * Budget denial on the embeddings path carrying the binding decision for header rendering.
 *
 * <p>A bare {@code ResponseStatusException} cannot carry response headers, which left embeddings 429s without
 * the {@code X-Budget-*} family the chat path sends. The controller translates this exception into the same
 * 429 shape (headers plus body), so both public surfaces report exhaustion identically.
 *
 * @since 1.8.0
 */
public class EmbeddingBudgetDeniedException extends ResponseStatusException {

	private final BudgetDecision.Denied denied;

	/**
	 * Creates a denial preserving the exact legacy message text.
	 *
	 * @param denied binding budget decision; must not be {@code null}
	 */
	public EmbeddingBudgetDeniedException(BudgetDecision.Denied denied) {
		super(HttpStatus.TOO_MANY_REQUESTS,
				"budget exhausted (" + denied.level() + " " + denied.window() + ", retry after "
						+ Math.max(1L, denied.retryAfterSeconds()) + "s)");
		this.denied = denied;
	}

	/**
	 * Returns the binding decision for header rendering.
	 *
	 * @return binding budget decision
	 */
	public BudgetDecision.Denied getDenied() {
		return denied;
	}
}
