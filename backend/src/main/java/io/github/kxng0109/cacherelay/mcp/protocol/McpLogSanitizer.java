package io.github.kxng0109.cacherelay.mcp.protocol;

import org.jspecify.annotations.Nullable;

import java.util.regex.Pattern;

/**
 * Log-injection guard for MCP log lines (MCP-B30): tool names, tenant ids, rejection reasons, and
 * upstream exception text are attacker-influenced, so control characters (CR, LF, TAB, and the rest
 * of {@code \p{Cntrl}}) are folded to {@code _} before values reach any log statement. A forged
 * log line can otherwise spoof audit events or corrupt log pipelines.
 */
public final class McpLogSanitizer {

	private static final Pattern CONTROL_CHARS = Pattern.compile("\\p{Cntrl}");

	private McpLogSanitizer() {
	}

	/**
	 * Folds control characters in a log-bound value to underscores.
	 *
	 * @param value raw value, possibly {@code null}
	 * @return the value with every control character replaced, or "null" for {@code null}
	 */
	public static String safe(@Nullable String value) {
		if (value == null) {
			return "null";
		}
		return CONTROL_CHARS.matcher(value).replaceAll("_");
	}
}
