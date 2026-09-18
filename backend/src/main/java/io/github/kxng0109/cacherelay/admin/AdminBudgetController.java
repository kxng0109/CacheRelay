package io.github.kxng0109.cacherelay.admin;

import io.github.kxng0109.cacherelay.admin.dto.BudgetBalanceResponse;
import io.github.kxng0109.cacherelay.admin.dto.BudgetHoldResponse;
import io.github.kxng0109.cacherelay.admin.dto.BudgetResponse;
import io.github.kxng0109.cacherelay.admin.dto.CreateBudgetRequest;
import io.github.kxng0109.cacherelay.admin.dto.UpdateBudgetRequest;
import io.github.kxng0109.cacherelay.budget.BudgetService;
import io.github.kxng0109.cacherelay.budget.BudgetSettlement;
import io.github.kxng0109.cacherelay.config.OpenApiConfig;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

/**
 * REST controller for hard spend budgets under {@code /v1/admin/budgets}. Every mutation is audit-logged with actor and
 * before/after snapshots.
 */
@RestController
@RequestMapping("/v1/admin/budgets")
@RequiredArgsConstructor
@Tag(name = "Admin - Budgets", description = "Hard spend caps with live balances (key, team, org scopes)")
public class AdminBudgetController {

	private final BudgetService budgetService;
	private final BudgetSettlement budgetSettlement;

	@Operation(summary = "Create spend budget",
			description = "Creates a hard spend cap; duplicate (level, subject) is a 409.",
			security = {
					@SecurityRequirement(name = OpenApiConfig.SCHEME_ADMIN_KEY_HEADER),
					@SecurityRequirement(name = OpenApiConfig.SCHEME_ADMIN_BEARER)
			})
	@ApiResponses(value = {
			@ApiResponse(responseCode = "201", description = "Budget created"),
			@ApiResponse(responseCode = "400", description = "Validation failure"),
			@ApiResponse(responseCode = "401", description = "Unauthorized: Master Admin key missing or incorrect"),
			@ApiResponse(responseCode = "409", description = "Budget already exists for this level and subject")
	})
	@PostMapping
	public ResponseEntity<BudgetResponse> createBudget(@Valid @RequestBody CreateBudgetRequest request) {
		return ResponseEntity.status(HttpStatus.CREATED).body(BudgetResponse.from(budgetService.create(
				request.level(), request.subjectId(), request.minuteMicros(), request.monthMicros(),
				request.webhookUrl()
		)));
	}

	@Operation(summary = "List spend budgets",
			description = "Every hard spend cap ordered by level then subject.",
			security = {
					@SecurityRequirement(name = OpenApiConfig.SCHEME_ADMIN_KEY_HEADER),
					@SecurityRequirement(name = OpenApiConfig.SCHEME_ADMIN_BEARER)
			})
	@ApiResponses(value = {
			@ApiResponse(responseCode = "200", description = "Budget list"),
			@ApiResponse(responseCode = "401", description = "Unauthorized: Master Admin key missing or incorrect")
	})
	@GetMapping
	public ResponseEntity<List<BudgetResponse>> listBudgets() {
		return ResponseEntity.ok(budgetService.list().stream().map(BudgetResponse::from).toList());
	}

	@Operation(summary = "Read live balance",
			description = "Durable limits plus current minute/month spend (missing counters read as zero).",
			security = {
					@SecurityRequirement(name = OpenApiConfig.SCHEME_ADMIN_KEY_HEADER),
					@SecurityRequirement(name = OpenApiConfig.SCHEME_ADMIN_BEARER)
			})
	@ApiResponses(value = {
			@ApiResponse(responseCode = "200", description = "Balance snapshot"),
			@ApiResponse(responseCode = "401", description = "Unauthorized: Master Admin key missing or incorrect")
	})
	@GetMapping("/{level}/{subject}/balance")
	public ResponseEntity<BudgetBalanceResponse> balance(
			@Parameter(description = "KEY, TEAM, or ORG") @PathVariable String level,
			@Parameter(description = "Budget subject") @PathVariable String subject) {
		return ResponseEntity.ok(BudgetBalanceResponse.from(budgetService.balance(level, subject)));
	}

	@Operation(summary = "Read request hold-versus-settled",
			description = "Held micros, applied settled micros (null before settle), and lifecycle state for one request id. 404 once the hold record expires.",
			security = {
					@SecurityRequirement(name = OpenApiConfig.SCHEME_ADMIN_KEY_HEADER),
					@SecurityRequirement(name = OpenApiConfig.SCHEME_ADMIN_BEARER)
			})
	@ApiResponses(value = {
			@ApiResponse(responseCode = "200", description = "Hold view"),
			@ApiResponse(responseCode = "401", description = "Unauthorized: Master Admin key missing or incorrect"),
			@ApiResponse(responseCode = "404", description = "Hold expired or unknown")
	})
	@GetMapping("/holds/{requestId}")
	public ResponseEntity<BudgetHoldResponse> hold(
			@Parameter(description = "Request identifier") @PathVariable String requestId) {
		return budgetSettlement.readHold(requestId)
				.map(view -> ResponseEntity.ok(BudgetHoldResponse.from(view)))
				.orElse(ResponseEntity.notFound().build());
	}

	@Operation(summary = "Replace spend budget caps",
			description = "Replaces the money fields; concurrent edits fail loudly with 409.",
			security = {
					@SecurityRequirement(name = OpenApiConfig.SCHEME_ADMIN_KEY_HEADER),
					@SecurityRequirement(name = OpenApiConfig.SCHEME_ADMIN_BEARER)
			})
	@ApiResponses(value = {
			@ApiResponse(responseCode = "200", description = "Budget updated"),
			@ApiResponse(responseCode = "400", description = "Validation failure"),
			@ApiResponse(responseCode = "401", description = "Unauthorized: Master Admin key missing or incorrect"),
			@ApiResponse(responseCode = "404", description = "Budget not found"),
			@ApiResponse(responseCode = "409", description = "Budget changed concurrently")
	})
	@PutMapping("/{id}")
	public ResponseEntity<BudgetResponse> updateBudget(
			@Parameter(description = "Budget id") @PathVariable UUID id,
			@Valid @RequestBody UpdateBudgetRequest request) {
		return ResponseEntity.ok(BudgetResponse.from(budgetService.update(
				id, request.minuteMicros(), request.monthMicros(), request.webhookUrl())));
	}

	@Operation(summary = "Delete spend budget",
			description = "Deletes the cap and snapshots live spend into the audit trail so chargeback survives.",
			security = {
					@SecurityRequirement(name = OpenApiConfig.SCHEME_ADMIN_KEY_HEADER),
					@SecurityRequirement(name = OpenApiConfig.SCHEME_ADMIN_BEARER)
			})
	@ApiResponses(value = {
			@ApiResponse(responseCode = "204", description = "Budget deleted"),
			@ApiResponse(responseCode = "401", description = "Unauthorized: Master Admin key missing or incorrect"),
			@ApiResponse(responseCode = "404", description = "Budget not found")
	})
	@DeleteMapping("/{id}")
	public ResponseEntity<Void> deleteBudget(@Parameter(description = "Budget id") @PathVariable UUID id) {
		budgetService.delete(id);
		return ResponseEntity.noContent().build();
	}
}
