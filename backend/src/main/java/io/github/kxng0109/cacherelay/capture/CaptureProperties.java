package io.github.kxng0109.cacherelay.capture;

import java.util.List;
import java.util.Optional;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import org.jspecify.annotations.Nullable;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.validation.annotation.Validated;

/**
 * Prompt/output capture ceilings bound from {@code gateway.capture.*}.
 *
 * <p>Capture is off by default and computes nothing when disabled. When
 * enabled, per-mille hash sampling plus per-owner/key full-fidelity allowlist
 * entries decide per request on the hot path; a token bucket caps persists
 * per second fleet-wide per instance. Bodies persist only redacted to hourly
 * local JSONL segments with sidecar manifests; rows expire by TTL.</p>
 *
 * @param enabled              master switch, off by default
 * @param samplePerMille       hash-sampled requests per mille (0 through 1000)
 * @param maxPersistsPerSecond persist ceiling per instance
 * @param captureDir           local segment directory
 * @param maxCharsPerField     per-field capture cap before redaction
 * @param maxSegmentBytes      segment rotation size
 * @param defaultTtlDays       default row TTL
 * @param strictTtlDays        strict jurisdiction TTL
 * @param allowlist            full-fidelity owner/key rules
 */
@ConfigurationProperties("gateway.capture")
@Validated
public record CaptureProperties(
		@DefaultValue("false") boolean enabled,
		@Min(0) @Max(1000) @DefaultValue("1") int samplePerMille,
		@Min(1) @Max(100_000) @DefaultValue("100") int maxPersistsPerSecond,
		@NotBlank @DefaultValue("./data/capture") String captureDir,
		@Min(1024) @Max(1_000_000) @DefaultValue("32768") int maxCharsPerField,
		@Min(1_048_576) @DefaultValue("268435456") long maxSegmentBytes,
		@Min(1) @Max(3650) @DefaultValue("90") int defaultTtlDays,
		@Min(1) @Max(3650) @DefaultValue("7") int strictTtlDays,
		@Valid List<CaptureRule> allowlist
) {

	/**
	 * The documented defaults, mirroring the {@link DefaultValue} annotations.
	 */
	public static final CaptureProperties DEFAULTS = new CaptureProperties(false, 1, 100,
			"./data/capture", 32768, 268435456L, 90, 7, List.of());

	/**
	 * Canonical constructor normalizing absent lists to empty.
	 */
	public CaptureProperties {
		allowlist = allowlist == null ? List.of() : List.copyOf(allowlist);
	}

	/**
	 * Full-fidelity capture rule for one owner id or key hash.
	 *
	 * @param ownerOrKey  owner id or hex key hash, never blank
	 * @param jurisdiction jurisdiction label selecting segment prefix and TTL
	 * @param ttlDays     row TTL, or {@code null} for the default
	 * @param strict      whether the strict jurisdiction TTL applies
	 */
	public record CaptureRule(
			@NotBlank String ownerOrKey,
			@DefaultValue("default") String jurisdiction,
			@Min(1) @Max(3650) Integer ttlDays,
			@DefaultValue("false") boolean strict
	) {

		/**
		 * Canonical constructor normalizing absent values to safe defaults.
		 */
		public CaptureRule {
			jurisdiction = jurisdiction == null || jurisdiction.isBlank() ? "default"
					: jurisdiction;
		}

		/**
		 * Effective row TTL against the enclosing defaults.
		 *
		 * @param defaultTtl default days
		 * @param strictTtl  strict days
		 * @return effective days, never below one
		 */
		public int effectiveTtlDays(int defaultTtl, int strictTtl) {
			int ttl = ttlDays != null ? ttlDays : defaultTtl;
			if (strict) {
				ttl = Math.min(ttl, strictTtl);
			}
			return Math.max(1, Math.min(ttl, defaultTtl));
		}
	}

	/**
	 * Finds the allowlist rule for one owner id or key hash.
	 *
	 * @param ownerOrKey owner id or key hash, or {@code null}
	 * @return the rule when listed
	 */
	public Optional<CaptureRule> ruleFor(@Nullable String ownerOrKey) {
		if (ownerOrKey == null) {
			return Optional.empty();
		}
		for (CaptureRule rule : allowlist) {
			if (rule.ownerOrKey().equals(ownerOrKey)) {
				return Optional.of(rule);
			}
		}
		return Optional.empty();
	}
}
