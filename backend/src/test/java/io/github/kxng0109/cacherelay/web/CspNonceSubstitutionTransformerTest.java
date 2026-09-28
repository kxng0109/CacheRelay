package io.github.kxng0109.cacherelay.web;

import io.github.kxng0109.cacherelay.auth.CspNonceFilter;
import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.servlet.resource.ResourceTransformerChain;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Unit tests for the per-response CSP nonce substitution (FE-01).
 */
@DisplayName("CspNonceSubstitutionTransformer")
class CspNonceSubstitutionTransformerTest {

	private static final String NONCE = "test-nonce-value";

	private final CspNonceSubstitutionTransformer transformer = new CspNonceSubstitutionTransformer();

	@TempDir
	private Path tempDir;

	private Resource transform(String html, String nonce) throws Exception {
		MockHttpServletRequest request = new MockHttpServletRequest("GET", "/index.html");
		if (nonce != null) {
			request.setAttribute(CspNonceFilter.NONCE_ATTRIBUTE, nonce);
		}
		Path shell = tempDir.resolve("index.html");
		Files.writeString(shell, html, StandardCharsets.UTF_8);
		Resource source = new FileSystemResource(shell);
		ResourceTransformerChain chain = mock(ResourceTransformerChain.class);
		when(chain.transform(any(HttpServletRequest.class), any(Resource.class))).thenReturn(source);
		return transformer.transform(request, source, chain);
	}

	private static String text(Resource resource) throws Exception {
		return new String(resource.getContentAsByteArray(), StandardCharsets.UTF_8);
	}

	@Test
	@DisplayName("both placeholders are replaced with the response nonce")
	void bothPlaceholdersReplaced() throws Exception {
		Resource result = transform(
				"<script nonce=\"__CSP_NONCE__\">var n=\"CSP_NONCE_PLACEHOLDER\";</script>", NONCE);

		assertThat(text(result)).doesNotContain("__CSP_NONCE__")
				.doesNotContain("CSP_NONCE_PLACEHOLDER")
				.contains("nonce=\"" + NONCE + "\"");
	}

	@Test
	@DisplayName("missing or empty nonce leaves the shell untouched")
	void missingOrEmptyNoncePassesThrough() throws Exception {
		String html = "<script nonce=\"__CSP_NONCE__\">x</script>";

		assertThat(text(transform(html, null))).isEqualTo(html);
		assertThat(text(transform(html, ""))).isEqualTo(html);
	}

	@Test
	@DisplayName("shell without placeholders is returned as-is")
	void placeholderFreeShellPassesThrough() throws Exception {
		String html = "<html><body>plain shell</body></html>";

		assertThat(transform(html, NONCE).getContentAsByteArray())
				.as("same bytes, no substitution allocated")
				.isEqualTo(html.getBytes(StandardCharsets.UTF_8));
	}

	@Test
	@DisplayName("vite-only placeholder shells are substituted")
	void viteOnlyPlaceholderSubstituted() throws Exception {
		Resource result = transform("<script nonce=\"CSP_NONCE_PLACEHOLDER\">x</script>", NONCE);

		assertThat(text(result)).doesNotContain("CSP_NONCE_PLACEHOLDER")
				.contains("nonce=\"" + NONCE + "\"");
	}
}
