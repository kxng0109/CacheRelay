package io.github.kxng0109.cacherelay.infra;

import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * Resolves repository file locations for infrastructure tests without coupling
 * to the process working directory (OPS-T2): Maven forks set the
 * {@code basedir} system property to the module directory, which stays correct
 * even when the fork's working directory is overridden; everywhere else the
 * working directory remains the fallback, preserving current behavior.
 */
final class InfraPaths {

	private InfraPaths() {
	}

	/**
	 * Returns the backend module directory.
	 *
	 * @return module directory path, never {@code null}
	 */
	static Path moduleDir() {
		String basedir = System.getProperty("basedir");
		if (basedir != null && !basedir.isBlank()) {
			return Paths.get(basedir);
		}
		return Paths.get(System.getProperty("user.dir"));
	}

	/**
	 * Returns the repository root (parent of the backend module directory).
	 *
	 * @return repository root path, never {@code null}
	 */
	static Path repoRoot() {
		return moduleDir().resolve("..").normalize();
	}
}
