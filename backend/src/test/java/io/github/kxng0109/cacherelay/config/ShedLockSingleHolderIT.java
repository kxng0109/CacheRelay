package io.github.kxng0109.cacherelay.config;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import javax.sql.DataSource;

import io.github.kxng0109.cacherelay.SharedContainersBase;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.stereotype.Component;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Proves the ShedLock single-holder contract against the shared Postgres:
 * two concurrent contenders for one lock name execute the guarded body
 * exactly once; the loser skips instead of duplicating work.
 */
@DisplayName("ShedLock single-holder lock")
class ShedLockSingleHolderIT extends SharedContainersBase {

	@Test
	@DisplayName("concurrent contenders execute the guarded body once")
	void concurrentContendersExecuteOnce() throws Exception {
		AnnotationConfigApplicationContext context = new AnnotationConfigApplicationContext();
		context.registerBean(DataSource.class, SharedContainersBase::newDataSource);
		context.register(ShedLockConfig.class, GuardedWorker.class);
		context.refresh();
		GuardedWorker worker = context.getBean(GuardedWorker.class);
		try {
			CountDownLatch entered = new CountDownLatch(1);
			CountDownLatch release = new CountDownLatch(1);
			worker.gate(entered, release);
			ExecutorService pool = Executors.newVirtualThreadPerTaskExecutor();
			try {
				var first = pool.submit(worker::guarded);
				assertThat(entered.await(10, TimeUnit.SECONDS)).isTrue();
				var second = pool.submit(worker::guarded);
				Thread.sleep(500);
				release.countDown();
				first.get(30, TimeUnit.SECONDS);
				second.get(30, TimeUnit.SECONDS);
			} finally {
				pool.shutdownNow();
			}

			assertThat(worker.executions()).isEqualTo(1);
		} finally {
			context.close();
		}
	}

	@Component
	static class GuardedWorker {
		private final AtomicInteger executions = new AtomicInteger();

		private volatile CountDownLatch entered;

		private volatile CountDownLatch release;

		void gate(CountDownLatch entered, CountDownLatch release) {
			this.entered = entered;
			this.release = release;
		}

		int executions() {
			return executions.get();
		}

		@SchedulerLock(name = "test-single-holder", lockAtMostFor = "1m",
				lockAtLeastFor = "1m")
		public void guarded() {
			executions.incrementAndGet();
			try {
				entered.countDown();
				assertThat(release.await(10, TimeUnit.SECONDS)).isTrue();
			} catch (InterruptedException interrupted) {
				Thread.currentThread().interrupt();
			}
		}
	}
}
