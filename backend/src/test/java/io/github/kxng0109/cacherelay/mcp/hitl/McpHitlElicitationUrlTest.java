package io.github.kxng0109.cacherelay.mcp.hitl;

import io.github.kxng0109.cacherelay.mcp.config.McpGatewayProperties;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.StringRedisTemplate;
import tools.jackson.databind.ObjectMapper;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

@DisplayName("MCP-B33 elicitation approval URL")
@SuppressWarnings("DataFlowIssue")
class McpHitlElicitationUrlTest {

	private McpHitlSuspensionEngine engine(McpGatewayProperties properties) {
		return new McpHitlSuspensionEngine(
				properties,
				mock(McpAeadResumptionTokenService.class),
				mock(StringRedisTemplate.class),
				new ObjectMapper()
		);
	}

	@Test
	@DisplayName("MCP-B33: configured base URL yields an absolute elicitation link")
	void absoluteUrlWhenConfigured() {
		McpGatewayProperties properties = new McpGatewayProperties();
		properties.setPublicBaseUrl("https://gw.example.com/");

		assertThat(engine(properties).elicitationApprovalUrl("tok-1"))
				.isEqualTo("https://gw.example.com/v1/admin/mcp/approvals/tok-1");
	}

	@Test
	@DisplayName("MCP-B33: blank base URL keeps the relative path")
	void relativePathWhenUnconfigured() {
		McpGatewayProperties properties = new McpGatewayProperties();
		properties.setPublicBaseUrl("");

		assertThat(engine(properties).elicitationApprovalUrl("tok-1"))
				.isEqualTo("/v1/admin/mcp/approvals/tok-1");
	}

	@Test
	@DisplayName("MCP-B33: null base URL keeps the relative path")
	void relativePathWhenBaseNull() {
		McpGatewayProperties properties = new McpGatewayProperties();
		properties.setPublicBaseUrl(null);

		assertThat(engine(properties).elicitationApprovalUrl("tok-1"))
				.isEqualTo("/v1/admin/mcp/approvals/tok-1");
	}

	@Test
	@DisplayName("MCP-B33: base URL without trailing slash needs no stripping")
	void absoluteUrlWithoutTrailingSlash() {
		McpGatewayProperties properties = new McpGatewayProperties();
		properties.setPublicBaseUrl("https://gw.example.com");

		assertThat(engine(properties).elicitationApprovalUrl("tok-1"))
				.isEqualTo("https://gw.example.com/v1/admin/mcp/approvals/tok-1");
	}
}
