package io.github.kxng0109.cacherelay.admin;

import java.util.List;
import java.util.Locale;

import io.github.kxng0109.cacherelay.admin.dto.CreateModelRequest;
import io.github.kxng0109.cacherelay.admin.dto.ModelDefinitionResponse;
import io.github.kxng0109.cacherelay.admin.dto.ModelListResponse;
import io.github.kxng0109.cacherelay.admin.dto.ProviderStepRequest;
import io.github.kxng0109.cacherelay.admin.dto.UpdateModelRequest;
import io.github.kxng0109.cacherelay.config.OpenApiConfig;
import io.github.kxng0109.cacherelay.contracts.ModelAlias;
import io.github.kxng0109.cacherelay.contracts.ProviderRef;
import io.github.kxng0109.cacherelay.model.ModelAliasRegistry;
import io.github.kxng0109.cacherelay.model.ModelDefinition;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * REST controller for administrative management of model aliases under
 * {@code /v1/admin/models}. Database-managed aliases are created, replaced,
 * and deleted here; file-bound aliases from configuration are listed with a
 * {@code "file"} source flag and stay read-only. Every mutation is audited by
 * {@link AdminAuthFilter} with the admin actor and outcome.
 */
@RestController
@RequestMapping("/v1/admin/models")
@RequiredArgsConstructor
@Tag(name = "Admin - Model Management", description = "Creating, replacing, and deleting database-managed model aliases; file-bound aliases are read-only")
public class AdminModelController {

	private final ModelAliasRegistry registry;

	/**
	 * Lists every effective model alias with its origin.
	 *
	 * @return HTTP 200 OK with file-bound aliases first
	 */
	@Operation(
			summary = "List model aliases",
			description = "Lists every effective model alias. Entries carry a source flag so callers can tell read-only file-bound aliases apart from editable database-managed ones.",
			security = {
					@SecurityRequirement(name = OpenApiConfig.SCHEME_ADMIN_KEY_HEADER),
					@SecurityRequirement(name = OpenApiConfig.SCHEME_ADMIN_BEARER)
			}
	)
	@ApiResponses(value = {
			@ApiResponse(responseCode = "200", description = "Aliases listed successfully",
					content = @Content(mediaType = "application/json",
							schema = @Schema(implementation = ModelListResponse.class))),
			@ApiResponse(responseCode = "401", description = "Unauthorized: Master Admin key missing or incorrect")
	})
	@GetMapping
	public ResponseEntity<ModelListResponse> listModels() {
		List<ModelDefinitionResponse> models = registry.list().stream()
				.map(AdminModelController::toResponse)
				.toList();
		return ResponseEntity.ok(new ModelListResponse(models));
	}

	/**
	 * Creates a database-managed model alias.
	 *
	 * @param request alias name, provider chain, and strategy
	 * @return HTTP 201 Created with the created alias
	 */
	@Operation(
			summary = "Create model alias",
			description = "Creates a database-managed model alias and republishes the routing catalog. Names colliding with file-bound aliases are rejected as read-only.",
			security = {
					@SecurityRequirement(name = OpenApiConfig.SCHEME_ADMIN_KEY_HEADER),
					@SecurityRequirement(name = OpenApiConfig.SCHEME_ADMIN_BEARER)
			}
	)
	@ApiResponses(value = {
			@ApiResponse(responseCode = "201", description = "Model alias created successfully",
					content = @Content(mediaType = "application/json",
							schema = @Schema(implementation = ModelDefinitionResponse.class))),
			@ApiResponse(responseCode = "400", description = "Invalid alias payload"),
			@ApiResponse(responseCode = "401", description = "Unauthorized"),
			@ApiResponse(responseCode = "409", description = "Alias already exists or is file-bound")
	})
	@PostMapping
	public ResponseEntity<ModelDefinitionResponse> createModel(@Valid @RequestBody CreateModelRequest request) {
		ModelAlias created = registry.create(request.name(), toChain(request.chain()), request.strategy());
		return ResponseEntity.status(HttpStatus.CREATED)
				.body(toResponse(request.name(), created, "database"));
	}

	/**
	 * Replaces the routing plan of a database-managed alias.
	 *
	 * @param name    client facing model name
	 * @param request replacement provider chain and strategy
	 * @return HTTP 200 OK with the replaced alias, or HTTP 404 Not Found
	 */
	@Operation(
			summary = "Replace model alias",
			description = "Replaces the routing plan of a database-managed alias. File-bound aliases cannot be replaced.",
			security = {
					@SecurityRequirement(name = OpenApiConfig.SCHEME_ADMIN_KEY_HEADER),
					@SecurityRequirement(name = OpenApiConfig.SCHEME_ADMIN_BEARER)
			}
	)
	@ApiResponses(value = {
			@ApiResponse(responseCode = "200", description = "Model alias replaced successfully",
					content = @Content(mediaType = "application/json",
							schema = @Schema(implementation = ModelDefinitionResponse.class))),
			@ApiResponse(responseCode = "400", description = "Invalid alias payload"),
			@ApiResponse(responseCode = "401", description = "Unauthorized"),
			@ApiResponse(responseCode = "404", description = "Alias not found"),
			@ApiResponse(responseCode = "409", description = "Alias is file-bound and read-only")
	})
	@PutMapping("/{name}")
	public ResponseEntity<ModelDefinitionResponse> updateModel(
			@Parameter(description = "Client facing model name", example = "fast-gpt")
			@PathVariable("name") String name,
			@Valid @RequestBody UpdateModelRequest request
	) {
		ModelAlias updated = registry.update(name, toChain(request.chain()), request.strategy());
		return ResponseEntity.ok(toResponse(name, updated, "database"));
	}

	/**
	 * Deletes a database-managed alias.
	 *
	 * @param name client facing model name
	 * @return HTTP 204 No Content, or HTTP 404 Not Found
	 */
	@Operation(
			summary = "Delete model alias",
			description = "Permanently deletes a database-managed alias and republishes the routing catalog. File-bound aliases cannot be deleted.",
			security = {
					@SecurityRequirement(name = OpenApiConfig.SCHEME_ADMIN_KEY_HEADER),
					@SecurityRequirement(name = OpenApiConfig.SCHEME_ADMIN_BEARER)
			}
	)
	@ApiResponses(value = {
			@ApiResponse(responseCode = "204", description = "Model alias deleted successfully"),
			@ApiResponse(responseCode = "401", description = "Unauthorized"),
			@ApiResponse(responseCode = "404", description = "Alias not found"),
			@ApiResponse(responseCode = "409", description = "Alias is file-bound and read-only")
	})
	@DeleteMapping("/{name}")
	public ResponseEntity<Void> deleteModel(
			@Parameter(description = "Client facing model name", example = "fast-gpt")
			@PathVariable("name") String name
	) {
		registry.delete(name);
		return ResponseEntity.noContent().build();
	}

	private static ModelDefinitionResponse toResponse(ModelDefinition definition) {
		return new ModelDefinitionResponse(
				definition.name(),
				definition.chain(),
				definition.strategy(),
				definition.source().name().toLowerCase(Locale.ROOT));
	}

	private static ModelDefinitionResponse toResponse(String name, ModelAlias alias, String source) {
		return new ModelDefinitionResponse(name, alias.chain(), alias.strategy(), source);
	}

	private static List<ProviderRef> toChain(List<ProviderStepRequest> steps) {
		return steps.stream()
				.map(step -> new ProviderRef(step.providerName(), step.modelOverride()))
				.toList();
	}
}
