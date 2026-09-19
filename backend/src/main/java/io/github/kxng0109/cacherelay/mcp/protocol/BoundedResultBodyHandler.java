package io.github.kxng0109.cacherelay.mcp.protocol;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.http.HttpResponse;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Flow;

/**
 * Byte-capped {@code String} body handler for MCP upstream calls (PERF-11): a misbehaving
 * tool server must not OOM the gateway by streaming an unbounded result. Bytes are counted
 * as they arrive and the subscription is cancelled the moment the cap is exceeded, so heap
 * never holds more than the cap plus one chunk; the pending read then fails with an
 * {@link IOException} that the controller maps to a 502.
 *
 * @since 1.7.0
 */
public final class BoundedResultBodyHandler implements HttpResponse.BodyHandler<String> {

	private final int maxBytes;

	/**
	 * Creates a handler with the given cap.
	 *
	 * @param maxBytes maximum response bytes to buffer (must be {@code > 0})
	 */
	public BoundedResultBodyHandler(int maxBytes) {
		if (maxBytes <= 0) {
			throw new IllegalArgumentException("maxBytes must be > 0, was " + maxBytes);
		}
		this.maxBytes = maxBytes;
	}

	@Override
	public HttpResponse.BodySubscriber<String> apply(HttpResponse.ResponseInfo responseInfo) {
		CompletableFuture<String> result = new CompletableFuture<>();
		ByteArrayOutputStream buffer = new ByteArrayOutputStream();
		return new HttpResponse.BodySubscriber<>() {
			private volatile Flow.Subscription subscription;

			@Override
			public void onSubscribe(Flow.Subscription newSubscription) {
				this.subscription = newSubscription;
				newSubscription.request(Long.MAX_VALUE);
			}

			@Override
			public void onNext(List<ByteBuffer> buffers) {
				long incoming = 0;
				for (ByteBuffer chunk : buffers) {
					incoming += chunk.remaining();
				}
				if (buffer.size() + incoming > maxBytes) {
					Flow.Subscription current = subscription;
					if (current != null) {
						current.cancel();
					}
					result.completeExceptionally(new IOException(
							"MCP result body exceeds " + maxBytes + " bytes"));
					return;
				}
				for (ByteBuffer chunk : buffers) {
					byte[] bytes = new byte[chunk.remaining()];
					chunk.get(bytes);
					buffer.write(bytes, 0, bytes.length);
				}
			}

			@Override
			public void onError(Throwable throwable) {
				result.completeExceptionally(throwable);
			}

			@Override
			public void onComplete() {
				result.complete(buffer.toString(StandardCharsets.UTF_8));
			}

			@Override
			public CompletionStage<String> getBody() {
				return result;
			}
		};
	}
}
