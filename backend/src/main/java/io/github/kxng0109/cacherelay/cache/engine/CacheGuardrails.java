package io.github.kxng0109.cacherelay.cache.engine;

import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Post-retrieval verification guardrails preventing semantic false-positives, entity swaps, and polarity/intent
 * reversals in L2 semantic vector search.
 */
@Component
public class CacheGuardrails {

	private static final Pattern NUMBER_PATTERN = Pattern.compile("\\b\\d+(?:\\.\\d+)?\\b");
	private static final Pattern WORD_PATTERN = Pattern.compile("\\b[a-zA-Z0-9_\\-]{2,}\\b");

	private static final Pattern CANNOT_PATTERN = Pattern.compile("(?i)\\bcannot\\b");
	private static final Pattern CANT_PATTERN = Pattern.compile("(?i)\\bcan['’]t\\b");
	private static final Pattern WONT_PATTERN = Pattern.compile("(?i)\\bwon['’]t\\b");
	private static final Pattern SHANT_PATTERN = Pattern.compile("(?i)\\bshan['’]t\\b");
	private static final Pattern AINT_PATTERN = Pattern.compile("(?i)\\bain['’]t\\b");
	private static final Pattern GENERAL_NT_PATTERN = Pattern.compile("(?i)\\b(\\w+)n['’]t\\b");

	private static final Set<String> COMMON_STOP_WORDS = Set.of(
			"THE", "WHAT", "HOW", "CAN", "TELL", "PLEASE", "WHY", "WHEN", "WHERE", "WHO",
			"WHICH", "COULD", "WOULD", "SHOULD", "THERE", "HERE", "THIS", "THAT", "THESE", "THOSE",
			"ANY", "CALCULATE", "IS", "ARE", "WAS", "WERE", "DO", "DOES", "DID", "EXPLAIN", "DESCRIBE",
			"SHOW", "GIVE"
	);

	private static final Set<String> SLOT_SKIP_WORDS = Set.of("the", "a", "an");

	private static final Set<String> SLOT_MARKERS = Set.of(
			"on", "in", "at", "for", "to", "from", "of", "with", "without", "by", "about",
			"into", "through", "over", "under", "between", "against", "via", "using", "as"
	);

	private static final Set<String> KNOWN_ENTITIES = Set.of(
			"aws", "azure", "gcp", "docker", "kubernetes", "k8s", "linux", "windows", "macos",
			"mac", "ios", "android", "ubuntu", "debian", "redhat", "centos", "alpine", "fedora",
			"redis", "postgres", "postgresql", "mysql", "mariadb", "mongodb", "oracle", "sqlite",
			"kafka", "rabbitmq", "java", "python", "golang", "rust", "c++", "c#", "typescript",
			"javascript", "react", "angular", "vue", "spring", "flask", "django", "node", "nodejs",
			"apple", "microsoft", "google", "amazon", "meta", "openai", "anthropic", "netflix",
			"uber", "salesforce", "ibm", "cisco", "intel", "amd", "nvidia"
	);

