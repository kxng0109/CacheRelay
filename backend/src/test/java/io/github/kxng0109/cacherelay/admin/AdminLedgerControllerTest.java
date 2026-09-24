package io.github.kxng0109.cacherelay.admin;

import io.github.kxng0109.cacherelay.admin.dto.*;
import io.github.kxng0109.cacherelay.ledger.DashboardService;
import io.github.kxng0109.cacherelay.ledger.DashboardView;
import io.github.kxng0109.cacherelay.ledger.UsageLedgerService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockHttpServletRequest;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

@DisplayName("AdminLedgerController")
class AdminLedgerControllerTest {

	private final UsageLedgerService usageLedgerService = mock(UsageLedgerService.class);
	private final DashboardService dashboards = mock(DashboardService.class);
	private final AdminLedgerController controller = new AdminLedgerController(usageLedgerService, dashboards);

	@Test
	@DisplayName("getSummary delegates to service and returns 200 OK")
	void getSummarySuccess() {
		Instant now = Instant.now();
		OwnerUsageSummary owner = new OwnerUsageSummary(
				"owner-1",
				10L,
				1000L,
				500L,
				1500L,
				14_000L,
				BigDecimal.valueOf(14000, 6),
				120.0
		);
		ModelUsageSummary model = new ModelUsageSummary(
				"openai",
				"gpt-4o",
				10L,
				1000L,
				500L,
				1500L,
				14_000L,
				BigDecimal.valueOf(14000, 6),
				120.0
		);
		ProviderUsageSummary provider = new ProviderUsageSummary(
				"openai",
				10L,
				1000L,
				500L,
				1500L,
				14_000L,
				BigDecimal.valueOf(14000, 6),
				120.0
		);

		LedgerSummaryResponse expected = new LedgerSummaryResponse(
				10L, 1000L, 500L, 1500L, 14_000L, BigDecimal.valueOf(14000, 6), 120.0,
				List.of(owner), List.of(model), List.of(provider)
		);

		when(usageLedgerService.getSummary(any(LedgerFilter.class))).thenReturn(expected);

		ResponseEntity<LedgerSummaryResponse> response = controller.getSummary(
				"owner-1", "openai", "gpt-4o", now.minusSeconds(3600), now
		);

		assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
		assertThat(response.getBody()).isEqualTo(expected);

		verify(usageLedgerService).getSummary(argThat(f ->
				                                              "owner-1".equals(f.ownerId())
						                                              && "openai".equals(f.provider())
						                                              && "gpt-4o".equals(f.model())
		));
	}

	@Test
	@DisplayName("getEntries delegates to service with pagination and returns 200 OK")
	void getEntriesSuccess() {
		UUID requestId = UUID.randomUUID();
		Instant now = Instant.now();
		LedgerEntryResponse entry = new LedgerEntryResponse(
				UUID.randomUUID(), requestId, "owner-1", "openai", "gpt-4o",
				100, 50, 150, 1400L, BigDecimal.valueOf(1400, 6), 200L, now
		);
		PageResponse<LedgerEntryResponse> expected = new PageResponse<>(List.of(entry), 0, 20, 1L, 1, false);

		when(usageLedgerService.getEntries(any(LedgerFilter.class), any(Pageable.class)))
				.thenReturn(expected);

		ResponseEntity<PageResponse<LedgerEntryResponse>> response = controller.getEntries(
				"owner-1", "openai", "gpt-4o", null, null, PageRequest.of(0, 20)
		);

		assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
		assertThat(response.getBody()).isEqualTo(expected);
		assertThat(response.getBody().content()).hasSize(1);
		assertThat(response.getBody().content().getFirst().requestId()).isEqualTo(requestId);
	}

	@Test
	@DisplayName("getEntryByRequestId returns 200 OK when found, 404 when absent")
	void getEntryByRequestIdScenarios() {
		UUID requestId = UUID.randomUUID();
		Instant now = Instant.now();
		LedgerEntryResponse entry = new LedgerEntryResponse(
				UUID.randomUUID(), requestId, "owner-1", "openai", "gpt-4o",
				100, 50, 150, 1400L, BigDecimal.valueOf(1400, 6), 200L, now
		);

		when(usageLedgerService.getEntryByRequestId(requestId)).thenReturn(Optional.of(entry));
		when(usageLedgerService.getEntryByRequestId(argThat(r -> r != null && !r.equals(requestId))))
				.thenReturn(Optional.empty());

		// Found
		ResponseEntity<LedgerEntryResponse> found = controller.getEntryByRequestId(requestId);
		assertThat(found.getStatusCode()).isEqualTo(HttpStatus.OK);
		assertThat(found.getBody()).isNotNull();
		assertThat(found.getBody().requestId()).isEqualTo(requestId);

		// Not Found
		UUID missingId = UUID.randomUUID();
		ResponseEntity<LedgerEntryResponse> notFound = controller.getEntryByRequestId(missingId);
		assertThat(notFound.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
		assertThat(notFound.getBody()).isNull();
	}

	@Test
	@DisplayName("getUserSummary attributes admin sessions and returns freshness headers")
	void getUserSummaryAdminSession() {
		UUID adminId = UUID.randomUUID();
		UUID targetId = UUID.randomUUID();
		Instant now = Instant.now();
		LedgerSummaryResponse expected = new LedgerSummaryResponse(
				5L, 500L, 250L, 750L, 7_000L, BigDecimal.valueOf(7_000, 6), 100.0,
				List.of(), List.of(), List.of()
		);
		DashboardView view = new DashboardView(expected, now, now);
		when(dashboards.getUserAsAdmin(eq(adminId.toString()), eq(targetId), any(), any(), any(), any()))
				.thenReturn(view);

		MockHttpServletRequest request = new MockHttpServletRequest("GET", "/v1/admin/ledger/user/" + targetId + "/summary");
		request.setAttribute(AdminAuthFilter.ATTRIBUTE_ADMIN_ID, adminId);
		ResponseEntity<LedgerSummaryResponse> response = controller.getUserSummary(
				targetId, now.minusSeconds(3600), now, request);

		assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
		assertThat(response.getBody()).isEqualTo(expected);
		assertThat(response.getHeaders().getFirst(DashboardService.HEADER_GENERATED_AT)).isNotNull();
		assertThat(response.getHeaders().getFirst(DashboardService.HEADER_WATERMARK)).isNotNull();
	}

	@Test
	@DisplayName("getUserSummary attributes master-key callers distinctly")
	void getUserSummaryMasterKey() {
		UUID targetId = UUID.randomUUID();
		Instant now = Instant.now();
		LedgerSummaryResponse expected = new LedgerSummaryResponse(
				0L, 0L, 0L, 0L, 0L, BigDecimal.valueOf(0, 6), 0.0,
				List.of(), List.of(), List.of()
		);
		when(dashboards.getUserAsAdmin(eq("master-key"), eq(targetId), any(), any(), any(), any()))
				.thenReturn(new DashboardView(expected, now, now));

		MockHttpServletRequest request = new MockHttpServletRequest("GET", "/v1/admin/ledger/user/" + targetId + "/summary");
		ResponseEntity<LedgerSummaryResponse> response = controller.getUserSummary(
				targetId, null, null, request);

		assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
		verify(dashboards).getUserAsAdmin(eq("master-key"), eq(targetId), isNull(), isNull(), any(), any());
	}
}
