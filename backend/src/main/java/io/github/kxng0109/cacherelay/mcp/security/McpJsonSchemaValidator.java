package io.github.kxng0109.cacherelay.mcp.security;

import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;

import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

/**
 * Bounded-subset JSON Schema parameter validator and dangerous pattern pre-filter for MCP tool arguments.
 *
 * <p>Enforces a deliberately narrow, documented subset of Draft 2020-12: {@code required}, {@code type}
 * ({@code string}, {@code integer}, {@code number}, {@code boolean}, {@code array}, {@code object}),
 * string {@code minLength}/{@code maxLength}/{@code pattern}, numeric {@code minimum}/{@code maximum},
 * array {@code minItems}/{@code maxItems}, {@code additionalProperties: false}, and the IEEE 754 safe-integer
 * range. Anything outside this subset (nested item schemas, enum, anyOf, format,
 * const) is not evaluated: the validator never claims full-draft conformance (MCP-B14).</p>
 */
@Component
public class McpJsonSchemaValidator {

	// IEEE 754 safe integer limits
	public static final long MAX_SAFE_INTEGER = 9007199254740991L;
	public static final long MIN_SAFE_INTEGER = -9007199254740991L;

	private static final Pattern PATH_TRAVERSAL_PATTERN = Pattern.compile("(?:\\.\\./|\\.\\.\\\\)");

	/**
	 * Upper bound for an upstream-supplied {@code pattern} string. Longer patterns are rejected as
	 * invalid: no legitimate tool schema needs a kilobyte of regex, and the cap bounds both compile
	 * cost and match-time pathology (MCP-B13).
	 */
	static final int MAX_PATTERN_CHARS = 1024;

	/** Cap for the compiled-pattern cache: one entry per distinct upstream pattern, LRU-evicted. */
	private static final int PATTERN_CACHE_CAP = 512;

	/**
	 * Compiled-pattern cache (MCP-B13): upstream {@code pattern} strings are compiled once, validated
	 * once, and reused. Absent means invalid or unsafe and fails closed on every use. Synchronized LRU
	 * so a hostile catalog with thousands of distinct patterns cannot grow the map without bound.
	 */
	private final Map<String, Optional<Pattern>> patternCache = Collections.synchronizedMap(
			new LinkedHashMap<>(64, 0.75f, true) {
				@Override
				protected boolean removeEldestEntry(Map.Entry<String, Optional<Pattern>> eldest) {
					return size() > PATTERN_CACHE_CAP;
				}
			});

	/**
	 * Validates client-supplied tool arguments against the declared JSON Schema inputSchema.
	 *
	 * @param arguments incoming tool argument object
	 * @param schema    tool inputSchema from definition
	 * @return validation result
	 */
	public ValidationResult validate(@Nullable JsonNode arguments, @Nullable JsonNode schema) {
		if (schema == null || schema.isNull() || !schema.isObject()) {
			return ValidationResult.success();
		}

		// Arguments must be an object if schema expects an object. A missing
		// arguments node is treated as {} (MCP-B14): zero-arg calls are legal,
		// so only a schema with required properties rejects the call.
		if (arguments == null || arguments.isNull() || arguments.isMissingNode()) {
			if (schema.has("required") && !schema.path("required").isEmpty()) {
				return ValidationResult.error("Missing required arguments object");
			}
			return ValidationResult.success();
		}

		if (!arguments.isObject()) {
			return ValidationResult.error("Tool arguments must be a JSON object");
		}

		// 1. Required property enforcement
		if (schema.has("required") && schema.path("required").isArray()) {
			for (JsonNode reqField : schema.path("required")) {
				String fieldName = reqField.asString();
				if (!arguments.has(fieldName) || arguments.path(fieldName).isNull()) {
					return ValidationResult.error("Missing required parameter: '" + fieldName + "'");
				}
			}
		}

		JsonNode properties = schema.path("properties");
		boolean strictAdditionalProps = schema.has("additionalProperties")
				&& schema.path("additionalProperties").isBoolean()
				&& !schema.path("additionalProperties").asBoolean();

		Set<String> declaredProperties = new HashSet<>();
		if (properties.isObject()) {
			properties.properties().forEach(e -> declaredProperties.add(e.getKey()));
		}

		// 2. Validate declared properties and check for undeclared properties if additionalProperties: false
		for (var entry : arguments.properties()) {
			String propName = entry.getKey();
			JsonNode propVal = entry.getValue();

			if (strictAdditionalProps && !declaredProperties.contains(propName)) {
				return ValidationResult.error("Undeclared parameter not allowed: '" + propName + "'");
			}

			if (properties.has(propName)) {
				JsonNode propSchema = properties.path(propName);
				ValidationResult propRes = validateProperty(propName, propVal, propSchema);
				if (!propRes.isValid()) {
					return propRes;
				}
			}
		}

		return ValidationResult.success();
	}

