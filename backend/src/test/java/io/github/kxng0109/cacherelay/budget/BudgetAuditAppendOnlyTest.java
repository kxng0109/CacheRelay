package io.github.kxng0109.cacherelay.budget;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.postgresql.ds.PGSimpleDataSource;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;

import io.github.kxng0109.cacherelay.SharedContainersBase;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Proves {@code budget_audit} is append-only at the database level (V8 trigger):
 * UPDATE and DELETE abort for every role including the table owner, so chargeback
 * history cannot be rewritten or purged by application code, a compromised admin
 * path, or a stray ORM flush. The full Flyway chain (V1–V8) migrates first, which
 * also proves the trigger definition itself is valid SQL.
 */
@DisplayName("Budget audit append-only trigger")
class BudgetAuditAppendOnlyTest extends SharedContainersBase {

	private JdbcTemplate jdbc;

	@BeforeEach
	void migrate() {
		PGSimpleDataSource dataSource = SharedContainersBase.newDataSource();
		jdbc = new JdbcTemplate(dataSource);
	}

	@Test
	@DisplayName("audit rows reject UPDATE and DELETE")
	void auditRejectsMutation() {
		UUID id = UUID.randomUUID();
		jdbc.update(
				"INSERT INTO budget_audit (id, actor, action, level, subject_id) VALUES (?, 'admin', 'CREATE', 'TEAM', 'tenant-a')",
				id);
		assertEquals(1, jdbc.queryForObject(
				"SELECT COUNT(*) FROM budget_audit WHERE id = ?", Integer.class, id));

		DataAccessException onUpdate = assertThrows(DataAccessException.class, () ->
				jdbc.update("UPDATE budget_audit SET actor = 'mallory' WHERE id = ?", id));
		assertTrue(onUpdate.getMostSpecificCause().getMessage().contains("append-only"));

		DataAccessException onDelete = assertThrows(DataAccessException.class, () ->
				jdbc.update("DELETE FROM budget_audit WHERE id = ?", id));
		assertTrue(onDelete.getMostSpecificCause().getMessage().contains("append-only"));

		assertEquals(1, jdbc.queryForObject(
				"SELECT COUNT(*) FROM budget_audit WHERE id = ?", Integer.class, id));
	}
}
