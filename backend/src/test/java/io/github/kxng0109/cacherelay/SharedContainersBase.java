package io.github.kxng0109.cacherelay;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;

import com.redis.testcontainers.RedisContainer;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeEach;
import org.postgresql.ds.PGSimpleDataSource;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.testcontainers.lifecycle.Startables;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

/**
 * One Postgres plus one Redis for the whole test JVM: containers start once
 * on first class load, the full Flyway chain migrates once, and every test
 * method starts from empty data tables and an empty Redis.
 *
 * <p>Replacing ~30 per-class container startups (each with its own Postgres
 * boot plus a full Flyway migrate) is the largest deterministic local speedup
 * available: nothing else about test semantics changes, because truncation
 * plus {@code RESTART IDENTITY} reproduces the fresh-container initial state
 * every method already assumed. The Flyway history table is never truncated,
 * so migrations are not replayed.</p>
 *
 * <p>Rules for subclasses: do not declare your own Postgres/Redis containers
 * (Spring tests wire {@code spring.datasource.url} and
 * {@code spring.data.redis.*} from the accessors via
 * {@code @DynamicPropertySource}; plain JDBC tests build from
 * {@link #newDataSource()}); do not call {@code Flyway.migrate()} yourself;
 * keep your own table cleanup (harmless duplication). Tests that cannot share
 * (custom images, auth, graceful no-Docker skip) keep their own containers
 * and do not extend this base.</p>
 */
public abstract class SharedContainersBase {

	private static final PostgreSQLContainer POSTGRES =
			new PostgreSQLContainer(DockerImageName.parse("postgres:16.15-alpine"));

	private static final RedisContainer REDIS =
			new RedisContainer(DockerImageName.parse("redis:8.10.1-alpine3.23"));

	private static final LettuceConnectionFactory REDIS_FACTORY;

	static {
		Startables.deepStart(POSTGRES, REDIS).join();
		LettuceConnectionFactory factory =
				new LettuceConnectionFactory(REDIS.getHost(), REDIS.getMappedPort(6379));
		factory.afterPropertiesSet();
		REDIS_FACTORY = factory;
		Flyway.configure()
				.dataSource(postgresJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())
				.load()
				.migrate();
	}

	/**
	 * @return shared Postgres JDBC URL, never {@code null}
	 */
	public static String postgresJdbcUrl() {
		return POSTGRES.getJdbcUrl();
	}

	/**
	 * @return shared Postgres host, never {@code null}
	 */
	public static String postgresHost() {
		return POSTGRES.getHost();
	}

	/**
	 * @return shared Postgres mapped port
	 */
	public static int postgresPort() {
		return POSTGRES.getMappedPort(5432);
	}

	/**
	 * @return shared Postgres database name, never {@code null}
	 */
	public static String postgresDatabase() {
		return POSTGRES.getDatabaseName();
	}

	/**
	 * @return shared Postgres username, never {@code null}
	 */
	public static String postgresUsername() {
		return POSTGRES.getUsername();
	}

	/**
	 * @return shared Postgres password, never {@code null}
	 */
	public static String postgresPassword() {
		return POSTGRES.getPassword();
	}

	/**
	 * @return shared Redis host, never {@code null}
	 */
	public static String redisHost() {
		return REDIS.getHost();
	}

	/**
	 * @return shared Redis mapped port
	 */
	public static int redisPort() {
		return REDIS.getMappedPort(6379);
	}

	/**
	 * Pauses the shared Redis container (chaos tests only): established
	 * connections stall while the mapped port stays stable. Always paired
	 * with {@link #unpauseRedis()} in a {@code finally} block so later
	 * classes never inherit a paused container.
	 */
	public static void pauseRedis() {
		REDIS.getDockerClient().pauseContainerCmd(REDIS.getContainerId()).exec();
	}

	/**
	 * Unpauses the shared Redis container after {@link #pauseRedis()}.
	 */
	public static void unpauseRedis() {
		REDIS.getDockerClient().unpauseContainerCmd(REDIS.getContainerId()).exec();
	}

	/**
	 * Builds a data source against the shared container, mirroring the
	 * per-class setup this base replaces.
	 *
	 * @return connected data source, never {@code null}
	 */
	public static PGSimpleDataSource newDataSource() {		PGSimpleDataSource dataSource = new PGSimpleDataSource();
		dataSource.setServerNames(new String[]{postgresHost()});
		dataSource.setPortNumbers(new int[]{postgresPort()});
		dataSource.setDatabaseName(postgresDatabase());
		dataSource.setUser(postgresUsername());
		dataSource.setPassword(postgresPassword());
		return dataSource;
	}

	/**
	 * Resets shared state before every test: all data tables truncated (Flyway
	 * history preserved) and the shared Redis flushed.
	 */
	@BeforeEach
	void resetSharedState() {
		truncateTables();
		REDIS_FACTORY.getConnection().serverCommands().flushDb();
	}

	private static void truncateTables() {
		List<String> tables = tableNames();
		String sql = "TRUNCATE " + String.join(", ", tables) + " RESTART IDENTITY CASCADE";
		try (Connection connection = newDataSource().getConnection();
				Statement truncate = connection.createStatement()) {
			truncate.execute(sql);
		} catch (SQLException failed) {
			throw new IllegalStateException("Shared Postgres truncate failed", failed);
		}
	}

	private static List<String> tableNames() {
		List<String> tables = new ArrayList<>();
		try (Connection connection = newDataSource().getConnection();
				Statement query = connection.createStatement();
				// flyway_schema_history keeps migrations applied exactly once per JVM;
				// model_pricing carries V2/V5 migration seeds that seed-dependent
				// tests read (no test asserts pricing absence, so upsert leakage
				// from refresh tests is presence-harmless). Any future migration
				// seed table must be added to this exclusion list.
				ResultSet rows = query.executeQuery(
						"SELECT tablename FROM pg_tables WHERE schemaname = 'public'"
								+ " AND tablename NOT IN ('flyway_schema_history', 'model_pricing')")) {
			while (rows.next()) {
				tables.add(rows.getString(1));
			}
		} catch (SQLException failed) {
			throw new IllegalStateException("Shared Postgres truncate failed", failed);
		}
		return tables;
	}
}