	private ValidationResult validateProperty(String propName, JsonNode val, JsonNode schema) {
		if (val.isNull()) {
			return ValidationResult.success();
		}

		String expectedType = schema.path("type").asString("");

		// Type validation
		if (!expectedType.isBlank()) {
			switch (expectedType) {
				case "string" -> {
					if (!val.isString()) {
						return ValidationResult.error("Parameter '" + propName + "' must be a string");
					}
					String text = val.asString();
					if (schema.has("minLength") && text.length() < schema.path("minLength").asInt()) {
						return ValidationResult.error("Parameter '" + propName + "' length is below minLength");
					}
					if (schema.has("maxLength") && text.length() > schema.path("maxLength").asInt()) {
						return ValidationResult.error("Parameter '" + propName + "' length exceeds maxLength");
					}
					if (schema.has("pattern")) {
						String regex = schema.path("pattern").asString();
						Optional<Pattern> compiled = patternFor(regex);
						if (compiled.isEmpty()) {
							return ValidationResult.error(
									"Parameter '" + propName + "' has an invalid or unsafe pattern and was rejected");
						}
						if (!compiled.get().matcher(text).find()) {
							return ValidationResult.error(
									"Parameter '" + propName + "' does not match required pattern");
						}
					}
					// Pre-filter dangerous patterns on sensitive parameters. The traversal
					// check runs on the percent-decoded value (MCP-B29): "%2e%2e/" must
					// not slip past a literal "../" match. Undecodable input keeps its
					// raw form and is still checked literally (fail-closed direction).
					if (isPathParameter(propName)
							&& PATH_TRAVERSAL_PATTERN.matcher(decodeLenient(text)).find()) {
						return ValidationResult.error("Path traversal detected in parameter '" + propName + "'");
					}
				}
				case "integer" -> {
					if (!val.isIntegralNumber()) {
						return ValidationResult.error("Parameter '" + propName + "' must be an integer");
					}
					long num = val.asLong();
					if (num < MIN_SAFE_INTEGER || num > MAX_SAFE_INTEGER) {
						return ValidationResult.error(
								"Parameter '" + propName + "' exceeds IEEE 754 safe integer range");
					}
					if (schema.has("minimum") && num < schema.path("minimum").asLong()) {
						return ValidationResult.error("Parameter '" + propName + "' is below minimum value");
					}
					if (schema.has("maximum") && num > schema.path("maximum").asLong()) {
						return ValidationResult.error("Parameter '" + propName + "' exceeds maximum value");
					}
				}
				case "number" -> {
					if (!val.isNumber()) {
						return ValidationResult.error("Parameter '" + propName + "' must be a number");
					}
					double d = val.asDouble();
					if (schema.has("minimum") && d < schema.path("minimum").asDouble()) {
						return ValidationResult.error("Parameter '" + propName + "' is below minimum value");
					}
					if (schema.has("maximum") && d > schema.path("maximum").asDouble()) {
						return ValidationResult.error("Parameter '" + propName + "' exceeds maximum value");
					}
				}
				case "boolean" -> {
					if (!val.isBoolean()) {
						return ValidationResult.error("Parameter '" + propName + "' must be a boolean");
					}
				}
				case "array" -> {
					if (!val.isArray()) {
						return ValidationResult.error("Parameter '" + propName + "' must be an array");
					}
					if (schema.has("minItems") && val.size() < schema.path("minItems").asInt()) {
						return ValidationResult.error("Parameter '" + propName + "' item count is below minItems");
					}
					if (schema.has("maxItems") && val.size() > schema.path("maxItems").asInt()) {
						return ValidationResult.error("Parameter '" + propName + "' item count exceeds maxItems");
					}
				}
				case "object" -> {
					if (!val.isObject()) {
						return ValidationResult.error("Parameter '" + propName + "' must be an object");
					}
				}
			}
		}

		return ValidationResult.success();
	}

	private boolean isPathParameter(String name) {
		// MCP-B29: ROOT locale — the default locale (e.g. Turkish dotted-I)
		// must never change which parameter names count as path-like.
		String lower = name.toLowerCase(Locale.ROOT);
		return lower.contains("path") || lower.contains("file") || lower.contains("dir") || lower.contains("uri");
	}

	/**
	 * Percent-decodes a value for traversal screening, returning the raw input when it is not
	 * valid percent-encoding (MCP-B29).
	 *
	 * @param text raw parameter value
	 * @return decoded value, or the raw value when undecodable
	 */
	private static String decodeLenient(String text) {
		if (!text.contains("%")) {
			return text;
		}
		try {
			return URLDecoder.decode(text, StandardCharsets.UTF_8);
		} catch (IllegalArgumentException undecodable) {
			return text;
		}
	}

