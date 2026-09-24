package io.github.kxng0109.cacherelay.auth.webhook;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import io.github.kxng0109.cacherelay.auth.RevalidationStatus;
import io.github.kxng0109.cacherelay.auth.SsoLink;
import io.github.kxng0109.cacherelay.auth.SsoLinkRepository;
import io.github.kxng0109.cacherelay.auth.SsoRevalidation;
import io.github.kxng0109.cacherelay.auth.SsoRevalidationRepository;
import io.github.kxng0109.cacherelay.auth.TeamRole;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@DisplayName("WebhookInvalidator")
class WebhookInvalidatorTest {

	private final SsoLinkRepository links = mock(SsoLinkRepository.class);

	private final SsoRevalidationRepository watermarks = mock(SsoRevalidationRepository.class);

	private final WebhookInvalidator invalidator = new WebhookInvalidator(links, watermarks);

	private final UUID userId = UUID.randomUUID();

	@Test
	@DisplayName("blank subjects never touch stores")
	void blankSubjectsSkip() {
		assertThat(invalidator.invalidate("github", null)).isFalse();
		assertThat(invalidator.invalidate("github", "  ")).isFalse();
		verify(links, never()).findBySubject(any());
		verify(watermarks, never()).save(any());
	}

	@Test
	@DisplayName("unknown subjects report unmatched")
	void unknownSubjectsMiss() {
		when(links.findBySubject("ghost")).thenReturn(List.of());

		assertThat(invalidator.invalidate("github", "ghost")).isFalse();
		verify(watermarks, never()).save(any());
	}

	@Test
	@DisplayName("foreign registrations never match")
	void foreignRegistrationsSkipped() {
		SsoLink link = new SsoLink(userId, "https://login.example.com/t", "sub-1", "azure");
		when(links.findBySubject("sub-1")).thenReturn(List.of(link));

		assertThat(invalidator.invalidate("github", "sub-1")).isFalse();
		verify(watermarks, never()).save(any());
	}

	@Test
	@DisplayName("missing watermarks seed at the epoch")
	void missingWatermarksSeed() {
		SsoLink link = new SsoLink(userId, "https://api.github.com", "123", "github");
		when(links.findBySubject("123")).thenReturn(List.of(link));
		when(watermarks.findById(userId)).thenReturn(Optional.empty());

		assertThat(invalidator.invalidate("github", "123")).isTrue();
		verify(watermarks).save(any(SsoRevalidation.class));
	}

	@Test
	@DisplayName("present watermarks re-stamp at the epoch")
	void presentWatermarksRestamp() {
		SsoLink link = new SsoLink(userId, "https://api.github.com", "123", "github");
		SsoRevalidation watermark = new SsoRevalidation(userId, Instant.now(),
				RevalidationStatus.ACTIVE);
		when(links.findBySubject("123")).thenReturn(List.of(link));
		when(watermarks.findById(userId)).thenReturn(Optional.of(watermark));

		assertThat(invalidator.invalidate("github", "123")).isTrue();
		assertThat(watermark.getLastVerifiedAt()).isEqualTo(Instant.EPOCH);
		assertThat(watermark.getLastStatus()).isEqualTo(RevalidationStatus.ACTIVE);
		verify(watermarks).save(watermark);
	}

	@Test
	@DisplayName("mixed links touch only the matching registration")
	void mixedLinksFilter() {
		UUID otherId = UUID.randomUUID();
		SsoLink mine = new SsoLink(userId, "https://api.github.com", "op", "github");
		SsoLink foreign = new SsoLink(otherId, "https://login.example.com/t", "op", "azure");
		when(links.findBySubject("op")).thenReturn(List.of(mine, foreign));
		when(watermarks.findById(any())).thenReturn(Optional.empty());

		assertThat(invalidator.invalidate("github", "op")).isTrue();
		verify(watermarks).save(any(SsoRevalidation.class));
	}
}
