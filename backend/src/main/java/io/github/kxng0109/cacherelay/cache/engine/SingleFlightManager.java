package io.github.kxng0109.cacherelay.cache.engine;

import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * In-flight request deduplication engine (SingleFlight pattern) preventing cache stampedes and upstream provider
 * throttling under high concurrency (2,000+ requests/sec).
 */
@Slf4j
@Component
public class SingleFlightManager {

	private final ConcurrentMap<String, CompletableFuture<?>> inFlight = new ConcurrentHashMap<>();

	/**
	 * Executes the loader for the given key, deduplicating concurrent executions so only one leader thread computes
	 * while follower threads wait and share the result.
	 *
	 * @param key    deduplication key (e.g. compound hash)
	 * @param loader computation loader to execute if this thread is the leader
	 * @param <T>    result type
	 * @return result of the computation
	 * @throws Exception if computation fails
	 */
	/**
	 * Executes the loader, blocking indefinitely for followers (legacy behavior).
	 *
	 * @param key    deduplication key (e.g. compound hash)
	 * @param loader computation loader to execute if this thread is the leader
	 * @param <T>    result type
	 * @return result of the computation
	 * @throws Exception if computation fails
	 */
	public <T> T execute(String key, Callable<T> loader) throws Exception {
		return execute(key, loader, null);
	}

	/**
	 * Executes the loader, bounding how long followers wait (PERF-08): a hung leader
	 * no longer leaks blocked virtual threads — followers time out and the flight entry
	 * is removed so a fresh flight can start. The leader always returns its own result
	 * directly instead of re-joining its future.
	 *
	 * @param key     deduplication key (e.g. compound hash)
	 * @param loader  computation loader to execute if this thread is the leader
	 * @param timeout follower wait bound, or {@code null} to wait indefinitely
	 * @param <T>     result type
	 * @return result of the computation
	 * @throws Exception if computation fails, or {@link TimeoutException} on follower timeout
	 */
	@SuppressWarnings("unchecked")
	public <T> T execute(String key, Callable<T> loader, @Nullable Duration timeout) throws Exception {
		boolean[] isLeader = new boolean[]{false};
		CompletableFuture<T> future = (CompletableFuture<T>) inFlight.computeIfAbsent(
				key, k -> {
					isLeader[0] = true;
					return new CompletableFuture<T>();
				}
		);

		if (isLeader[0]) {
			try {
				T result = loader.call();
				future.complete(result);
				return result;
			} catch (Throwable t) {
				future.completeExceptionally(t);
				if (t instanceof Exception e) {
					throw e;
				}
				throw new RuntimeException(t);
			} finally {
				inFlight.remove(key, future);
			}
		}

		try {
			if (timeout == null) {
				return future.join();
			}
			return future.get(timeout.toMillis(), TimeUnit.MILLISECONDS);
		} catch (CompletionException | ExecutionException ex) {
			Throwable cause = ex.getCause() != null ? ex.getCause() : ex;
			if (cause instanceof Exception e) {
				throw e;
			}
			throw new RuntimeException(cause);
		} catch (TimeoutException ex) {
			inFlight.remove(key, future);
			throw ex;
		} catch (InterruptedException ex) {
			Thread.currentThread().interrupt();
			throw new RuntimeException(ex);
		}
	}
}