	private static final List<PolarityPair> POLARITY_PAIRS = List.of(
			PolarityPair.of("safe", "dangerous"),
			PolarityPair.of("safely", "dangerously"),
			PolarityPair.of("safety", "danger"),
			PolarityPair.of("secure", "insecure"),
			PolarityPair.of("buy", "sell"),
			PolarityPair.of("buying", "selling"),
			PolarityPair.of("bought", "sold"),
			PolarityPair.of("buys", "sells"),
			PolarityPair.of("allow", "deny"),
			PolarityPair.of("allows", "denies"),
			PolarityPair.of("allowed", "denied"),
			PolarityPair.of("allowing", "denying"),
			PolarityPair.of("permit", "deny"),
			PolarityPair.of("permit", "forbid"),
			PolarityPair.of("permitted", "forbidden"),
			PolarityPair.of("legal", "illegal"),
			PolarityPair.of("legally", "illegally"),
			PolarityPair.of("lawful", "unlawful"),
			PolarityPair.of("increase", "decrease"),
			PolarityPair.of("increases", "decreases"),
			PolarityPair.of("increased", "decreased"),
			PolarityPair.of("increasing", "decreasing"),
			PolarityPair.of("enable", "disable"),
			PolarityPair.of("enabled", "disabled"),
			PolarityPair.of("enabling", "disabling"),
			PolarityPair.of("enables", "disables"),
			PolarityPair.of("activate", "deactivate"),
			PolarityPair.of("activated", "deactivated"),
			PolarityPair.of("true", "false"),
			PolarityPair.of("start", "stop"),
			PolarityPair.of("starts", "stops"),
			PolarityPair.of("started", "stopped"),
			PolarityPair.of("starting", "stopping"),
			PolarityPair.of("begin", "end"),
			PolarityPair.of("open", "close"),
			PolarityPair.of("opened", "closed"),
			PolarityPair.of("create", "delete"),
			PolarityPair.of("creates", "deletes"),
			PolarityPair.of("created", "deleted"),
			PolarityPair.of("creating", "deleting"),
			PolarityPair.of("add", "remove"),
			PolarityPair.of("adds", "removes"),
			PolarityPair.of("added", "removed"),
			PolarityPair.of("adding", "removing"),
			PolarityPair.of("insert", "drop"),
			PolarityPair.of("with", "without"),
			PolarityPair.of("turn on", "turn off"),
			PolarityPair.of("turned on", "turned off"),
			PolarityPair.of("turning on", "turning off"),
			PolarityPair.of("accept", "reject"),
			PolarityPair.of("accepted", "rejected"),
			PolarityPair.of("accepts", "rejects"),
			PolarityPair.of("lock", "unlock"),
			PolarityPair.of("locked", "unlocked"),
			PolarityPair.of("public", "private"),
			PolarityPair.of("connect", "disconnect"),
			PolarityPair.of("connected", "disconnected"),
			PolarityPair.of("valid", "invalid"),
			PolarityPair.of("pass", "fail"),
			PolarityPair.of("passed", "failed"),
			PolarityPair.of("success", "failure"),
			PolarityPair.of("good", "bad"),
			PolarityPair.of("high", "low"),
			PolarityPair.of("right", "wrong"),
			PolarityPair.of("up", "down"),
			PolarityPair.of("positive", "negative")
	);

	private static final List<Pattern> NEGATION_PATTERNS = List.of(
			Pattern.compile("\\bnot\\b", Pattern.CASE_INSENSITIVE),
			Pattern.compile("\\bnever\\b", Pattern.CASE_INSENSITIVE),
			Pattern.compile("\\bno\\b", Pattern.CASE_INSENSITIVE),
			Pattern.compile("\\bneither\\b", Pattern.CASE_INSENSITIVE),
			Pattern.compile("\\bnor\\b", Pattern.CASE_INSENSITIVE),
			Pattern.compile("\\bnone\\b", Pattern.CASE_INSENSITIVE),
			Pattern.compile("\\bwithout\\b", Pattern.CASE_INSENSITIVE),
			Pattern.compile("\\bcannot\\b", Pattern.CASE_INSENSITIVE),
			Pattern.compile("\\bhardly\\b", Pattern.CASE_INSENSITIVE),
			Pattern.compile("\\bbarely\\b", Pattern.CASE_INSENSITIVE),
			Pattern.compile("\\bscarcely\\b", Pattern.CASE_INSENSITIVE)
	);

	private record PolarityPair(Pattern positive, Pattern negative) {
		static PolarityPair of(String pos, String neg) {
			return new PolarityPair(
					Pattern.compile("\\b" + Pattern.quote(pos) + "\\b", Pattern.CASE_INSENSITIVE),
					Pattern.compile("\\b" + Pattern.quote(neg) + "\\b", Pattern.CASE_INSENSITIVE)
			);
		}
	}

	/**
	 * Normalizes English contractions (e.g. "can't" to "can not", "won't" to "will not") to eliminate
	 * regex word boundary ambiguities.
	 *
	 * @param text input prompt text
	 * @return normalized text
	 */
	public static String normalizeContractions(String text) {
		if (text == null || text.isEmpty()) {
			return "";
		}
		String result = CANNOT_PATTERN.matcher(text).replaceAll("can not");
		result = CANT_PATTERN.matcher(result).replaceAll("can not");
		result = WONT_PATTERN.matcher(result).replaceAll("will not");
		result = SHANT_PATTERN.matcher(result).replaceAll("shall not");
		result = AINT_PATTERN.matcher(result).replaceAll("is not");
		result = GENERAL_NT_PATTERN.matcher(result).replaceAll("$1 not");
		return result;
	}

