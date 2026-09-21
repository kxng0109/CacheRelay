package io.github.kxng0109.cacherelay.infra;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.Reader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.yaml.snakeyaml.Yaml;

/**
 * Locks the SEC-15 contract into the Kubernetes manifests: the management port exists
 * on the Deployment (probes target it by name), the Service never maps it, and the
 * allow policy admits it from the monitoring namespace only.
 */
@DisplayName("Kubernetes manifest management-port isolation (SEC-15)")
class K8sManifestSecurityTest {

	private static final Path K8S_DIR =
			Paths.get(System.getProperty("user.dir"), "deploy", "k8s");

	@SuppressWarnings("unchecked")
	private static Map<String, Object> load(String file) throws IOException {
		Path path = K8S_DIR.resolve(file);
		assertThat(Files.exists(path)).as(file + " resolves").isTrue();
		try (Reader reader = Files.newBufferedReader(path)) {
			return new Yaml().load(reader);
		}
	}

	@Test
	@DisplayName("Deployment declares the management port and probes target it")
	@SuppressWarnings("unchecked")
	void deploymentProbesManagementPort() throws IOException {
		Map<String, Object> deployment = load("deployment.yaml");
		Map<String, Object> spec = (Map<String, Object>) deployment.get("spec");
		Map<String, Object> template = (Map<String, Object>) spec.get("template");
		Map<String, Object> podSpec = (Map<String, Object>) ((Map<String, Object>) template.get("spec"));
		List<Map<String, Object>> containers = (List<Map<String, Object>>) podSpec.get("containers");
		Map<String, Object> container = containers.getFirst();

		List<Map<String, Object>> ports = (List<Map<String, Object>>) container.get("ports");
		assertThat(ports).anySatisfy(port -> {
			assertThat(port.get("name")).isEqualTo("management");
			assertThat(port.get("containerPort")).isEqualTo(9091);
		});

		Map<String, Object> liveness = (Map<String, Object>) container.get("livenessProbe");
		Map<String, Object> readiness = (Map<String, Object>) container.get("readinessProbe");
		assertThat(((Map<String, Object>) liveness.get("httpGet")).get("port")).isEqualTo("management");
		assertThat(((Map<String, Object>) readiness.get("httpGet")).get("port")).isEqualTo("management");
	}

	@Test
	@DisplayName("Service never maps the management port")
	@SuppressWarnings("unchecked")
	void serviceDoesNotMapManagementPort() throws IOException {
		Map<String, Object> service = load("service.yaml");
		Map<String, Object> spec = (Map<String, Object>) service.get("spec");
		List<Map<String, Object>> ports = (List<Map<String, Object>>) spec.get("ports");

		assertThat(ports).noneSatisfy(port -> assertThat(port.get("port")).isEqualTo(9091));
	}

	@Test
	@DisplayName("Allow policy admits the management port from the monitoring namespace only")
	@SuppressWarnings("unchecked")
	void allowPolicyAdmitsManagementPortFromMonitoring() throws IOException {
		Map<String, Object> policy = load("networkpolicy-allow.yaml");
		Map<String, Object> spec = (Map<String, Object>) policy.get("spec");
		List<Map<String, Object>> ingress = (List<Map<String, Object>>) spec.get("ingress");

		Map<String, Object> managementRule = ingress.stream()
				.filter(rule -> ((List<Map<String, Object>>) rule.get("ports")).stream()
						.anyMatch(port -> Integer.valueOf(9091).equals(port.get("port"))))
				.findFirst()
				.orElseThrow(() -> new AssertionError("no ingress rule admits port 9091"));

		List<Map<String, Object>> sources = (List<Map<String, Object>>) managementRule.get("from");
		assertThat(sources).isNotEmpty();
		assertThat(sources).allSatisfy(source -> {
			Map<String, Object> selector = (Map<String, Object>) source.get("namespaceSelector");
			assertThat(selector).isNotNull();
			Map<String, Object> labels = (Map<String, Object>) selector.get("matchLabels");
			assertThat(labels).containsEntry("app.kubernetes.io/name", "monitoring");
		});
	}
}
