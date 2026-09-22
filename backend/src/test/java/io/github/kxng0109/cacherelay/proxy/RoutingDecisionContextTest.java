package io.github.kxng0109.cacherelay.proxy;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import jakarta.servlet.http.HttpServletRequest;

/**
 * Unit tests for {@link RoutingDecisionContext}: header parsing, defaults, and
 * fail-fast validation. Parsed values are logged by the decision writer, never
 * enforced: effective policy stays quality-first in phase 1.
 */
@DisplayName("RoutingDecisionContext")
class RoutingDecisionContextTest {

	private static HttpServletRequest request(String tier, String mode) {
		HttpServletRequest request = mock(HttpServletRequest.class);
		when(request.getHeader(RoutingDecisionContext.MIN_QUALITY_TIER_HEADER)).thenReturn(tier);
		when(request.getHeader(RoutingDecisionContext.TRADEOFF_MODE_HEADER)).thenReturn(mode);
		return request;
	}

	@Test
	@DisplayName("absent headers read as unrated floor with quality tradeoff")
	void absentHeadersDefault() {
		RoutingDecisionContext context = RoutingDecisionContext.fromRequest(request(null, null));

		assertThat(context.minQualityTier()).isNull();
		assertThat(context.tradeoffMode()).isEqualTo("quality");
	}

	@Test
	@DisplayName("valid tier and mode normalize case")
	void validHeadersNormalize() {
		RoutingDecisionContext context = RoutingDecisionContext.fromRequest(request("frontier", "ECO"));

		assertThat(context.minQualityTier()).isEqualTo("FRONTIER");
		assertThat(context.tradeoffMode()).isEqualTo("eco");
	}

	@Test
	@DisplayName("explicit quality mode stays quality")
	void explicitQualityMode() {
		RoutingDecisionContext context = RoutingDecisionContext.fromRequest(request(null, "quality"));

		assertThat(context.tradeoffMode()).isEqualTo("quality");
	}

	@Test
	@DisplayName("unknown tier fails fast with 400")
	void unknownTierFailsFast() {
		assertThatThrownBy(() -> RoutingDecisionContext.fromRequest(request("ULTRA", null)))
				.isInstanceOf(ResponseStatusException.class)
				.extracting(ex -> ((ResponseStatusException) ex).getStatusCode())
				.isEqualTo(HttpStatus.BAD_REQUEST);
	}

	@Test
	@DisplayName("unknown tradeoff mode fails fast with 400")
	void unknownModeFailsFast() {
		assertThatThrownBy(() -> RoutingDecisionContext.fromRequest(request(null, "cheapest")))
				.isInstanceOf(ResponseStatusException.class)
				.extracting(ex -> ((ResponseStatusException) ex).getStatusCode())
				.isEqualTo(HttpStatus.BAD_REQUEST);
	}

	@Test
	@DisplayName("blank headers behave as absent")
	void blankHeadersDefault() {
		RoutingDecisionContext context = RoutingDecisionContext.fromRequest(request("  ", " "));

		assertThat(context.minQualityTier()).isNull();
		assertThat(context.tradeoffMode()).isEqualTo("quality");
	}

	@Test
	@DisplayName("defaults carry no floor and quality tradeoff")
	void defaults() {
		RoutingDecisionContext context = RoutingDecisionContext.defaults();

		assertThat(context.minQualityTier()).isNull();
		assertThat(context.tradeoffMode()).isEqualTo("quality");
	}
}
