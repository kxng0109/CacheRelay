package io.github.kxng0109.cacherelay.proxy;

import java.util.Map;

import io.micrometer.tracing.Span;
import io.micrometer.tracing.Tracer;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Span attribute matrix: every mark helper across tiers, providers, cost
 * presence, and hostile inputs (nulls, blanks, NaN, negatives, wrong types).
 * Attribute values must stay routing metadata forever — this suite pins the
 * exact tag set so a payload leak fails loudly here first.
 */
@DisplayName("ProxySpanAttributes")
class ProxySpanAttributesTest {

	@Test
	@DisplayName("routing marks alias and model")
	void routingMarksAliasAndModel() {
		MockHttpServletRequest request = new MockHttpServletRequest();

		ProxySpanAttributes.markRouting(request, "gpt-56-luna", "gpt-56-luna");

		assertThat(ProxySpanAttributes.collect(request)).containsExactlyInAnyOrderEntriesOf(Map.of(
				ProxySpanAttributes.ALIAS, "gpt-56-luna",
				ProxySpanAttributes.MODEL, "gpt-56-luna"));
	}

	@Test
	@DisplayName("blank and null routing values are skipped")
	void blankRoutingSkipped() {
		MockHttpServletRequest request = new MockHttpServletRequest();

		ProxySpanAttributes.markRouting(request, "   ", null);

		assertThat(ProxySpanAttributes.collect(request)).isEmpty();
	}

	@Test
	@DisplayName("cache hits pin tier, similarity, and ok outcome")
	void cacheHitPinsTier() {
		MockHttpServletRequest request = new MockHttpServletRequest();

		ProxySpanAttributes.markCacheHit(request, "l2-semantic", 0.123456);

		assertThat(ProxySpanAttributes.collect(request)).containsExactlyInAnyOrderEntriesOf(Map.of(
				ProxySpanAttributes.CACHE_OUTCOME, "hit",
				ProxySpanAttributes.CACHE_TIER, "l2-semantic",
				ProxySpanAttributes.CACHE_SIMILARITY, "0.1235",
				ProxySpanAttributes.OUTCOME, ProxySpanAttributes.OUTCOME_OK));
	}

	@Test
	@DisplayName("NaN similarity is skipped but the hit still records")
	void nanSimilaritySkipped() {
		MockHttpServletRequest request = new MockHttpServletRequest();

		ProxySpanAttributes.markCacheHit(request, "replay", Double.NaN);

		Map<String, String> tags = ProxySpanAttributes.collect(request);
		assertThat(tags).containsEntry(ProxySpanAttributes.CACHE_TIER, "replay");
		assertThat(tags).doesNotContainKey(ProxySpanAttributes.CACHE_SIMILARITY);
		assertThat(tags).containsEntry(ProxySpanAttributes.OUTCOME, ProxySpanAttributes.OUTCOME_OK);
	}

	@Test
	@DisplayName("blank tiers are skipped but the hit still records")
	void blankTierSkipped() {
		MockHttpServletRequest request = new MockHttpServletRequest();

		ProxySpanAttributes.markCacheHit(request, "  ", 0.5);

		Map<String, String> tags = ProxySpanAttributes.collect(request);
		assertThat(tags).doesNotContainKey(ProxySpanAttributes.CACHE_TIER);
		assertThat(tags).containsEntry(ProxySpanAttributes.CACHE_SIMILARITY, "0.5000");
	}

	@Test
	@DisplayName("misses pin provider, tried count, hold micros, and ok outcome")
	void missPinsProviderAndHold() {
		MockHttpServletRequest request = new MockHttpServletRequest();

		ProxySpanAttributes.markMiss(request, "openai", 3, 1_250_000L);

		assertThat(ProxySpanAttributes.collect(request)).containsExactlyInAnyOrderEntriesOf(Map.of(
				ProxySpanAttributes.CACHE_OUTCOME, "miss",
				ProxySpanAttributes.PROVIDER, "openai",
				ProxySpanAttributes.PROVIDERS_TRIED, "3",
				ProxySpanAttributes.BUDGET_HOLD_MICROS, "1250000",
				ProxySpanAttributes.OUTCOME, ProxySpanAttributes.OUTCOME_OK));
	}

	@Test
	@DisplayName("negative tried and hold counts are skipped")
	void negativeCountsSkipped() {
		MockHttpServletRequest request = new MockHttpServletRequest();

		ProxySpanAttributes.markMiss(request, "openai", -1, -1L);

		Map<String, String> tags = ProxySpanAttributes.collect(request);
		assertThat(tags).containsEntry(ProxySpanAttributes.PROVIDER, "openai");
		assertThat(tags).doesNotContainKey(ProxySpanAttributes.PROVIDERS_TRIED);
		assertThat(tags).doesNotContainKey(ProxySpanAttributes.BUDGET_HOLD_MICROS);
	}

	@Test
	@DisplayName("blank providers are skipped")
	void blankProviderSkipped() {
		MockHttpServletRequest request = new MockHttpServletRequest();

		ProxySpanAttributes.markMiss(request, "", 1, 100L);

		assertThat(ProxySpanAttributes.collect(request))
				.doesNotContainKey(ProxySpanAttributes.PROVIDER);
	}

	@Test
	@DisplayName("errors pin outcome and reason")
	void errorPinsReason() {
		MockHttpServletRequest request = new MockHttpServletRequest();

		ProxySpanAttributes.markError(request, "budget_denied");

		assertThat(ProxySpanAttributes.collect(request)).containsExactlyInAnyOrderEntriesOf(Map.of(
				ProxySpanAttributes.OUTCOME, ProxySpanAttributes.OUTCOME_ERROR,
				ProxySpanAttributes.ERROR_REASON, "budget_denied"));
	}

