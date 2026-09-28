package io.github.kxng0109.cacherelay.config;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.List;

import io.github.kxng0109.cacherelay.admin.AlertWebhookProperties;
import io.github.kxng0109.cacherelay.contracts.GatewayProperties;
import org.jspecify.annotations.Nullable;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

/**
 * Startup fail-fasts for deployment safety (FS-B17), evaluated before the
 * application serves traffic.
 *
 * <ul>
 *   <li>Bootstrap keys are hard-required outside {@code dev}/{@code test}:
 *       an operator that forgot to configure any initial key gets a loud
 *       boot failure, not a silently keyless gateway.</li>
 *   <li>The Alertmanager webhook secret is hard-required outside
 *       {@code dev}/{@code test}: without it the dedicated receiver would
 *       answer 404 and pages would be dropped silently.</li>
 *   <li>The {@code dev}/{@code test} profiles refuse non-loopback binds:
 *       development bootstrap keys are low-value conveniences, safe only
 *       while unreachable from the network (the compose stack binds
 *       loopback by default).</li>
 * </ul>
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class StartupGuard {

	private final Environment environment;

	private final GatewayProperties gatewayProperties;

	private final AlertWebhookProperties alertWebhookProperties;

	/**
	 * Creates the guard.
	 *
	 * @param environment       active Spring profiles and bind host, never {@code null}
	 * @param gatewayProperties bound gateway configuration, never {@code null}
	 * @param alertWebhookProperties alert webhook configuration, never {@code null}
	 */
	public StartupGuard(Environment environment, GatewayProperties gatewayProperties,
	                    AlertWebhookProperties alertWebhookProperties) {
		this.environment = environment;
		this.gatewayProperties = gatewayProperties;
		this.alertWebhookProperties = alertWebhookProperties;
	}

	/**
	 * Evaluates the deployment guards once the application is ready.
	 *
	 * @throws IllegalStateException when a guard fails
	 */
	@EventListener(ApplicationReadyEvent.class)
	public void guard() {
		List<String> profiles = Arrays.asList(environment.getActiveProfiles());
		boolean relaxed = profiles.contains("dev") || profiles.contains("test");
		if (!relaxed && gatewayProperties.getBootstrapKeys().isEmpty()) {
			throw new IllegalStateException(
					"No bootstrap keys configured outside dev/test: set GATEWAY_BOOTSTRAPKEYS_*"
							+ " (init-env generates them) — refusing to serve a keyless gateway");
		}
		if (!relaxed) {
			String secret = alertWebhookProperties.getWebhookSecret();
			if (secret == null || secret.isBlank()
					|| secret.getBytes(StandardCharsets.UTF_8).length < 32) {
				throw new IllegalStateException(
						"No Alertmanager webhook secret configured outside dev/test: set "
								+ "GATEWAY_ALERTS_WEBHOOK_SECRET to a 32+ byte secret"
								+ " (init-env generates it) — refusing to run a gateway that drops pages");
			}
		}
		if (relaxed && !isLoopback(environment.getProperty("GATEWAY_BIND_HOST", "127.0.0.1"))) {
			throw new IllegalStateException(
					"dev/test profile refuses non-loopback bind "
							+ environment.getProperty("GATEWAY_BIND_HOST")
							+ ": development keys must stay unreachable from the network");
		}
	}

	private static boolean isLoopback(@Nullable String host) {
		return host == null || host.isBlank()
				|| host.equals("127.0.0.1")
				|| host.equals("::1")
				|| host.equalsIgnoreCase("localhost");
	}
}
