package io.github.kxng0109.cacherelay.admin.dto;

import java.util.List;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * Envelope for a model catalog search.
 *
 * @param models matching models ordered by model id
 */
@Schema(name = "ModelCatalog", description = "Matching models from the pricing catalog")
public record ModelCatalogResponse(
		@Schema(description = "Matching models ordered by model id")
		List<ModelCatalogEntryResponse> models
) {
}
