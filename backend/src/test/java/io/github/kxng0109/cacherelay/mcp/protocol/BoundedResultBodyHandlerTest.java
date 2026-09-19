package io.github.kxng0109.cacherelay.mcp.protocol;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.http.HttpClient;
import java.net.http.HttpHeaders;
import java.net.http.HttpResponse;
import java.nio.ByteBuffer;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletionException;
import java.util.concurrent.Flow;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("BoundedResultBodyHandler (PERF-11)")
class BoundedResultBodyHandlerTest {

	private static HttpResponse.ResponseInfo info() {
		return new HttpResponse.ResponseInfo() {
			@Override
			public int statusCode() {
				return 200;
			}

			@Override
			public HttpHeaders headers() {
				return HttpHeaders.of(Map.of(), (name, value) -> true);
			}

			@Override
			public HttpClient.Version version() {
				return HttpClient.Version.HTTP_1_1;
			}
		};
	}

	private static Flow.Subscription subscription() {
		return new Flow.Subscription() {
			@Override
			public void request(long n) {
			}

			@Override
			public void cancel() {
			}
		};
	}

	@Test
	@DisplayName("bodies within the cap assemble")
	void withinCapAssembles() {
		HttpResponse.BodySubscriber<String> subscriber = new BoundedResultBodyHandler(1024).apply(info());
		subscriber.onSubscribe(subscription());
		subscriber.onNext(List.of(ByteBuffer.wrap("{\"ok\":".getBytes()), ByteBuffer.wrap("true}".getBytes())));
		subscriber.onComplete();

		assertThat(subscriber.getBody().toCompletableFuture().join()).isEqualTo("{\"ok\":true}");
	}

	@Test
	@DisplayName("bodies past the cap fail with IOException and never buffer beyond it")
	void overCapFails() {
		HttpResponse.BodySubscriber<String> subscriber = new BoundedResultBodyHandler(1024).apply(info());
		subscriber.onSubscribe(subscription());
		subscriber.onNext(List.of(ByteBuffer.wrap(new byte[1024])));
		subscriber.onNext(List.of(ByteBuffer.wrap(new byte[1])));

		assertThatThrownBy(() -> subscriber.getBody().toCompletableFuture().join())
				.isInstanceOf(CompletionException.class)
				.hasCauseInstanceOf(IOException.class);
	}

	@Test
	@DisplayName("non-positive caps are rejected")
	void nonPositiveCapRejected() {
		assertThatThrownBy(() -> new BoundedResultBodyHandler(0))
				.isInstanceOf(IllegalArgumentException.class);
	}

	@Test
	@DisplayName("upstream errors propagate")
	void upstreamErrorPropagates() {
		HttpResponse.BodySubscriber<String> subscriber = new BoundedResultBodyHandler(1024).apply(info());
		subscriber.onSubscribe(subscription());
		subscriber.onError(new IOException("upstream reset"));

		assertThatThrownBy(() -> subscriber.getBody().toCompletableFuture().join())
				.isInstanceOf(CompletionException.class)
				.hasCauseInstanceOf(IOException.class)
				.hasMessageContaining("upstream reset");
	}
}
