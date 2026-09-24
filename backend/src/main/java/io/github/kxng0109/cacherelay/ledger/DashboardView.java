package io.github.kxng0109.cacherelay.ledger;

import java.time.Instant;

import io.github.kxng0109.cacherelay.admin.dto.LedgerSummaryResponse;

/**
 * One computed dashboard view: the summary plus the freshness coordinates the
 * UI renders as its staleness note.
 *
 * @param summary     aggregated usage summary, never {@code null}
 * @param generatedAt when the view was computed, never {@code null}
 * @param watermark   newest ledger row counted, never {@code null}
 */
public record DashboardView(
		LedgerSummaryResponse summary,
		Instant generatedAt,
		Instant watermark
) {
}
