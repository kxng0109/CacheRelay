package io.github.kxng0109.cacherelay.security.guardrail.vendor;

/**
 * Third-party safety screening for one ingress payload.
 *
 * <p>Implementations call out to a vendor screening API with a bounded timeout
 * and translate the response into a {@link VendorVerdict}. Any failure —
 * timeout, transport error, malformed response — must throw: the filter fails
 * closed to the local deterministic engines, never open. Implementations must
 * never log raw payload text (verdicts and latency only) so vendor logging
 * cannot defeat the PII vault lifecycle.</p>
 */
public interface GuardrailVendorClient {

	/**
	 * Screens one ingress payload.
	 *
	 * @param textPayload decoded request body, never {@code null}
	 * @return vendor verdict, never {@code null}
	 * @throws RuntimeException on any vendor failure (timeout, transport, malformed)
	 */
	VendorVerdict screen(String textPayload);

	/**
	 * Vendor identifier for audit and spend attribution.
	 *
	 * @return vendor id (e.g. {@code bedrock}), never {@code null}
	 */
	String vendorId();
}
