package io.github.kxng0109.cacherelay.model;

/**
 * Where an effective model alias originates: file-bound configuration or a
 * database row managed through the admin API.
 */
public enum AliasSource {

	/**
	 * Bound from {@code gateway.aliases} configuration. Read-only through the
	 * admin API and always wins on name conflict.
	 */
	FILE,

	/**
	 * Stored in the {@code model_alias} table and managed through the admin
	 * API.
	 */
	DATABASE
}
