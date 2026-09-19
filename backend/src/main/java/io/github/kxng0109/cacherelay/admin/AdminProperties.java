package io.github.kxng0109.cacherelay.admin;

import io.github.kxng0109.cacherelay.config.SensitiveString;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * Configuration properties for the administrative control plane under {@code /v1/admin/**}.
 *
 * <p>The master key is injected exclusively from the runtime environment via
 * {@code GATEWAY_ADMIN_MASTERKEY} (or an equivalent secret-manager injection).
 * No default value is provided: binding validates at startup via {@link Validated}
 * and the application fails fast when the key is absent, blank, shorter than
 * 32 bytes, or equal to a published default. The value is held in a
 * {@link SensitiveString} so it never appears in logs or diagnostics.</p>
 *
 * @since 1.7.0
 */
@Getter
@Setter
@Validated
@ConfigurationProperties(prefix = "gateway.admin")
public class AdminProperties {

	/**
	 * Master admin secret for the control plane.
	 */
	@NotNull(message = "GATEWAY_ADMIN_MASTERKEY is required; provide a 32+ byte secret via the environment or a secret manager")
	@Valid
	@AdminMasterKey
	private SensitiveString masterKey;
}
