package io.github.kxng0109.cacherelay.mcp.security;

/**
 * Thrown when a guardrail scan cannot be evaluated. Callers fail closed: a
 * tool invocation that cannot be screened must never execute.
 */
public class GuardrailScanException extends RuntimeException {

	/**
	 * Creates the failure.
	 *
	 * @param stage   failing scan stage (e.g. {@code arguments}), never {@code null}
	 * @param cause   underlying failure, possibly {@code null}
	 */
	public GuardrailScanException(String stage, Throwable cause) {
		super("Guardrail scan failed closed at stage: " + stage, cause);
	}
}
