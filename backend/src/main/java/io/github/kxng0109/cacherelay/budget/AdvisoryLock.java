package io.github.kxng0109.cacherelay.budget;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Cross-pod single-flight via PostgreSQL advisory locks.
 *
 * <p>Prefer {@link #tryLockXact}: transaction-scoped locks release implicitly
 * at commit/rollback, so a failed unlock can never leak a session lock onto a
 * pooled connection and wedge the holder's job (FIN-B19). The session-level
 * pair below remains for callers that manage dedicated connections; a failed
 * session unlock still only lapses with the session.
 *
 * <p>Locks live in the session (or transaction), not the table: a pod crash or
 * failover releases them implicitly, so a dead leader can never wedge
 * followers. All coordinated work must therefore be idempotent (it will
 * occasionally run twice across a failover).</p>
 */
public final class AdvisoryLock {

	private static final Logger log = LoggerFactory.getLogger(AdvisoryLock.class);

	private AdvisoryLock() {
	}

	/**
	 * Tries to acquire the named lock without waiting, scoped to the caller's
	 * transaction: commit or rollback releases it implicitly, so no explicit
	 * unlock (and no unlock failure) can leak it onto a pooled connection.
	 * The caller must disable auto-commit and end the transaction (commit on
	 * success, rollback on failure) for the release to happen.
	 *
	 * @param connection live JDBC connection with auto-commit disabled
	 * @param name       lock name (hashed to 64 bits; distinct names never contend)
	 * @return {@code true} when this pod holds the lock for its transaction
	 * @throws SQLException when the lock statement itself fails
	 */
	public static boolean tryLockXact(Connection connection, String name) throws SQLException {
		try (PreparedStatement statement = connection.prepareStatement(
				"SELECT pg_try_advisory_xact_lock(hashtextextended(?, 0))")) {
			statement.setString(1, name);
			try (ResultSet rows = statement.executeQuery()) {
				return rows.next() && rows.getBoolean(1);
			}
		}
	}

	/**
	 * Tries to acquire the named lock without waiting.
	 *
	 * @param connection live JDBC connection held for the whole critical section
	 * @param name       lock name (hashed to 64 bits; distinct names never contend)
	 * @return {@code true} when this pod holds the lock
	 */
	public static boolean tryLock(Connection connection, String name) throws SQLException {
		try (PreparedStatement statement = connection.prepareStatement(
				"SELECT pg_try_advisory_lock(hashtextextended(?, 0))")) {
			statement.setString(1, name);
			try (ResultSet rows = statement.executeQuery()) {
				return rows.next() && rows.getBoolean(1);
			}
		}
	}

	/**
	 * Releases the named lock. Best-effort: a failed unlock only leaks until the session closes.
	 */
	public static void unlock(Connection connection, String name) {
		try (PreparedStatement statement = connection.prepareStatement(
				"SELECT pg_advisory_unlock(hashtextextended(?, 0))")) {
			statement.setString(1, name);
			statement.execute();
		} catch (SQLException ex) {
			log.warn("Advisory unlock failed for '{}' (lock lapses with the session)", name);
		}
	}
}
