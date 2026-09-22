package io.github.kxng0109.cacherelay.admin;

import java.util.List;
import java.util.Optional;

import io.github.kxng0109.cacherelay.admin.dto.ModelCatalogEntryResponse;
import io.github.kxng0109.cacherelay.admin.dto.ModelCatalogResponse;
import io.github.kxng0109.cacherelay.config.OpenApiConfig;
import io.github.kxng0109.cacherelay.ledger.ModelPriceCatalog;
import io.github.kxng0109.cacherelay.ledger.ModelPricingEntry;
import io.github.kxng0109.cacherelay.ledger.ModelQualityCatalog;
import io.github.kxng0109.cacherelay.ledger.ModelQualityEntry;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/**
 * REST controller serving model suggestions from the pricing catalog under
 * {@code /v1/admin/model-catalog}. Admins use it to populate the model picker
 * when composing an alias chain step, seeing exact upstream model ids with
 * context windows and prices from the same catalog the ledger prices requests
 * with. The underlying snapshot is the one refreshed by the daily pricing sync,
 * so searches are served from memory.
 */
@RestController
@RequestMapping("/v1/admin/model-catalog")
@Tag(name = "Admin - Model Catalog", description = "Searchable model suggestions with context windows and pricing")
public class AdminModelCatalogController {

	private static final int DEFAULT_LIMIT = 50;

	private static final int MAX_LIMIT = 200;

	private static final int MAX_QUERY_LENGTH = 128;

	private static final int MAX_PROVIDER_LENGTH = 64;

	private final ModelPriceCatalog modelPriceCatalog;

	private final ModelQualityCatalog modelQualityCatalog;

	/**
	 * Creates the controller with both catalogs.
	 *
	 * @param modelPriceCatalog   pricing read side
	 * @param modelQualityCatalog quality read side
	 */
	@Autowired
	public AdminModelCatalogController(
			ModelPriceCatalog modelPriceCatalog,
			ModelQualityCatalog modelQualityCatalog) {
		this.modelPriceCatalog = modelPriceCatalog;
		this.modelQualityCatalog = modelQualityCatalog;
	}

	/**
	 * Creates the controller without quality annotations (tests).
	 *
	 * @param modelPriceCatalog pricing read side
	 */
	public AdminModelCatalogController(ModelPriceCatalog modelPriceCatalog) {
		this(modelPriceCatalog, null);
	}

	/**
	 * Searches the catalog for model suggestions.
	 *
	 * @param provider optional catalog provider filter such as {@code openai} or
	 *                 {@code together_ai}
	 * @param query    optional case-insensitive substring of the model id
	 * @param limit    maximum entries to return (1 through 200)
	 * @return HTTP 200 OK with matching models ordered by model id
	 */
	@Operation(
			summary = "Search the model catalog",
			description = "Searches the pricing catalog snapshot for model suggestions. Filter by catalog provider and/or a case-insensitive model id substring; results carry context windows and per-token prices.",
			security = {
					@SecurityRequirement(name = OpenApiConfig.SCHEME_ADMIN_KEY_HEADER),
					@SecurityRequirement(name = OpenApiConfig.SCHEME_ADMIN_BEARER)
			}
	)
	@ApiResponses(value = {
			@ApiResponse(responseCode = "200", description = "Catalog search results",
					content = @Content(mediaType = "application/json",
							schema = @Schema(implementation = ModelCatalogResponse.class))),
			@ApiResponse(responseCode = "400", description = "Invalid search parameters"),
			@ApiResponse(responseCode = "401", description = "Unauthorized: Master Admin key missing or incorrect")
	})
	@GetMapping
	public ResponseEntity<ModelCatalogResponse> searchCatalog(
			@Parameter(description = "Catalog provider filter, for example openai or together_ai",
					example = "openai")
			@RequestParam(value = "provider", required = false) String provider,
			@Parameter(description = "Case-insensitive substring of the model id", example = "gpt")
			@RequestParam(value = "q", required = false) String query,
			@Parameter(description = "Maximum entries to return (1 through 200)", example = "50")
			@RequestParam(value = "limit", defaultValue = "" + DEFAULT_LIMIT) int limit
	) {
		validate(provider, query, limit);
		List<ModelCatalogEntryResponse> models = modelPriceCatalog.search(provider, query, limit).stream()
				.map(this::toResponse)
				.toList();
		return ResponseEntity.ok(new ModelCatalogResponse(models));
	}

	private static void validate(String provider, String query, int limit) {
		if (limit < 1 || limit > MAX_LIMIT) {
			throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
					"limit must be between 1 and " + MAX_LIMIT);
		}
		if (provider != null && provider.length() > MAX_PROVIDER_LENGTH) {
			throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
					"provider must be at most " + MAX_PROVIDER_LENGTH + " characters");
		}
		if (query != null && query.length() > MAX_QUERY_LENGTH) {
			throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
					"q must be at most " + MAX_QUERY_LENGTH + " characters");
		}
	}

	private ModelCatalogEntryResponse toResponse(ModelPricingEntry entry) {
		String qualityTier = null;
		String benchmarkRefs = null;
		if (modelQualityCatalog != null) {
			Optional<ModelQualityEntry> quality = modelQualityCatalog.qualityOf(entry.modelId());
			if (quality.isPresent()) {
				qualityTier = quality.get().tier().name();
				benchmarkRefs = quality.get().benchmarkRefs();
			}
		}
		return new ModelCatalogEntryResponse(
				entry.modelId(),
				entry.provider(),
				entry.mode(),
				entry.inputCostPerToken(),
				entry.outputCostPerToken(),
				entry.cacheReadInputTokenCost(),
				entry.cacheCreationInputTokenCost(),
				entry.maxInputTokens(),
				entry.maxOutputTokens(),
				qualityTier,
				benchmarkRefs);
	}
}
