package io.github.kxng0109.cacherelay.admin;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;

import io.github.kxng0109.cacherelay.auth.AuthAuditService;
import io.github.kxng0109.cacherelay.auth.RefreshService;
import io.github.kxng0109.cacherelay.capture.CaptureProperties;
import io.github.kxng0109.cacherelay.capture.CapturePurger;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.server.ResponseStatusException;
import tools.jackson.databind.JsonNode;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@DisplayName("AdminCaptureController")
class AdminCaptureControllerTest {

	private static final Instant FIXED_NOW = Instant.parse("2026-09-23T12:00:00Z");

	private CaptureProperties properties(Path dir) {
		return new CaptureProperties(true, 1000, 100_000, dir.toString(), 32768,
				268435456L, 90, 7, List.of());
	}

	private AdminCaptureController controller(Path dir, CapturePurger purger) {
		return new AdminCaptureController(properties(dir), purger, mock(AuthAuditService.class),
				Clock.fixed(FIXED_NOW, ZoneOffset.UTC));
	}

	private void segment(Path dir, String name, String... lines) throws Exception {
		Files.write(dir.resolve(name), List.of(lines), StandardCharsets.UTF_8);
	}

	private String row(String requestId, String ownerHash, Instant capturedAt,
			Instant expiresAt) {
		return "{\"request_id\":\"" + requestId + "\",\"owner_hash\":"
				+ (ownerHash == null ? "null" : "\"" + ownerHash + "\"")
				+ ",\"captured_at\":\"" + capturedAt + "\",\"expires_at\":\"" + expiresAt + "\"}";
	}

	@Test
	@DisplayName("recent filters by owner and expiry with an audit record")
	void recentFilters(@TempDir Path dir) throws Exception {
		String hash = RefreshService.sha256Hex("owner-1");
		segment(dir, "capture-default-20260923-12.jsonl",
				row("r-1", hash, FIXED_NOW.minusSeconds(60), FIXED_NOW.plusSeconds(3600)),
				row("r-2", "other-hash", FIXED_NOW.minusSeconds(60), FIXED_NOW.plusSeconds(3600)),
				row("r-3", hash, FIXED_NOW.minusSeconds(60), FIXED_NOW.minusSeconds(10)),
				"not-json");
		AuthAuditService audit = mock(AuthAuditService.class);
		AdminCaptureController controller = new AdminCaptureController(properties(dir),
				mock(CapturePurger.class), audit, Clock.fixed(FIXED_NOW, ZoneOffset.UTC));
		MockHttpServletRequest request = new MockHttpServletRequest("GET",
				"/v1/admin/capture/recent");
		UUID adminId = UUID.randomUUID();
		request.setAttribute(AdminAuthFilter.ATTRIBUTE_ADMIN_ID, adminId);

		ResponseEntity<List<JsonNode>> response =
				controller.recent("owner-1", 20, null, request);

		assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
		assertThat(response.getBody()).isNotNull();
		assertThat(response.getBody()).hasSize(1);
		assertThat(response.getBody().getFirst().path("request_id").asString()).isEqualTo("r-1");
		verify(audit).record(AuthAuditService.ACTION_CAPTURE_READ,
				AuthAuditService.SEVERITY_INFO, adminId.toString(), "/v1/admin/capture/recent",
				AuthAuditService.OUTCOME_SUCCESS, "127.0.0.1", null);
	}

