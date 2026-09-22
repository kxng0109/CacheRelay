package io.github.kxng0109.cacherelay.admin;

import java.time.Instant;

import io.github.kxng0109.cacherelay.admin.dto.QualityResponse;
import io.github.kxng0109.cacherelay.admin.dto.SetQualityRequest;
import io.github.kxng0109.cacherelay.config.OpenApiConfig;
import io.github.kxng0109.cacherelay.ledger.ModelQualityCatalog;
import io.github.kxng0109.cacherelay.ledger.ModelQualityEntity;
import io.github.kxng0109.cacherelay.ledger.ModelQualityRepository;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/**
 * REST controller curating model quality tiers under
 * {@code /v1/admin/model-quality}. Tiers live in their own table so the daily
 * pricing sync can never clobber them; rating a model here annotates the
 * model-catalog read side and feeds future routing policy.
 */
@RestController
@RequestMapping("/v1/admin/model-quality")
@RequiredArgsConstructor
@Tag(name = "Admin - Model Quality", description = "Curate model quality tiers with benchmark references")
public class AdminModelQualityController {

	private static final int MAX_MODEL_ID_LENGTH = 128;

	private static final String MODEL_ID_PATTERN = "[\\w./:-]+";

	private static final int MAX_REFS_LENGTH = 2000;

	private static final String REFS_PATTERN = "[\\w\\s:;@.,+\\-/%()]*";

	private final ModelQualityRepository repository;

	private final ModelQualityCatalog catalog;

	/**
	 * Reads the current rating of one model.
	 *
	 * @param modelId exact model id
	 * @return HTTP 200 OK with the rating, or 404 when the model is unrated
	 */
	@Operation(
			summary = "Read a model quality rating",
			description = "Reads the curated quality tier of one model.",
			security = {
					@SecurityRequirement(name = OpenApiConfig.SCHEME_ADMIN_KEY_HEADER),
					@SecurityRequirement(name = OpenApiConfig.SCHEME_ADMIN_BEARER)
			}
	)
	@ApiResponses(value = {
			@ApiResponse(responseCode = "200", description = "Current rating",
					content = @Content(mediaType = "application/json",
							schema = @Schema(implementation = QualityResponse.class))),
			@ApiResponse(responseCode = "401", description = "Unauthorized: Master Admin key missing or incorrect"),
			@ApiResponse(responseCode = "404", description = "Model is unrated")
	})
	@GetMapping("/{modelId}")
	public ResponseEntity<QualityResponse> getQuality(
			@Parameter(description = "Exact model id", example = "gpt-5.6-luna")
			@PathVariable("modelId") String modelId) {
		validateModelId(modelId);
		return repository.findById(modelId)
				.map(entity -> ResponseEntity.ok(QualityResponse.from(entity)))
				.orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "model is unrated"));
	}

	/**
	 * Rates or re-rates one model.
	 *
	 * @param modelId exact model id
	 * @param request tier and optional benchmark references
	 * @return HTTP 200 OK with the stored rating
	 */
	@Operation(
			summary = "Rate a model",
			description = "Creates or replaces the quality tier of one model with optional benchmark references.",
			security = {
					@SecurityRequirement(name = OpenApiConfig.SCHEME_ADMIN_KEY_HEADER),
					@SecurityRequirement(name = OpenApiConfig.SCHEME_ADMIN_BEARER)
			}
	)
	@ApiResponses(value = {
			@ApiResponse(responseCode = "200", description = "Stored rating",
					content = @Content(mediaType = "application/json",
							schema = @Schema(implementation = QualityResponse.class))),
			@ApiResponse(responseCode = "400", description = "Invalid model id or references"),
			@ApiResponse(responseCode = "401", description = "Unauthorized: Master Admin key missing or incorrect")
	})
	@PutMapping("/{modelId}")
	public ResponseEntity<QualityResponse> setQuality(
			@Parameter(description = "Exact model id", example = "gpt-5.6-luna")
			@PathVariable("modelId") String modelId,
			@Parameter(description = "Tier and benchmark references")
			@RequestBody SetQualityRequest request) {
		validateModelId(modelId);
		validateRefs(request == null ? null : request.benchmarkRefs());
		if (request == null || request.tier() == null) {
			throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "tier must not be null");
		}
		String refs = request.benchmarkRefs() == null || request.benchmarkRefs().isBlank()
				? null
				: request.benchmarkRefs();
		ModelQualityEntity stored = repository.save(
				new ModelQualityEntity(modelId, request.tier(), refs, Instant.now()));
		catalog.invalidate();
		return ResponseEntity.ok(QualityResponse.from(stored));
	}

	/**
	 * Removes the rating of one model, returning it to unrated.
	 *
	 * @param modelId exact model id
	 * @return HTTP 204 when removed, or 404 when the model is unrated
	 */
	@Operation(
			summary = "Unrate a model",
			description = "Deletes the quality rating of one model.",
			security = {
					@SecurityRequirement(name = OpenApiConfig.SCHEME_ADMIN_KEY_HEADER),
					@SecurityRequirement(name = OpenApiConfig.SCHEME_ADMIN_BEARER)
			}
	)
	@ApiResponses(value = {
			@ApiResponse(responseCode = "204", description = "Rating removed"),
			@ApiResponse(responseCode = "401", description = "Unauthorized: Master Admin key missing or incorrect"),
			@ApiResponse(responseCode = "404", description = "Model is unrated")
	})
	@DeleteMapping("/{modelId}")
	public ResponseEntity<Void> deleteQuality(
			@Parameter(description = "Exact model id", example = "gpt-5.6-luna")
			@PathVariable("modelId") String modelId) {
		validateModelId(modelId);
		if (!repository.existsById(modelId)) {
			throw new ResponseStatusException(HttpStatus.NOT_FOUND, "model is unrated");
		}
		repository.deleteById(modelId);
		catalog.invalidate();
		return ResponseEntity.noContent().build();
	}

	private static void validateModelId(String modelId) {
		if (modelId == null || modelId.length() > MAX_MODEL_ID_LENGTH
				|| !modelId.matches(MODEL_ID_PATTERN)) {
			throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "invalid model id");
		}
	}

	private static void validateRefs(String refs) {
		if (refs != null && (refs.length() > MAX_REFS_LENGTH || !refs.matches(REFS_PATTERN))) {
			throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "invalid benchmarkRefs");
		}
	}
}
