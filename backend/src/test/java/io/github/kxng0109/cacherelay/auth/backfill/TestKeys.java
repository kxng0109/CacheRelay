package io.github.kxng0109.cacherelay.auth.backfill;

import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.util.Base64;

/**
 * Test-only RSA material: one generated keypair with its PKCS8 PEM encoding.
 */
final class TestKeys {

	static final KeyPair RSA = generate();

	static final String RSA_PRIVATE_PEM = pem(RSA);

	private TestKeys() {
	}

	private static KeyPair generate() {
		try {
			KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
			generator.initialize(2048);
			return generator.generateKeyPair();
		} catch (Exception failed) {
			throw new IllegalStateException("Test RSA generation failed", failed);
		}
	}

	private static String pem(KeyPair pair) {
		String encoded = Base64.getMimeEncoder(64, new byte[]{'\n'})
				.encodeToString(pair.getPrivate().getEncoded());
		return "-----BEGIN PRIVATE KEY-----\n" + encoded + "\n-----END PRIVATE KEY-----";
	}
}
