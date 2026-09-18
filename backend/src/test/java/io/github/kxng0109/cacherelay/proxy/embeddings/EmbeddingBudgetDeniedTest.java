package io.github.kxng0109.cacherelay.proxy.embeddings;

import io.github.kxng0109.cacherelay.budget.BudgetDecision;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Proves embeddings budget denials carry the chat-identical 429 shape.
 *
 * <p>A standalone slice is the honest scope: header rendering lives in the controller's exception handler,
 * while chain behavior is covered by the live-server suites. A bare {@code ResponseStatusException} cannot
 * carry headers, which is exactly the parity gap this locks shut.
 */
@DisplayName("Embeddings budget-denial parity")
@ExtendWith(MockitoExtension.class)
class EmbeddingBudgetDeniedTest {

	@Mock
	private EmbeddingService embeddingService;

	@InjectMocks
	private EmbeddingController controller;

	@Test
	@DisplayName("denial renders 429 with the X-Budget-* family and shared body")
	void denialRendersChatIdenticalShape() throws Exception {
		BudgetDecision.Denied denied = new BudgetDecision.Denied("KEY", "minute", 60L);
		when(embeddingService.processEmbedding(any(), any(), any(), any()))
				.thenThrow(new EmbeddingBudgetDeniedException(denied));

		MockMvc mockMvc = MockMvcBuilders.standaloneSetup(controller).build();
		mockMvc.perform(post("/v1/embeddings")
						.contentType(MediaType.APPLICATION_JSON)
						.content("{\"model\":\"m\",\"input\":\"hi\"}"))
				.andExpect(status().isTooManyRequests())
				.andExpect(header().string("X-Budget-Remaining", "0"))
				.andExpect(header().exists("X-Budget-Reset"))
				.andExpect(header().string("X-Budget-Level", "KEY"))
				.andExpect(header().string("X-Budget-Window", "minute"))
				.andExpect(header().exists("Retry-After"))
				.andExpect(jsonPath("$.error.message").value("budget exhausted (KEY minute)"));
	}
}
