package io.github.kxng0109.cacherelay.infra;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.yaml.snakeyaml.Yaml;

import java.io.IOException;
import java.io.Reader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("DockerCompose infrastructure exposure and auth")
class DockerComposeSecurityTest {

	private static Map<String, Object> services;
	private static String rawCompose;

	@BeforeAll
	@SuppressWarnings("unchecked")
	static void loadCompose() throws IOException {
		Path compose = Paths.get(System.getProperty("user.dir"), "..", "docker-compose.yml");
		assertThat(Files.exists(compose)).as("repo docker-compose.yml resolves").isTrue();
		rawCompose = Files.readString(compose);
		Map<String, Object> root;
		try (Reader reader = Files.newBufferedReader(compose)) {
			root = new Yaml().load(reader);
		}
		services = (Map<String, Object>) root.get("services");
		assertThat(services).isNotEmpty();
	}

	@SuppressWarnings("unchecked")
	private static List<String> portsOf(String service) {
		Map<String, Object> svc = (Map<String, Object>) services.get(service);
		assertThat(svc).as("service " + service + " exists").isNotNull();
		Object ports = svc.get("ports");
		if (ports == null) {
			return List.of();
		}
		return (List<String>) ports;
	}

	@Test
	@DisplayName("only the app publishes a non-loopback port")
	void onlyAppOnPublicInterface() {
		for (String service : services.keySet()) {
			for (String mapping : portsOf(service)) {
				if (service.equals("cacherelay")) {
					assertThat(mapping).as("app stays reachable").doesNotStartWith("127.0.0.1:");
				} else {
					assertThat(mapping).as(service + " must bind loopback").startsWith("127.0.0.1:");
				}
			}
		}
	}

	@Test
	@DisplayName("Redis tiers require a password from the environment")
	void redisRequiresPassword() {
		assertThat(rawCompose).contains("--requirepass ${REDIS_PASSWORD:?");
		assertThat(rawCompose).contains("--requirepass ${REDIS_CACHE_PASSWORD:?");
	}

	@Test
	@DisplayName("no shipped default secrets for admin key or Postgres")
	void noDefaultSecrets() {
		assertThat(rawCompose).doesNotContain("cacherelay_admin_secret_key");
		assertThat(rawCompose).doesNotContain("POSTGRES_PASSWORD:-");
		assertThat(rawCompose).doesNotContain("POSTGRES_PASSWORD: ${POSTGRES_PASSWORD:-cacherelay_secret}");
	}

	@Test
	@DisplayName("Postgres password is hard-required with no default")
	void postgresPasswordHardRequired() {
		assertThat(rawCompose).contains("POSTGRES_PASSWORD:?set POSTGRES_PASSWORD in .env");
	}

	@Test
	@DisplayName("no bare empty defaults: compose rejects ${VAR:} interpolation")
	void noBareEmptyDefaults() {
		assertThat(rawCompose).doesNotMatch("(?m)\\$\\{[A-Z_]+:\\}");
	}
}
