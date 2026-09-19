package io.github.kxng0109.cacherelay.mcp.security;

import io.github.kxng0109.cacherelay.security.guardrail.secret.IngressSecretScanner;
import io.github.kxng0109.cacherelay.security.guardrail.secret.SecretScanResult;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.HexFormat;
import java.util.regex.Pattern;

/**
 * Ingress parameter scanner and egress output sanitizer for Model Context Protocol tool executions.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class McpGuardrailScanner {

	private static final SecureRandom RANDOM = new SecureRandom();
	private static final Pattern INDIRECT_INJECTION_MARKERS = Pattern.compile(
			"(?i)(?:ignore\\s+(?:all\\s+)?previous\\s+instructions|disregard\\s+(?:all\\s+)?prior\\s+prompts|<\\|im_start\\|>|\\[SYSTEM\\]:)"
	);

	private final IngressSecretScanner secretScanner;
	private final ObjectMapper objectMapper;

	/**
	 * Scans tool argument payload for leaked credentials, API keys, and high-entropy secrets.
	 *
	 * @param arguments tool arguments JSON node
	 * @return scan result indicating whether a secret was detected
	 */
	public SecretScanResult scanArguments(@Nullable JsonNode arguments) {
		if (arguments == null || arguments.isNull() || arguments.isEmpty()) {
			return SecretScanResult.clean();
		}
		try {
			String serialized = objectMapper.writeValueAsString(arguments);
			byte[] bytes = serialized.getBytes(StandardCharsets.UTF_8);
			return secretScanner.scan(bytes, serialized);
		} catch (Exception e) {
			log.warn("Error serializing tool arguments for guardrail scan: {}", e.getMessage());
			return SecretScanResult.clean();
		}
	}

	/**
	 * Wraps tool output content in a secure, nonced XML delimiter block to defend against indirect prompt injection.
	 *
	 * <p>Envelope-breakout hardening (SEC-09): the tool name is XML-attribute-escaped so a
	 * crafted name cannot break out of the {@code name} attribute, and the closing tag
	 * carries the same nonce ({@code </tool_result nonce="…">}) so a verbatim
	 * {@code </tool_result>} inside untrusted output does not terminate the envelope. The
	 * text itself is preserved verbatim (consumers are LLMs, not XML parsers). Outputs
	 * containing the nonce itself are NOT rejected: a 16-hex-char coincidence (e.g. inside
	 * UUIDs/hashes) would false-positive, while the nonce-bound closer already defeats
	 * practical forgery (the attacker cannot predict the nonce).</p>
	 *
	 * @param toolName namespaced tool name
	 * @param rawText  raw output string produced by the tool
	 * @return nonced delimiter block
	 */
	public String wrapToolOutputWithNonce(String toolName, String rawText) {
		byte[] nonceBytes = new byte[8];
		RANDOM.nextBytes(nonceBytes);
		String nonce = HexFormat.of().formatHex(nonceBytes);

		String safeName = escapeXmlAttribute(toolName == null ? "" : toolName);
		String safeText = rawText == null ? "" : rawText;
		return "<tool_result name=\"" + safeName + "\" nonce=\"" + nonce + "\" context=\"EXTERNAL_UNTRUSTED_DATA\">\n"
				+ safeText + "\n"
				+ "</tool_result nonce=\"" + nonce + "\">";
	}

	/**
	 * Escapes a value for an XML double-quoted attribute context.
	 *
	 * @param value raw value, possibly {@code null}
	 * @return escaped value
	 */
	static String escapeXmlAttribute(String value) {
		if (value == null) {
			return "";
		}
		StringBuilder escaped = new StringBuilder(value.length());
		for (int i = 0; i < value.length(); i++) {
			char c = value.charAt(i);
			switch (c) {
				case '&' -> escaped.append("&amp;");
				case '"' -> escaped.append("&quot;");
				case '<' -> escaped.append("&lt;");
				case '>' -> escaped.append("&gt;");
				default -> escaped.append(c);
			}
		}
		return escaped.toString();
	}

	/**
	 * Inspects tool execution output text for indirect prompt injection attack markers.
	 *
	 * @param outputText tool output content
	 * @return true if adversarial injection patterns are detected
	 */
	public boolean containsIndirectPromptInjection(@Nullable String outputText) {
		if (outputText == null || outputText.isBlank()) {
			return false;
		}
		return INDIRECT_INJECTION_MARKERS.matcher(outputText).find();
	}
}
