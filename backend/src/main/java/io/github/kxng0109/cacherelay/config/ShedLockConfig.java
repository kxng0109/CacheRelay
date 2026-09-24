package io.github.kxng0109.cacherelay.config;

import javax.sql.DataSource;

import net.javacrumbs.shedlock.core.LockProvider;
import net.javacrumbs.shedlock.provider.jdbctemplate.JdbcTemplateLockProvider;
import net.javacrumbs.shedlock.spring.annotation.EnableSchedulerLock;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Distributed lock provider for single-holder scheduled jobs: lock rows live
 * in Postgres with database time, so sibling instances skip instead of
 * duplicating work regardless of app-clock skew.
 */
@Configuration
@EnableSchedulerLock(defaultLockAtMostFor = "10m")
public class ShedLockConfig {

	/**
	 * Creates the Postgres-backed lock provider.
	 *
	 * @param dataSource primary data source, never {@code null}
	 * @return lock provider, never {@code null}
	 */
	@Bean
	public LockProvider lockProvider(DataSource dataSource) {
		return new JdbcTemplateLockProvider(JdbcTemplateLockProvider.Configuration.builder()
				.withJdbcTemplate(new JdbcTemplate(dataSource))
				.usingDbTime()
				.build());
	}
}