	/**
	 * Validates whether a candidate cached prompt is semantically safe to serve for the incoming prompt.
	 *
	 * @param incomingPrompt       user prompt from the active client request
	 * @param cachedPrompt         original prompt stored in the cached entry
	 * @param polarityGuardEnabled whether polarity and negation checking is active
	 * @param entityGuardEnabled   whether entity and number intersection checking is active
	 * @return true if the candidate passes all active guardrails
	 */
	public boolean validateSemanticMatch(
			String incomingPrompt,
			String cachedPrompt,
			boolean polarityGuardEnabled,
			boolean entityGuardEnabled
	) {
		if (polarityGuardEnabled && !checkPolarityMatch(incomingPrompt, cachedPrompt)) {
			return false;
		}
		if (entityGuardEnabled && !checkEntityMatch(incomingPrompt, cachedPrompt)) {
			return false;
		}
		return true;
	}

	/**
	 * Checks that the incoming and cached prompts share consistent polarity, antonym intent, and negation.
	 *
	 * @param incomingPrompt active user prompt
	 * @param cachedPrompt   cached entry prompt
	 * @return true if polarity matches, false if polarity is inverted
	 */
	public boolean checkPolarityMatch(String incomingPrompt, String cachedPrompt) {
		String inNorm = normalizeContractions(incomingPrompt);
		String cachedNorm = normalizeContractions(cachedPrompt);

		// 1. Check opposing polarity pairs (e.g. enable vs disable, safe vs dangerous)
		for (PolarityPair pair : POLARITY_PAIRS) {
			boolean inHasPos = pair.positive().matcher(inNorm).find();
			boolean inHasNeg = pair.negative().matcher(inNorm).find();
			boolean cachedHasPos = pair.positive().matcher(cachedNorm).find();
			boolean cachedHasNeg = pair.negative().matcher(cachedNorm).find();

			if ((inHasPos && cachedHasNeg) || (inHasNeg && cachedHasPos)) {
				return false;
			}
		}

		// 2. Check general negation presence
		boolean inHasNegation = hasNegationTerm(inNorm);
		boolean cachedHasNegation = hasNegationTerm(cachedNorm);

		return inHasNegation == cachedHasNegation;
	}

	/**
	 * Checks that numbers and proper noun entities in both prompts do not conflict.
	 *
	 * <p>Uses slot-aligned contradiction detection: values occupying the same grammatical slot (derived from
	 * the immediately-preceding context word) must overlap; disjoint values in a shared slot indicate a
	 * conflict (e.g. "CEO of Apple" vs "CEO of Microsoft", "deploy on AWS" vs "deploy on Azure").
	 * An atomic parity check verifies that numerical constants match exactly.</p>
	 *
	 * @param incomingPrompt active user prompt
	 * @param cachedPrompt   cached entry prompt
	 * @return true if entities and numbers match or are safely compatible, false on conflicts
	 */
	public boolean checkEntityMatch(String incomingPrompt, String cachedPrompt) {
		String inNorm = normalizeContractions(incomingPrompt);
		String cachedNorm = normalizeContractions(cachedPrompt);

		// 1. Number parity and slot-aligned contradiction check
		List<String> inNumbers = extractAllNumbers(inNorm);
		List<String> cachedNumbers = extractAllNumbers(cachedNorm);
		if (!inNumbers.isEmpty() || !cachedNumbers.isEmpty()) {
			if (inNumbers.isEmpty() || cachedNumbers.isEmpty()) {
				return false;
			}
			if (inNumbers.size() != cachedNumbers.size()) {
				return false;
			}
			Map<String, Integer> inFreq = countFrequencies(inNumbers);
			Map<String, Integer> cachedFreq = countFrequencies(cachedNumbers);
			if (!inFreq.equals(cachedFreq)) {
				return false;
			}
			Map<String, Set<String>> inNumberSlots = extractSlottedNumbers(inNorm);
			Map<String, Set<String>> cachedNumberSlots = extractSlottedNumbers(cachedNorm);
			if (hasSlotContradiction(inNumberSlots, cachedNumberSlots)) {
				return false;
			}
		}

		// 2. Slot-aligned entity contradiction check
		Map<String, Set<String>> inEntitySlots = extractSlottedEntities(inNorm);
		Map<String, Set<String>> cachedEntitySlots = extractSlottedEntities(cachedNorm);
		if (!inEntitySlots.isEmpty() || !cachedEntitySlots.isEmpty()) {
			if (inEntitySlots.isEmpty() || cachedEntitySlots.isEmpty()) {
				return false;
			}
			if (hasSlotContradiction(inEntitySlots, cachedEntitySlots)) {
				return false;
			}
		}

		return true;
	}

