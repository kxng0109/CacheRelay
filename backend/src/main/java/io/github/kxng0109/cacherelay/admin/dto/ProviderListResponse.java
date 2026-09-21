package io.github.kxng0109.cacherelay.admin.dto;

import java.util.List;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * Envelope for the configured provider listing.
 *
 * @param providers configured providers ordered by name
 */
@Schema(name = "ProviderList", description = "Configured upstream providers with live routing health")
public record ProviderListResponse(
		@Schema(description = "Configured providers ordered by name")
		List<ProviderStatusResponse> providers
) {
}
