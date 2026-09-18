package io.github.kxng0109.cacherelay.mcp.hitl;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * List-safe pending-approval summary: identifying metadata without the invocation
 * arguments (full args stay on the per-token detail endpoint).
 *
 * @param tokenId    hex token id of the suspended invocation
 * @param toolName   namespaced tool name
 * @param serverName upstream server name
 * @param ownerId    owning tenant
 * @param keyName    virtual key name that invoked the tool
 * @param createdAt  suspension timestamp (ISO-8601)
 * @param expiresAt  suspension expiry (ISO-8601)
 */
@Schema(name = "PendingApprovalSummary", description = "Pending approval without arguments")
public record PendingApprovalSummary(
		@Schema(description = "Hex token id", example = "9f8e7d6c5b4a3210")
		String tokenId,

		@Schema(description = "Namespaced tool name", example = "postgres__run_query")
		String toolName,

		@Schema(description = "Upstream server name", example = "postgres")
		String serverName,

		@Schema(description = "Owning tenant")
		String ownerId,

		@Schema(description = "Invoking virtual key name")
		String keyName,

		@Schema(description = "Suspension timestamp (ISO-8601)")
		String createdAt,

		@Schema(description = "Suspension expiry (ISO-8601)")
		String expiresAt
) {
}