	private boolean hasSlotContradiction(Map<String, Set<String>> incoming, Map<String, Set<String>> cached) {
		for (Map.Entry<String, Set<String>> entry : incoming.entrySet()) {
			Set<String> cachedValues = cached.get(entry.getKey());
			if (cachedValues == null) {
				continue;
			}
			boolean overlaps = false;
			for (String value : entry.getValue()) {
				if (cachedValues.contains(value)) {
					overlaps = true;
					break;
				}
			}
			if (!overlaps) {
				return true;
			}
		}
		return false;
	}

	private boolean hasNegationTerm(String text) {
		for (Pattern pattern : NEGATION_PATTERNS) {
			if (pattern.matcher(text).find()) {
				return true;
			}
		}
		return false;
	}

	private List<String> extractAllNumbers(String text) {
		List<String> numbers = new ArrayList<>();
		Matcher matcher = NUMBER_PATTERN.matcher(text);
		while (matcher.find()) {
			numbers.add(matcher.group());
		}
		return numbers;
	}

	private Map<String, Integer> countFrequencies(List<String> items) {
		Map<String, Integer> freq = new HashMap<>();
		for (String item : items) {
			freq.merge(item, 1, Integer::sum);
		}
		return freq;
	}

	private Map<String, Set<String>> extractSlottedNumbers(String text) {
		Map<String, Set<String>> slots = new HashMap<>();
		Matcher matcher = NUMBER_PATTERN.matcher(text);
		while (matcher.find()) {
			String slot = precedingWordSlot(text, matcher.start()) + ":NUM";
			slots.computeIfAbsent(slot, k -> new HashSet<>()).add(matcher.group());
		}
		return slots;
	}

	private Map<String, Set<String>> extractSlottedEntities(String text) {
		Map<String, Set<String>> slots = new HashMap<>();
		Matcher matcher = WORD_PATTERN.matcher(text);
		while (matcher.find()) {
			String word = matcher.group();
			String lowerWord = word.toLowerCase(Locale.ROOT);
			if (COMMON_STOP_WORDS.contains(lowerWord.toUpperCase(Locale.ROOT))) {
				continue;
			}
			boolean isCapitalized = Character.isUpperCase(word.charAt(0));
			boolean isKnownEntity = KNOWN_ENTITIES.contains(lowerWord);

			if (isCapitalized || isKnownEntity) {
				String slot = precedingWordSlot(text, matcher.start());
				String slotKey = slot + ":ENT";
				slots.computeIfAbsent(slotKey, k -> new HashSet<>()).add(lowerWord);
			}
		}
		return slots;
	}

	/**
	 * Derives the grammatical slot key from the word immediately preceding the match start. Articles ({@code the},
	 * {@code a}, {@code an}) are skipped because they precede too many unrelated entities ("the CEO" vs "the Apple"
	 * share "the" but are not a swap); the slot falls back to the nearest preceding content word. Returns the empty
	 * string for matches at the start of the text.
	 */
	private static String precedingWordSlot(String text, int matchStart) {
		int end = matchStart;
		while (true) {
			while (end > 0 && !Character.isLetterOrDigit(text.charAt(end - 1))) {
				end--;
			}
			int start = end;
			while (start > 0 && Character.isLetterOrDigit(text.charAt(start - 1))) {
				start--;
			}
			if (start >= end) {
				return "";
			}
			String word = text.substring(start, end).toLowerCase(Locale.ROOT);
			if (!SLOT_SKIP_WORDS.contains(word)) {
				return word;
			}
			end = start;
		}
	}
}
