package io.github.kxng0109.cacherelay.budget;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.Connection;
import java.sql.SQLException;

import io.github.kxng0109.cacherelay.SharedContainersBase;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * FIN-B19 against real PostgreSQL: transaction-scoped advisory locks cannot
 * leak onto a pooled connection — rollback releases them, so a failed unlock
 * can never wedge the sweeper/detector/janitor on an instance.
 */
@DisplayName("FIN-B19 xact advisory locks auto-release")
class AdvisoryLockXactIT extends SharedContainersBase {

	@Test
	@DisplayName("rollback releases the lock for the next holder")
	void rollbackReleasesLock() throws SQLException {
		try (Connection first = SharedContainersBase.newDataSource().getConnection();
				Connection second = SharedContainersBase.newDataSource().getConnection()) {
			first.setAutoCommit(false);
			second.setAutoCommit(false);
			try {
				assertThat(AdvisoryLock.tryLockXact(first, "fin-b19-lock")).isTrue();
				assertThat(AdvisoryLock.tryLockXact(second, "fin-b19-lock"))
						.as("second holder waits while the first transaction is open")
						.isFalse();
				first.rollback();
				assertThat(AdvisoryLock.tryLockXact(second, "fin-b19-lock"))
						.as("rollback auto-releases; no explicit unlock needed")
						.isTrue();
				second.rollback();
			} finally {
				rollbackQuietly(first);
				rollbackQuietly(second);
			}
		}
	}

	private static void rollbackQuietly(Connection connection) {
		try {
			connection.rollback();
		} catch (SQLException ignored) {
		}
	}
}