	/**
	 * Returns the compiled form of an upstream-supplied regex, compiling and validating it at most once
	 * (MCP-B13). Over-long patterns, syntactically invalid patterns, and patterns with nested unbounded
	 * repetition (the catastrophic-backtracking shape) yield empty and fail closed at every use site —
	 * never a per-request recompile, never an uncaught {@code PatternSyntaxException} 500.
	 *
	 * @param regex upstream pattern string
	 * @return the compiled pattern, or empty when invalid or unsafe
	 */
	private Optional<Pattern> patternFor(String regex) {
		Optional<Pattern> cached = patternCache.get(regex);
		if (cached != null) {
			return cached;
		}
		Optional<Pattern> compiled = compileSafely(regex);
		patternCache.put(regex, compiled);
		return compiled;
	}

	/**
	 * Compiles one upstream pattern after length, shape, and syntax checks.
	 *
	 * @param regex upstream pattern string
	 * @return the compiled pattern, or empty when invalid or unsafe
	 */
	private static Optional<Pattern> compileSafely(String regex) {
		if (regex == null || regex.length() > MAX_PATTERN_CHARS || hasNestedRepetition(regex)) {
			return Optional.empty();
		}
		try {
			return Optional.of(Pattern.compile(regex));
		} catch (PatternSyntaxException invalid) {
			return Optional.empty();
		}
	}

	/**
	 * Detects nested unbounded repetition — a group that itself contains {@code +}, {@code *}, or an
	 * open-ended {@code {n,}} quantifier and is in turn quantified. That shape is the classic
	 * catastrophic-backtracking (ReDoS) trigger, e.g. {@code (a+)+}. Escapes and character-class
	 * contents are skipped so literals like {@code [a)+]} never trip the scan.
	 *
	 * @param regex upstream pattern string
	 * @return true when the pattern carries the nested-repetition shape
	 */
	static boolean hasNestedRepetition(String regex) {
		// Per-group "contains an unbounded quantifier" flags; index 0 is the implicit root.
		boolean[] unbounded = new boolean[regex.length() + 1];
		int[] groupDepth = {0};
		boolean inClass = false;
		for (int i = 0; i < regex.length(); i++) {
			char c = regex.charAt(i);
			if (c == '\\') {
				i++;
				continue;
			}
			if (inClass) {
				if (c == ']') {
					inClass = false;
				}
				continue;
			}
			switch (c) {
				case '[' -> inClass = true;
				case '(' -> groupDepth[0]++;
				case ')' -> {
					if (groupDepth[0] > 0 && unbounded[groupDepth[0]] && isUnboundedQuantifier(regex, i + 1)) {
						return true;
					}
					if (groupDepth[0] > 0) {
						// An inner repetition still repeats as part of the parent,
						// so the flag propagates outward (covers "((a+))+").
						unbounded[groupDepth[0] - 1] = unbounded[groupDepth[0] - 1] || unbounded[groupDepth[0]];
						unbounded[groupDepth[0]] = false;
						groupDepth[0]--;
					}
				}
				case '+', '*' -> unbounded[groupDepth[0]] = true;
				case '{' -> {
					if (isOpenEndedBrace(regex, i)) {
						unbounded[groupDepth[0]] = true;
					}
				}
				default -> {
				}
			}
		}
		return false;
	}

	/**
	 * Checks whether the pattern position starts an unbounded quantifier ({@code +}, {@code *}, or an
	 * open-ended {@code {n,}} brace).
	 *
	 * @param regex pattern string
	 * @param pos   position to inspect (may be past the end)
	 * @return true when an unbounded quantifier starts at the position
	 */
	private static boolean isUnboundedQuantifier(String regex, int pos) {
		if (pos >= regex.length()) {
			return false;
		}
		char c = regex.charAt(pos);
		return c == '+' || c == '*' || (c == '{' && isOpenEndedBrace(regex, pos));
	}

	/**
	 * Checks whether a brace at the position opens an open-ended {@code {n,}} repetition.
	 *
	 * @param regex pattern string
	 * @param pos   position of the opening brace
	 * @return true when the brace starts an unbounded repetition
	 */
	private static boolean isOpenEndedBrace(String regex, int pos) {
		int i = pos + 1;
		while (i < regex.length() && Character.isDigit(regex.charAt(i))) {
			i++;
		}
		return i < regex.length() && regex.charAt(i) == ',';
	}

	public record ValidationResult(boolean isValid, @Nullable String errorMessage) {
		public static ValidationResult success() {
			return new ValidationResult(true, null);
		}

		public static ValidationResult error(String message) {
			return new ValidationResult(false, message);
		}
	}
}
