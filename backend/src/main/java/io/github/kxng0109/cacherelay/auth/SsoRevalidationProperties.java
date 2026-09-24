package io.github.kxng0109.cacherelay.auth;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.validation.annotation.Validated;

/**
 * Revalidation sweep ceilings bound from {@code gateway.sso.revalidation.*}.
 *
 * <p>Two ShedLock single-holder jobs (hot cadence for recently verified
 * accounts, nightly for dormant ones) re-check IdP account state and revoke
 * on positive disabled signals only. Transport failures never revoke.</p>
 *
 * @param enabled          master switch for both jobs
 * @param hotCron          hot sweep schedule (Spring cron, seconds first)
 * @param coldCron         cold sweep schedule (Spring cron, seconds first)
 * @param batchSize        users claimed per tick
 * @param hotStaleMinutes  hot staleness threshold
 * @param coldStaleHours   cold staleness threshold
 */
@ConfigurationProperties("gateway.sso.revalidation")
@Validated
public record SsoRevalidationProperties(
		@DefaultValue("true") boolean enabled,
		@NotBlank @DefaultValue("0 */15 * * * *") String hotCron,
		@NotBlank @DefaultValue("0 0 2 * * *") String coldCron,
		@Min(1) @Max(5000) @DefaultValue("200") int batchSize,
		@Min(1) @Max(10080) @DefaultValue("15") int hotStaleMinutes,
		@Min(1) @Max(8760) @DefaultValue("24") int coldStaleHours
) {

	/**
	 * The documented defaults, mirroring the {@link DefaultValue} annotations.
	 */
	public static final SsoRevalidationProperties DEFAULTS =
			new SsoRevalidationProperties(true, "0 */15 * * * *", "0 0 2 * * *", 200, 15, 24);
}
