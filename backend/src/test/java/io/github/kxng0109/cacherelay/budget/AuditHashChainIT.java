package io.github.kxng0109.cacherelay.budget;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.postgresql.ds.PGSimpleDataSource;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;

import io.github.kxng0109.cacherelay.SharedContainersBase;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Proves the audit hash chain (V13) against real PostgreSQL: the full Flyway chain migrates (validating the
 * backfill DO block and both triggers), inserts link automatically (prev GENESIS then chained), the unique
 * predecessor holds, and mutation stays blocked by the V8 trigger.
 */
@DisplayName("Audit hash chain")
class AuditHashChainIT extends SharedContainersBase {

	private JdbcTemplate jdbc;

	@BeforeEach
	void migrate() {
		PGSimpleDataSource dataSource = SharedContainersBase.newDataSource();
		jdbc = new JdbcTemplate(dataSource);
		jdbc.execute("TRUNCATE budget_audit");
	}

	@Test
	@DisplayName("inserts chain automatically from GENESIS")
	void insertsChainAutomatically() {
		UUID first = UUID.randomUUID();
		UUID second = UUID.randomUUID();
		jdbc.update(
				"INSERT INTO budget_audit (id, actor, action, level, subject_id) VALUES (?, 'admin', 'CREATE', 'TEAM', 't-a')",
				first);
		jdbc.update(
				"INSERT INTO budget_audit (id, actor, action, level, subject_id) VALUES (?, 'admin', 'UPDATE', 'TEAM', 't-a')",
				second);

		List<Map<String, Object>> rows = jdbc.queryForList(
				"SELECT id, prev_hash, row_hash FROM budget_audit ORDER BY created_at, id");
		assertThat(rows).hasSize(2);
		String firstPrev = (String) rows.get(0).get("prev_hash");
		String firstRow = (String) rows.get(0).get("row_hash");
		String secondPrev = (String) rows.get(1).get("prev_hash");
		String secondRow = (String) rows.get(1).get("row_hash");
		assertThat(firstPrev).isEqualTo("GENESIS");
		assertThat(firstRow).isNotBlank().hasSize(64);
		assertThat(secondPrev).isEqualTo(firstRow);
		assertThat(secondRow).isNotBlank().isNotEqualTo(firstRow);
	}

	@Test
	@DisplayName("updates stay blocked on chained rows")
	void updatesStayBlocked() {
		UUID id = UUID.randomUUID();
		jdbc.update(
				"INSERT INTO budget_audit (id, actor, action, level, subject_id) VALUES (?, 'admin', 'CREATE', 'TEAM', 't-a')",
				id);

		assertThatThrownBy(() -> jdbc.update("UPDATE budget_audit SET actor = 'mallory' WHERE id = ?", id))
				.isInstanceOf(DataAccessException.class)
				.hasStackTraceContaining("append-only");
	}

	@Test
	@DisplayName("FIN-B35: a future-dated row never wedges later inserts")
	void skewedClockDoesNotWedgeChain() {
		UUID first = UUID.randomUUID();
		UUID skewed = UUID.randomUUID();
		UUID third = UUID.randomUUID();
		UUID fourth = UUID.randomUUID();
		Timestamp now = Timestamp.from(Instant.now());
		Timestamp future = Timestamp.from(Instant.now().plusSeconds(3600L));
		jdbc.update(
				"INSERT INTO budget_audit (id, actor, action, level, subject_id, created_at)"
						+ " VALUES (?, 'admin', 'CREATE', 'TEAM', 't-a', ?)",
				first, now);
		jdbc.update(
				"INSERT INTO budget_audit (id, actor, action, level, subject_id, created_at)"
						+ " VALUES (?, 'admin', 'UPDATE', 'TEAM', 't-a', ?)",
				skewed, future);
		jdbc.update(
				"INSERT INTO budget_audit (id, actor, action, level, subject_id, created_at)"
						+ " VALUES (?, 'admin', 'UPDATE', 'TEAM', 't-a', ?)",
				third, now);
		jdbc.update(
				"INSERT INTO budget_audit (id, actor, action, level, subject_id, created_at)"
						+ " VALUES (?, 'admin', 'UPDATE', 'TEAM', 't-a', ?)",
				fourth, now);

		List<Map<String, Object>> rows = jdbc.queryForList(
				"SELECT id, prev_hash, row_hash FROM budget_audit ORDER BY created_at, id");
		assertThat(rows).hasSize(4);
		Map<String, String> prevById = new HashMap<>();
		Map<String, String> rowById = new HashMap<>();
		for (Map<String, Object> row : rows) {
			prevById.put(row.get("id").toString(), (String) row.get("prev_hash"));
			rowById.put(row.get("id").toString(), (String) row.get("row_hash"));
		}
		assertThat(prevById.get(third.toString()))
				.as("insertion order, not clock order")
				.isEqualTo(rowById.get(skewed.toString()));
		assertThat(prevById.get(fourth.toString()))
				.as("tip follows the true latest row")
				.isEqualTo(rowById.get(third.toString()));
	}

	@Test
	@DisplayName("FIN-B34: tampering with after_json breaks v2 verification")
	void tamperedPayloadBreaksVerification() {
		UUID id = UUID.randomUUID();
		jdbc.update(
				"INSERT INTO budget_audit (id, actor, action, level, subject_id, after_json)"
						+ " VALUES (?, 'admin', 'CREATE', 'TEAM', 't-a', '{\"month_micros\":100}')",
				id);
		assertThat(mismatchedChainV2()).as("clean chain verifies").isEmpty();

		jdbc.execute("ALTER TABLE budget_audit DISABLE TRIGGER trg_budget_audit_immutable");
		try {
			jdbc.update("UPDATE budget_audit SET after_json = '{\"month_micros\":999999}' WHERE id = ?", id);
		} finally {
			jdbc.execute("ALTER TABLE budget_audit ENABLE TRIGGER trg_budget_audit_immutable");
		}

		assertThat(mismatchedChainV2()).as("tampered payload detected").containsExactly(id);
	}

	/**
	 * Recomputes every v2 link from stored values (the documented verification
	 * query): any row whose payload no longer matches its sealed digest is
	 * returned.
	 *
	 * @return ids of rows failing verification, in chain order
	 */
	private List<UUID> mismatchedChainV2() {
		return jdbc.query(
				"SELECT id FROM budget_audit WHERE chain_v2 != encode(sha256(convert_to("
						+ "prev_hash || actor || action || level || subject_id"
						+ " || COALESCE(before_json, '') || COALESCE(after_json, '')"
						+ " || to_char(created_at AT TIME ZONE 'UTC', 'YYYY-MM-DD HH24:MI:SS.US'), 'UTF8')), 'hex')"
						+ " ORDER BY seq",
				(rs, row) -> (UUID) rs.getObject("id"));
	}
}
