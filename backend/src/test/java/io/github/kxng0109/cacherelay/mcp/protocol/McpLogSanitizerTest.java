package io.github.kxng0109.cacherelay.mcp.protocol;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("McpLogSanitizer")
@SuppressWarnings("DataFlowIssue")
class McpLogSanitizerTest {

	@Test
	@DisplayName("MCP-B30: control characters are folded, printable text survives")
	void foldsControlCharacters() {
		assertThat(McpLogSanitizer.safe("tool\nINJECTED\r\nline")).isEqualTo("tool_INJECTED__line");
		assertThat(McpLogSanitizer.safe("tab\there")).isEqualTo("tab_here");
		assertThat(McpLogSanitizer.safe("postgres__run_query")).isEqualTo("postgres__run_query");
		assertThat(McpLogSanitizer.safe(null)).isEqualTo("null");
		assertThat(McpLogSanitizer.safe("")).isEmpty();
	}
}
