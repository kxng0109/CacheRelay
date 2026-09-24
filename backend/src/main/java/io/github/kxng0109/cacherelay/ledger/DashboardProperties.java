package io.github.kxng0109.cacherelay.ledger;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.validation.annotation.Validated;

/**
 * On-demand dashboard ceilings bound from {@code gateway.dashboard.*}.
 *
 * <p>Dashboards compute only when viewed: nothing precomputes in the background.
 * These values bound result-cache freshness, the late-arrival grace overlap, the
 * default query window, the per-query statement timeout, and the per-user view
 * rate. They are read when the dashboard service is created.</p>
 *
 * @param resultTtlMinutes      how long a computed dashboard summary stays cached
 * @param graceOverlapMinutes   how far behind the cache watermark delta queries reach
 * @param defaultWindowDays     trailing window applied when callers pass no range
 * @param statementTimeoutSeconds per-query Postgres statement timeout for dashboard scans
 * @param rateLimitPerMinute    maximum dashboard views per user per minute
 */
@ConfigurationProperties("gateway.dashboard")
@Validated
public record DashboardProperties(
		@Min(1) @Max(60) @DefaultValue("5") int resultTtlMinutes,
		@Min(1) @Max(60) @DefaultValue("5") int graceOverlapMinutes,
		@Min(1) @Max(90) @DefaultValue("7") int defaultWindowDays,
		@Min(1) @Max(120) @DefaultValue("10") int statementTimeoutSeconds,
		@Min(1) @Max(10_000) @DefaultValue("30") int rateLimitPerMinute
) {

	/**
	 * The documented defaults, mirroring the {@link DefaultValue} annotations.
	 */
	public static final DashboardProperties DEFAULTS = new DashboardProperties(5, 5, 7, 10, 30);
}
