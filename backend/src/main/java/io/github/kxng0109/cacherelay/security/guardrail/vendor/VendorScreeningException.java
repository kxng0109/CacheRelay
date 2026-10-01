package io.github.kxng0109.cacherelay.security.guardrail.vendor;

import java.net.URI;

import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;

/**
 * Thrown when vendor screening flags a payload in ENFORCE mode.
 */
public class VendorScreeningException extends RuntimeException {

	private static final URI PROBLEM_TYPE = URI.create("https://cacherelay.io/errors/vendor-screening-rejection");

	private final VendorVerdict verdict;

	/**
	 * Creates the exception.
	 *
	 * @param verdict flagging verdict, never {@code null}
	 */
	public VendorScreeningException(VendorVerdict verdict) {
		super("Vendor screening flagged the payload: " + verdict.vendor() + "/"
				+ verdict.reason());
		this.verdict = verdict;
	}

	/**
	 * Returns the flagging verdict.
	 *
	 * @return verdict, never {@code null}
	 */
	public VendorVerdict verdict() {
		return verdict;
	}

	/**
	 * Builds an RFC 9457 compliant ProblemDetail object for serialization as application/problem+json.
	 *
	 * @param instancePath request URI instance
	 * @return ProblemDetail model
	 */
	public ProblemDetail toProblemDetail(String instancePath) {
		ProblemDetail problem = ProblemDetail.forStatusAndDetail(
				HttpStatus.UNPROCESSABLE_CONTENT,
				"Third-party screening flagged the payload; request denied."
		);
		problem.setType(PROBLEM_TYPE);
		problem.setTitle("Unprocessable Content - Vendor Screening Rejection");
		problem.setInstance(URI.create(instancePath));
		problem.setProperty("vendor", verdict.vendor());
		problem.setProperty("reason", verdict.reason());
		return problem;
	}
}
