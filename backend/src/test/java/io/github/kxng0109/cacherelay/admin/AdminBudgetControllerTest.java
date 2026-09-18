package io.github.kxng0109.cacherelay.admin;

import io.github.kxng0109.cacherelay.admin.dto.BudgetBalanceResponse;
import io.github.kxng0109.cacherelay.admin.dto.CreateBudgetRequest;
import io.github.kxng0109.cacherelay.admin.dto.UpdateBudgetRequest;
import io.github.kxng0109.cacherelay.budget.BudgetLimit;
import io.github.kxng0109.cacherelay.budget.BudgetService;
import io.github.kxng0109.cacherelay.budget.BudgetSettlement;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit tests for budget administration endpoints with a mocked service.
 */
@DisplayName("AdminBudgetController")
class AdminBudgetControllerTest {

	private final BudgetService budgetService = mock(BudgetService.class);
	private final BudgetSettlement budgetSettlement = mock(BudgetSettlement.class);

	private final AdminBudgetController controller =
			new AdminBudgetController(budgetService, budgetSettlement);

	@Test
	@DisplayName("create returns 201 with the created budget")
	void createReturns201() {
		BudgetLimit limit = new BudgetLimit("TEAM", "tenant-a", 100L, 200L, null);
		when(budgetService.create("TEAM", "tenant-a", 100L, 200L, null)).thenReturn(limit);

		var response = controller.createBudget(new CreateBudgetRequest("TEAM", "tenant-a", 100L, 200L, null));

		assertEquals(HttpStatus.CREATED, response.getStatusCode());
		assertEquals("TEAM", response.getBody().level());
		verify(budgetService).create("TEAM", "tenant-a", 100L, 200L, null);
	}

	@Test
	@DisplayName("balance returns 200 with the live snapshot")
	void balanceReturns200() {
		BudgetService.BalanceView view = new BudgetService.BalanceView("TEAM", "tenant-a", 100L, 40L, 200L, 10L);
		when(budgetService.balance("TEAM", "tenant-a")).thenReturn(view);

		var response = controller.balance("TEAM", "tenant-a");

		assertEquals(HttpStatus.OK, response.getStatusCode());
		BudgetBalanceResponse body = response.getBody();
		assertEquals(40L, body.minuteSpentMicros());
		assertEquals(10L, body.monthSpentMicros());
	}

	@Test
	@DisplayName("update returns 200 with the replaced budget")
	void updateReturns200() {
		UUID id = UUID.randomUUID();
		BudgetLimit limit = new BudgetLimit("TEAM", "tenant-a", 300L, 400L, null);
		when(budgetService.update(id, 300L, 400L, null)).thenReturn(limit);

		var response = controller.updateBudget(id, new UpdateBudgetRequest(300L, 400L, null));

		assertEquals(HttpStatus.OK, response.getStatusCode());
		assertEquals(300L, response.getBody().minuteMicros());
	}

	@Test
	@DisplayName("delete returns 204")
	void deleteReturns204() {
		UUID id = UUID.randomUUID();

		var response = controller.deleteBudget(id);

		assertEquals(HttpStatus.NO_CONTENT, response.getStatusCode());
		verify(budgetService).delete(id);
	}

	@Test
	@DisplayName("service errors propagate with their status")
	void serviceErrorsPropagate() {
		UUID id = UUID.randomUUID();
		when(budgetService.update(id, 1L, 2L, null))
				.thenThrow(new ResponseStatusException(HttpStatus.NOT_FOUND, "budget not found"));

		assertThrows(ResponseStatusException.class, () ->
				controller.updateBudget(id, new UpdateBudgetRequest(1L, 2L, null)));
	}

	@Test
	@DisplayName("hold returns 200 with held and settled micros")
	void holdReturns200() {
		when(budgetSettlement.readHold("req-1")).thenReturn(Optional.of(
				new BudgetSettlement.HoldView("req-1", "KEY|abc", 1250L, 900L, "SETTLED")));

		var response = controller.hold("req-1");

		assertEquals(HttpStatus.OK, response.getStatusCode());
		assertEquals(1250L, response.getBody().heldMicros());
		assertEquals(900L, response.getBody().settledMicros());
		assertEquals("SETTLED", response.getBody().state());
	}

	@Test
	@DisplayName("hold returns 404 when the record expired")
	void holdReturns404() {
		when(budgetSettlement.readHold("gone")).thenReturn(Optional.empty());

		var response = controller.hold("gone");

		assertEquals(HttpStatus.NOT_FOUND, response.getStatusCode());
	}

	@Test
	@DisplayName("list returns every budget as true-shape responses")
	void listReturnsBudgets() {
		BudgetLimit first = mock(BudgetLimit.class);
		when(first.getId()).thenReturn(UUID.randomUUID());
		when(first.getLevel()).thenReturn("TEAM");
		when(first.getSubjectId()).thenReturn("tenant-corp");
		when(first.getMinuteMicros()).thenReturn(5_000_000L);
		when(first.getMonthMicros()).thenReturn(200_000_000L);
		when(budgetService.list()).thenReturn(List.of(first));

		var response = controller.listBudgets();

		assertEquals(HttpStatus.OK, response.getStatusCode());
		assertEquals(1, response.getBody().size());
		assertEquals("TEAM", response.getBody().getFirst().level());
		assertEquals("tenant-corp", response.getBody().getFirst().subjectId());
		assertEquals(5_000_000L, response.getBody().getFirst().minuteMicros());
		assertEquals(200_000_000L, response.getBody().getFirst().monthMicros());
	}
}
