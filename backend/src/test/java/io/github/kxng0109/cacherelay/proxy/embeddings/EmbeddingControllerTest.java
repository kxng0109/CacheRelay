package io.github.kxng0109.cacherelay.proxy.embeddings;

import io.github.kxng0109.cacherelay.contracts.SHA256Hash;
import io.github.kxng0109.cacherelay.contracts.VirtualApiKey;
import io.github.kxng0109.cacherelay.proxy.embeddings.dto.EmbeddingData;
import io.github.kxng0109.cacherelay.proxy.embeddings.dto.EmbeddingRequest;
import io.github.kxng0109.cacherelay.proxy.embeddings.dto.EmbeddingResponse;
import io.github.kxng0109.cacherelay.security.filter.KeyAuthFilter;
import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.server.ResponseStatusException;

import java.time.Instant;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.*;

@DisplayName("EmbeddingController")
@SuppressWarnings("DataFlowIssue")
class EmbeddingControllerTest {

	private final EmbeddingService embeddingService = mock(EmbeddingService.class);
	private final EmbeddingController controller = new EmbeddingController(embeddingService);

	@Test
	@DisplayName("createEmbeddings extracts ownerId and delegates to EmbeddingService")
	void createEmbeddingsDelegates() {
		HttpServletRequest httpRequest = mock(HttpServletRequest.class);
		when(httpRequest.getAttribute(KeyAuthFilter.OWNER_ID_ATTRIBUTE)).thenReturn("tenant-alpha");

		EmbeddingRequest request = new EmbeddingRequest("input text", "text-embedding-3-small", null, null, null);
		EmbeddingResponse expected = EmbeddingResponse.of(
				"text-embedding-3-small", List.of(EmbeddingData.of(0, new float[]{0.1f})), 5
		);

		when(embeddingService.processEmbedding(request, "tenant-alpha", null, null)).thenReturn(expected);
		when(embeddingService.resolveProviderName("text-embedding-3-small")).thenReturn("openai");

		ResponseEntity<EmbeddingResponse> response = controller.createEmbeddings(request, httpRequest);

		assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
		assertThat(response.getBody()).isEqualTo(expected);
		assertThat(response.getHeaders().getFirst("X-CacheRelay-Provider")).isEqualTo("openai");
		assertThat(response.getHeaders().getFirst("X-CacheRelay-Tried")).isEqualTo("openai");
		verify(embeddingService).processEmbedding(request, "tenant-alpha", null, null);
	}

	@Test
	@DisplayName("a malformed Idempotency-Key is rejected with 400 before delegating")
	void malformedIdempotencyKeyRejected() {
		HttpServletRequest httpRequest = mock(HttpServletRequest.class);
		when(httpRequest.getAttribute(KeyAuthFilter.OWNER_ID_ATTRIBUTE)).thenReturn("tenant-alpha");
		when(httpRequest.getHeader("Idempotency-Key")).thenReturn("y".repeat(300));

		EmbeddingRequest request = new EmbeddingRequest("input text", "text-embedding-3-small", null, null, null);

		assertThatThrownBy(() -> controller.createEmbeddings(request, httpRequest))
				.isInstanceOf(ResponseStatusException.class)
				.hasMessageContaining("400");
		verify(embeddingService, never()).processEmbedding(any(), any(), any(), any());
	}

	@Test
	@DisplayName("FS-B09: key restricted to another provider gets 403 without upstream spend")
	void disallowedProviderRejected() {		HttpServletRequest httpRequest = mock(HttpServletRequest.class);
		when(httpRequest.getAttribute(KeyAuthFilter.OWNER_ID_ATTRIBUTE)).thenReturn("tenant-alpha");
		VirtualApiKey key = new VirtualApiKey(
				SHA256Hash.fromRawKey("gw-" + "a".repeat(32)),
				"gw-",
				"tenant-alpha",
				"test",
				100,
				100000,
				Set.of(),
				Set.of("ollama-local"),
				true,
				Instant.now());
		when(httpRequest.getAttribute(KeyAuthFilter.VIRTUAL_KEY_ATTRIBUTE)).thenReturn(key);
		when(embeddingService.resolveProviderName("text-embedding-3-small")).thenReturn("openai-main");

		EmbeddingRequest request = new EmbeddingRequest("input text", "text-embedding-3-small", null, null, null);

		assertThatThrownBy(() -> controller.createEmbeddings(request, httpRequest))
				.isInstanceOf(ResponseStatusException.class)
				.hasMessageContaining("403");
		verify(embeddingService, never()).processEmbedding(any(), any(), any(), any());
	}

	@Test
	@DisplayName("FS-B12: key allowing the resolved provider proceeds upstream")
	void allowedProviderProceeds() {
		HttpServletRequest httpRequest = mock(HttpServletRequest.class);
		when(httpRequest.getAttribute(KeyAuthFilter.OWNER_ID_ATTRIBUTE)).thenReturn("tenant-alpha");
		VirtualApiKey key = new VirtualApiKey(
				SHA256Hash.fromRawKey("gw-" + "b".repeat(32)),
				"gw-",
				"tenant-alpha",
				"test",
				100,
				100000,
				Set.of(),
				Set.of("openai-main"),
				true,
				Instant.now());
		when(httpRequest.getAttribute(KeyAuthFilter.VIRTUAL_KEY_ATTRIBUTE)).thenReturn(key);
		when(embeddingService.resolveProviderName("text-embedding-3-small")).thenReturn("openai-main");
		EmbeddingRequest request = new EmbeddingRequest("input text", "text-embedding-3-small", null, null, null);
		EmbeddingResponse expected = EmbeddingResponse.of(
				"text-embedding-3-small", List.of(EmbeddingData.of(0, new float[]{0.1f})), 5);
		when(embeddingService.processEmbedding(request, "tenant-alpha", null, null)).thenReturn(expected);

		ResponseEntity<EmbeddingResponse> response = controller.createEmbeddings(request, httpRequest);

		assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
		assertThat(response.getBody()).isEqualTo(expected);
	}
}