	@Test
	@DisplayName("recent honors limits and since bounds")
	void recentBounds(@TempDir Path dir) throws Exception {
		segment(dir, "capture-default-20260923-12.jsonl",
				row("r-1", null, FIXED_NOW.minusSeconds(3600), FIXED_NOW.plusSeconds(3600)),
				row("r-2", null, FIXED_NOW.minusSeconds(60), FIXED_NOW.plusSeconds(3600)),
				"{\"request_id\":\"r-4\"}");
		MockHttpServletRequest request = new MockHttpServletRequest("GET",
				"/v1/admin/capture/recent");

		assertThatThrownBy(() -> controller(dir, mock(CapturePurger.class)).recent(null, 0, null,
				request))
				.isInstanceOf(ResponseStatusException.class)
				.extracting(ex -> ((ResponseStatusException) ex).getStatusCode())
				.isEqualTo(HttpStatus.BAD_REQUEST);
		assertThatThrownBy(() -> controller(dir, mock(CapturePurger.class)).recent(null, 101,
				null, request))
				.isInstanceOf(ResponseStatusException.class)
				.extracting(ex -> ((ResponseStatusException) ex).getStatusCode())
				.isEqualTo(HttpStatus.BAD_REQUEST);

		ResponseEntity<List<JsonNode>> limited = controller(dir,
				mock(CapturePurger.class)).recent(null, 1, null, request);
		assertThat(limited.getBody()).isNotNull();
		assertThat(limited.getBody()).hasSize(1);
		assertThat(limited.getBody().getFirst().path("request_id").asString()).isEqualTo("r-4");

		ResponseEntity<List<JsonNode>> ranged = controller(dir,
				mock(CapturePurger.class)).recent(null, 20, FIXED_NOW.minusSeconds(120), request);
		assertThat(ranged.getBody()).isNotNull();
		assertThat(ranged.getBody()).hasSize(1);
		assertThat(ranged.getBody().getFirst().path("request_id").asString()).isEqualTo("r-2");
	}

	@Test
	@DisplayName("missing stores read as empty")
	void missingStoreReadsEmpty(@TempDir Path dir) {
		MockHttpServletRequest request = new MockHttpServletRequest("GET",
				"/v1/admin/capture/recent");

		ResponseEntity<List<JsonNode>> response = controller(dir,
				mock(CapturePurger.class)).recent(null, 20, null, request);

		assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
		assertThat(response.getBody()).isNotNull();
		assertThat(response.getBody()).isEmpty();
	}

	@Test
	@DisplayName("owner erasure delegates with an audit record")
	void eraseOwnerDelegates(@TempDir Path dir) {
		CapturePurger purger = mock(CapturePurger.class);
		when(purger.purgeOwner("owner-1")).thenReturn(2);
		AuthAuditService audit = mock(AuthAuditService.class);
		AdminCaptureController controller = new AdminCaptureController(properties(dir), purger,
				audit, Clock.fixed(FIXED_NOW, ZoneOffset.UTC));
		MockHttpServletRequest request = new MockHttpServletRequest("DELETE",
				"/v1/admin/capture/owner/owner-1");

		ResponseEntity<Integer> response = controller.eraseOwner("owner-1", request);

		assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
		assertThat(response.getBody()).isEqualTo(2);
		verify(audit).record(AuthAuditService.ACTION_CAPTURE_READ,
				AuthAuditService.SEVERITY_INFO, "master-key", "/v1/admin/capture/owner/owner-1",
				AuthAuditService.OUTCOME_SUCCESS, "127.0.0.1", null);
		verify(purger).purgeOwner("owner-1");
	}

	@Test
	@DisplayName("admin sessions attribute erasure to their identity")
	void eraseOwnerAttributesAdmin(@TempDir Path dir) {
		CapturePurger purger = mock(CapturePurger.class);
		when(purger.purgeOwner("owner-1")).thenReturn(0);
		AuthAuditService audit = mock(AuthAuditService.class);
		AdminCaptureController controller = new AdminCaptureController(properties(dir), purger,
				audit, Clock.fixed(FIXED_NOW, ZoneOffset.UTC));
		MockHttpServletRequest request = new MockHttpServletRequest("DELETE",
				"/v1/admin/capture/owner/owner-1");
		UUID adminId = UUID.randomUUID();
		request.setAttribute(AdminAuthFilter.ATTRIBUTE_ADMIN_ID, adminId);

		ResponseEntity<Integer> response = controller.eraseOwner("owner-1", request);

		assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
		verify(audit).record(AuthAuditService.ACTION_CAPTURE_READ,
				AuthAuditService.SEVERITY_INFO, adminId.toString(),
				"/v1/admin/capture/owner/owner-1", AuthAuditService.OUTCOME_SUCCESS, "127.0.0.1",
				null);
	}
}
