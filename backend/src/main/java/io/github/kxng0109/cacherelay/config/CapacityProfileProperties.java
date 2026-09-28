package io.github.kxng0109.cacherelay.config;

import java.util.Set;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Deployment capacity profile, bound from {@code cacherelay.capacity.*}.
 *
 * <p>2 vCPU / 2 GB is the simulation floor, not a configuration ceiling:
 * floor-tuned values are profile-driven, never hardcoded — caps enforce,
 * weights steer. {@code floor} carries the conservative defaults baked into
 * every knob; larger hardware raises the explicit knobs (SSE ceilings, Hikari
 * pool, ledger executor, JVM flags) and declares {@code standard} or
 * {@code high} here so the startup capacity report names the intent.
 * {@code instanceWeight} steers fronting load balancers (no LB ships in this
 * repo; compose/k8s overlays consume it).</p>
 *
 * @param profile         one of {@code floor}, {@code standard}, {@code high}
 * @param instanceWeight  relative load share for this instance ({@code >= 1})
 */
@ConfigurationProperties("cacherelay.capacity")
public record CapacityProfileProperties(
		@DefaultValue("floor") String profile,
		@DefaultValue("1") int instanceWeight
) {

	/**
	 * Valid profile names.
	 */
	public static final Set<String> PROFILES = Set.of("floor", "standard", "high");

	/**
	 * The documented defaults, mirroring the {@link DefaultValue} annotations.
	 */
	public static final CapacityProfileProperties DEFAULTS = new CapacityProfileProperties("floor", 1);

	public CapacityProfileProperties {
		if (!PROFILES.contains(profile)) {
			throw new IllegalArgumentException("Unknown capacity profile: " + profile
					+ " (expected one of floor, standard, high)");
		}
		if (instanceWeight < 1) {
			throw new IllegalArgumentException("instanceWeight must be >= 1");
		}
	}
}
