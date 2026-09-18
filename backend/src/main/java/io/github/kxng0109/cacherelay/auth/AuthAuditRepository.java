package io.github.kxng0109.cacherelay.auth;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;

/**
 * Persistence for {@link AuthAuditEvent}, with severity/action filtering for operators
 * and failure counting for login lockout.
 */
public interface AuthAuditRepository extends JpaRepository<AuthAuditEvent, UUID> {

	/**
	 * Lists events of one severity, newest first.
	 *
	 * @param severity {@code INFO}, {@code WARN}, {@code ERROR}, or {@code CRITICAL}
	 * @return matching events newest first
	 */
	List<AuthAuditEvent> findBySeverityOrderByOccurredAtDesc(String severity);

	/**
	 * Lists events of one action type, newest first.
	 *
	 * @param action event type, e.g. {@code ADMIN_LOGIN}
	 * @return matching events newest first
	 */
	List<AuthAuditEvent> findByActionOrderByOccurredAtDesc(String action);

	/**
	 * Counts recent failures for lockout decisions.
	 *
	 * @param actorHash actor keyed hash
	 * @param action    event type, e.g. {@code LOCAL_LOGIN}
	 * @param since     window start
	 * @return failure count inside the window
	 */
	long countByActorHashAndActionAndOutcomeAndOccurredAtAfter(String actorHash, String action,
			String outcome, Instant since);

	/**
	 * Deletes events past the retention horizon (janitor).
	 *
	 * @param cutoff events before this are deleted
	 * @return deleted rows
	 */
	@Modifying
	@Query("DELETE FROM AuthAuditEvent e WHERE e.occurredAt < :cutoff")
	int deleteBefore(Instant cutoff);
}