	@Test
	@DisplayName("blank reasons are skipped but the error outcome stays")
	void blankReasonSkipped() {
		MockHttpServletRequest request = new MockHttpServletRequest();

		ProxySpanAttributes.markError(request, "  ");

		Map<String, String> tags = ProxySpanAttributes.collect(request);
		assertThat(tags).containsEntry(ProxySpanAttributes.OUTCOME, ProxySpanAttributes.OUTCOME_ERROR);
		assertThat(tags).doesNotContainKey(ProxySpanAttributes.ERROR_REASON);
	}

	@Test
	@DisplayName("provider errors pin provider, status, and upstream reason")
	void providerErrorPinsStatus() {
		MockHttpServletRequest request = new MockHttpServletRequest();

		ProxySpanAttributes.markProviderError(request, "anthropic", 529);

		assertThat(ProxySpanAttributes.collect(request)).containsExactlyInAnyOrderEntriesOf(Map.of(
				ProxySpanAttributes.PROVIDER, "anthropic",
				ProxySpanAttributes.UPSTREAM_STATUS, "529",
				ProxySpanAttributes.OUTCOME, ProxySpanAttributes.OUTCOME_ERROR,
				ProxySpanAttributes.ERROR_REASON, "upstream_error"));
	}

	@Test
	@DisplayName("later marks overwrite earlier ones")
	void laterMarksWin() {
		MockHttpServletRequest request = new MockHttpServletRequest();

		ProxySpanAttributes.markRouting(request, "a", "a");
		ProxySpanAttributes.markError(request, "unknown_model");

		assertThat(ProxySpanAttributes.collect(request)).containsEntry(
				ProxySpanAttributes.ERROR_REASON, "unknown_model");
	}

	@Test
	@DisplayName("non-string attributes are ignored")
	void nonStringAttributesIgnored() {
		MockHttpServletRequest request = new MockHttpServletRequest();
		request.setAttribute("cacherelay.span." + ProxySpanAttributes.PROVIDERS_TRIED, 3);

		assertThat(ProxySpanAttributes.collect(request)).isEmpty();
	}

	@Test
	@DisplayName("empty requests collect nothing")
	void emptyCollectsNothing() {
		assertThat(ProxySpanAttributes.collect(new MockHttpServletRequest())).isEmpty();
	}

	@Test
	@DisplayName("applyTo tags every entry on the span")
	void applyToTagsAll() {
		MockHttpServletRequest request = new MockHttpServletRequest();
		ProxySpanAttributes.markRouting(request, "m", "m");
		ProxySpanAttributes.markError(request, "unknown_model");
		Span span = mock(Span.class);

		ProxySpanAttributes.applyTo(span, ProxySpanAttributes.collect(request));

		verify(span).tag(ProxySpanAttributes.ALIAS, "m");
		verify(span).tag(ProxySpanAttributes.MODEL, "m");
		verify(span).tag(ProxySpanAttributes.OUTCOME, ProxySpanAttributes.OUTCOME_ERROR);
		verify(span).tag(ProxySpanAttributes.ERROR_REASON, "unknown_model");
	}

	@Test
	@DisplayName("applyTo with a null span is a silent no-op")
	void applyToNullSpanIsNoOp() {
		MockHttpServletRequest request = new MockHttpServletRequest();
		ProxySpanAttributes.markError(request, "budget_denied");

		ProxySpanAttributes.applyTo(null, ProxySpanAttributes.collect(request));
	}

	@Test
	@DisplayName("applyTo with no tags never touches the span")
	void applyToEmptyTagsUntouched() {
		Span span = mock(Span.class);

		ProxySpanAttributes.applyTo(span, Map.of());

		verify(span, never()).tag(anyString(), anyString());
	}

	@Test
	@DisplayName("tagCurrentSpan tags the active span with recorded attributes")
	void tagCurrentSpanTagsActive() {
		MockHttpServletRequest request = new MockHttpServletRequest();
		ProxySpanAttributes.markRouting(request, "m", "m");
		ProxySpanAttributes.markMiss(request, "openai", 1, 200L);
		Span span = mock(Span.class);
		Tracer tracer = mock(Tracer.class);
		when(tracer.currentSpan()).thenReturn(span);

		ProxySpanAttributes.tagCurrentSpan(tracer, request);

		verify(span).tag(ProxySpanAttributes.ALIAS, "m");
		verify(span).tag(ProxySpanAttributes.PROVIDER, "openai");
		verify(span).tag(ProxySpanAttributes.BUDGET_HOLD_MICROS, "200");
	}

	@Test
	@DisplayName("tagCurrentSpan with a null tracer is a silent no-op")
	void tagCurrentSpanNullTracerIsNoOp() {
		MockHttpServletRequest request = new MockHttpServletRequest();
		ProxySpanAttributes.markError(request, "budget_denied");

		ProxySpanAttributes.tagCurrentSpan(null, request);
	}

	@Test
	@DisplayName("tagCurrentSpan without an active span is a silent no-op")
	void tagCurrentSpanNullSpanIsNoOp() {
		MockHttpServletRequest request = new MockHttpServletRequest();
		ProxySpanAttributes.markError(request, "budget_denied");
		Tracer tracer = mock(Tracer.class);
		when(tracer.currentSpan()).thenReturn(null);

		ProxySpanAttributes.tagCurrentSpan(tracer, request);
	}
}
