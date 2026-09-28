package io.github.kxng0109.cacherelay.infra;

import java.io.IOException;
import java.nio.file.Files;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Pins the FE-02 packaging contract: the operator console is built from
 * {@code frontend/} and ships inside the gateway JAR ({@code static/}),
 * so the documented production host serves the shell itself.
 *
 * <p>Uses the repo's established file-content pattern: the Dockerfile and
 * the release workflow are build inputs, and asserting their wiring keeps
 * the console from silently dropping out of the image or the release JAR.
 */
@DisplayName("SPA packaging into the gateway image and JAR")
class SpaPackagingTest {

	@Test
	@DisplayName("Dockerfile builds the console and stages it into static/ before packaging")
	void dockerfileBuildsConsoleIntoStatic() throws IOException {
		String dockerfile = Files.readString(InfraPaths.moduleDir().resolve("Dockerfile"));

		assertThat(dockerfile).as("frontend build stage exists").contains("AS frontend");
		assertThat(dockerfile).as("console dependencies install").contains("npm ci");
		assertThat(dockerfile).as("console bundle builds").contains("npm run build");
		assertThat(dockerfile).as("bundle stages into the JAR static dir before package")
				.contains("src/main/resources/static");
	}

	@Test
	@DisplayName("release workflow bundles the console into the JAR and keeps the tarball")
	void releaseBundlesConsoleIntoJar() throws IOException {
		String workflow = Files.readString(InfraPaths.repoRoot().resolve(
				".github/workflows/release.yml"));

		assertThat(workflow).as("console stages into the JAR static dir").contains("src/main/resources/static");
		int bundleStep = workflow.indexOf("src/main/resources/static");
		int packageStep = workflow.indexOf("mvnw -B -ntp package");
		assertThat(bundleStep).as("static staging located").isGreaterThanOrEqualTo(0);
		assertThat(packageStep).as("jar package step located").isGreaterThanOrEqualTo(0);
		assertThat(bundleStep).as("console stages before the JAR packages").isLessThan(packageStep);
		assertThat(workflow).as("documented tarball artifact kept").contains("cacherelay-frontend-");
	}

	@Test
	@DisplayName("compose build context reaches the frontend directory")
	void composeContextReachesFrontend() throws IOException {
		String compose = Files.readString(InfraPaths.repoRoot().resolve("docker-compose.yml"));

		assertThat(compose).as("build context covers the repo root for the frontend stage")
				.containsPattern("(?m)^\\s*context:\\s*\\.\\s*$");
		assertThat(compose).as("dockerfile path stays explicit").contains("backend/Dockerfile");
	}
}
