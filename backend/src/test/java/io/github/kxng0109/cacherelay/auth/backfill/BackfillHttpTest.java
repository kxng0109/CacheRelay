package io.github.kxng0109.cacherelay.auth.backfill;

import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("BackfillHttp")
class BackfillHttpTest {

	@Test
	@DisplayName("next links extract from single and multiple headers")
	void parseLinkNext() {
		assertThat(BackfillHttp.parseLinkNext(List.of(
				"<https://idp.example/users?after=a>; rel=\"self\","
						+ " <https://idp.example/users?after=b>; rel=\"next\"")))
				.isEqualTo("https://idp.example/users?after=b");
		assertThat(BackfillHttp.parseLinkNext(List.of())).isNull();
		assertThat(BackfillHttp.parseLinkNext(List.of("<https://idp.example/x>; rel=\"self\"")))
				.isNull();
		assertThat(BackfillHttp.parseLinkNext(List.of("not-a-link"))).isNull();
		assertThat(BackfillHttp.parseLinkNext(List.of("<https://idp.example/x>"))).isNull();
	}

	@Test
	@DisplayName("segments encode after allowlist validation")
	void encodeSegment() {
		assertThat(BackfillHttp.encodeSegment("user-1_2.x@y=z")).isEqualTo("user-1_2.x%40y%3Dz");
		assertThatThrownBy(() -> BackfillHttp.encodeSegment("a/b")).isInstanceOf(
				IllegalArgumentException.class);
		assertThatThrownBy(() -> BackfillHttp.encodeSegment("")).isInstanceOf(
				IllegalArgumentException.class);
	}

	@Test
	@DisplayName("payloads parse or throw IO")
	void parseJson() throws Exception {
		assertThat(BackfillHttp.parseJson("{\"a\":1}").path("a").asInt()).isEqualTo(1);
		assertThatThrownBy(() -> BackfillHttp.parseJson("nope"))
				.isInstanceOf(java.io.IOException.class);
	}

	@Test
	@DisplayName("header lookup ignores casing")
	void headerLookup() {
		BackfillHttp.HttpResult result = new BackfillHttp.HttpResult(200, "",
				java.util.Map.of("LiNk", List.of("<https://idp.example/n>; rel=\"next\"")));

		assertThat(result.header("link")).containsExactly("<https://idp.example/n>; rel=\"next\"");
		assertThat(result.header("LINK")).containsExactly("<https://idp.example/n>; rel=\"next\"");
		assertThat(result.header("absent")).isEmpty();
	}
}
