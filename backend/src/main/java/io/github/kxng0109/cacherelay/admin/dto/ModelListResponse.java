package io.github.kxng0109.cacherelay.admin.dto;

import java.util.List;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * Envelope for model alias listings.
 *
 * @param models effective aliases, file-bound first
 */
@Schema(name = "ModelList", description = "Effective model aliases")
public record ModelListResponse(
		@Schema(description = "Effective aliases, file-bound first")
		List<ModelDefinitionResponse> models
) {
}
