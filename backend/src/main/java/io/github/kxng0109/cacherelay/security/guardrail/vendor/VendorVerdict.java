package io.github.kxng0109.cacherelay.security.guardrail.vendor;

import java.time.Duration;

/**
 * Third-party safety screening verdict for one ingress payload.
 *
 * <p>Verdicts map onto the existing {@code ENFORCE}/{@code AUDIT_ONLY} fork:
 * flagged payloads block in ENFORCE and annotate in AUDIT_ONLY, exactly like
 * the deterministic stages.</p>
 *
 * @param flagged  whether the vendor flagged the payload
 * @param vendor   vendor identifier for audit and spend attribution (e.g. {@code bedrock})
 * @param reason   machine-readable detector or policy name, never {@code null} when flagged
 * @param latency  vendor round-trip time for spend/latency audit, never {@code null}
 */
public record VendorVerdict(
		boolean flagged,
		String vendor,
		String reason,
		Duration latency
) {
	/**
	 * Creates a clean verdict.
	 *
	 * @param vendor  vendor identifier, never {@code null}
	 * @param latency vendor round-trip time, never {@code null}
	 * @return clean verdict
	 */
	public static VendorVerdict clean(String vendor, Duration latency) {
		return new VendorVerdict(false, vendor, "clean", latency);
	}
}
