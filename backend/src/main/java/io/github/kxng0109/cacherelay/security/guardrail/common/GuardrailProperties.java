package io.github.kxng0109.cacherelay.security.guardrail.common;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Configuration properties for real-time guardrails and compliance engines.
 */
@ConfigurationProperties(prefix = "gateway.guardrails")
public class GuardrailProperties {

	private GuardrailMode mode = GuardrailMode.ENFORCE;
	private boolean secretScanningEnabled = true;
	private boolean piiAnonymizationEnabled = true;
	private boolean promptInjectionDefenseEnabled = true;
	private boolean streamingValidationEnabled = true;
	private String killSwitchMode = "TERMINATE_WITH_ERROR";
	private boolean systemPromptExfiltrationDefenseEnabled = true;
	private boolean dataResidencyEnabled = true;
	private String defaultResidencyPolicy = "STRICT_SOVEREIGN";
	private boolean vendorScreeningEnabled = false;

	public GuardrailMode getMode() {
		return mode;
	}

	public void setMode(GuardrailMode mode) {
		this.mode = mode == null ? GuardrailMode.ENFORCE : mode;
	}

	public boolean isSecretScanningEnabled() {
		return secretScanningEnabled;
	}

	public void setSecretScanningEnabled(boolean secretScanningEnabled) {
		this.secretScanningEnabled = secretScanningEnabled;
	}

	public boolean isPiiAnonymizationEnabled() {
		return piiAnonymizationEnabled;
	}

	public void setPiiAnonymizationEnabled(boolean piiAnonymizationEnabled) {
		this.piiAnonymizationEnabled = piiAnonymizationEnabled;
	}

	public boolean isPromptInjectionDefenseEnabled() {
		return promptInjectionDefenseEnabled;
	}

	public void setPromptInjectionDefenseEnabled(boolean promptInjectionDefenseEnabled) {
		this.promptInjectionDefenseEnabled = promptInjectionDefenseEnabled;
	}

	public boolean isStreamingValidationEnabled() {
		return streamingValidationEnabled;
	}

	public void setStreamingValidationEnabled(boolean streamingValidationEnabled) {
		this.streamingValidationEnabled = streamingValidationEnabled;
	}

	public String getKillSwitchMode() {
		return killSwitchMode;
	}

	public void setKillSwitchMode(String killSwitchMode) {
		this.killSwitchMode = killSwitchMode == null ? "TERMINATE_WITH_ERROR" : killSwitchMode;
	}

	public boolean isSystemPromptExfiltrationDefenseEnabled() {
		return systemPromptExfiltrationDefenseEnabled;
	}

	public void setSystemPromptExfiltrationDefenseEnabled(boolean systemPromptExfiltrationDefenseEnabled) {
		this.systemPromptExfiltrationDefenseEnabled = systemPromptExfiltrationDefenseEnabled;
	}

	public boolean isDataResidencyEnabled() {
		return dataResidencyEnabled;
	}

	public void setDataResidencyEnabled(boolean dataResidencyEnabled) {
		this.dataResidencyEnabled = dataResidencyEnabled;
	}

	/**
	 * Whether third-party vendor screening runs after the local engines.
	 * Default off: enabling it sends payloads outside the boundary, so it
	 * requires an explicit operator decision plus vendor residency scoping.
	 *
	 * @return whether vendor screening is enabled
	 */
	public boolean isVendorScreeningEnabled() {
		return vendorScreeningEnabled;
	}

	/**
	 * Sets third-party vendor screening.
	 *
	 * @param vendorScreeningEnabled whether vendor screening runs
	 */
	public void setVendorScreeningEnabled(boolean vendorScreeningEnabled) {
		this.vendorScreeningEnabled = vendorScreeningEnabled;
	}

	public String getDefaultResidencyPolicy() {
		return defaultResidencyPolicy;
	}

	public void setDefaultResidencyPolicy(String defaultResidencyPolicy) {
		this.defaultResidencyPolicy = defaultResidencyPolicy == null ? "STRICT_SOVEREIGN" : defaultResidencyPolicy;
	}
}
