package io.github.kxng0109.cacherelay.cache.engine;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("CacheGuardrails")
class CacheGuardrailsTest {

	private final CacheGuardrails guardrails = new CacheGuardrails();

	@Test
	@DisplayName("checkPolarityMatch correctly detects matching and conflicting polarity keywords")
	void polarityMatchTests() {
		// Matching intent
		assertThat(guardrails.checkPolarityMatch("How to enable 2FA", "Please enable two factor auth")).isTrue();
		assertThat(guardrails.checkPolarityMatch("How to reset my password", "I forgot my password")).isTrue();

		// Opposing polarity pairs
		assertThat(guardrails.checkPolarityMatch("How to enable 2FA", "How to disable 2FA")).isFalse();
		assertThat(guardrails.checkPolarityMatch("disable 2FA", "enable 2FA")).isFalse();
		assertThat(guardrails.checkPolarityMatch("Turn on dark mode", "Turn off dark mode")).isFalse();
		assertThat(guardrails.checkPolarityMatch("Start the container", "Stop the container")).isFalse();
		assertThat(guardrails.checkPolarityMatch("Create an account", "Delete an account")).isFalse();
		assertThat(guardrails.checkPolarityMatch("Add item to cart", "Remove item from cart")).isFalse();

		// WordNet-aligned antonyms
		assertThat(guardrails.checkPolarityMatch("Is it safe to migrate?", "Is it dangerous to migrate?")).isFalse();
		assertThat(guardrails.checkPolarityMatch("Should I buy Bitcoin?", "Should I sell Bitcoin?")).isFalse();
		assertThat(guardrails.checkPolarityMatch("Allow external traffic", "Deny external traffic")).isFalse();
		assertThat(guardrails.checkPolarityMatch("Is this method legal?", "Is this method illegal?")).isFalse();
		assertThat(guardrails.checkPolarityMatch("Increase timeout", "Decrease timeout")).isFalse();
		assertThat(guardrails.checkPolarityMatch("Set value to true", "Set value to false")).isFalse();
		assertThat(guardrails.checkPolarityMatch("With SSL enabled", "Without SSL enabled")).isFalse();

		// General negation mismatch
		assertThat(guardrails.checkPolarityMatch("Is Python compiled?", "Is Python not compiled?")).isFalse();
		assertThat(guardrails.checkPolarityMatch("I want milk", "I want coffee without milk")).isFalse();

		// Contraction negation mismatch
		assertThat(guardrails.checkPolarityMatch("I can deploy this", "I can't deploy this")).isFalse();
		assertThat(guardrails.checkPolarityMatch("I can deploy this", "I can’t deploy this")).isFalse();
		assertThat(guardrails.checkPolarityMatch("It will work", "It won't work")).isFalse();
		assertThat(guardrails.checkPolarityMatch("It is ready", "It isn't ready")).isFalse();
		assertThat(guardrails.checkPolarityMatch("Why did it fail?", "Why didn't it fail?")).isFalse();
	}

	@Test
	@DisplayName("normalizeContractions expands English contractions reliably")
	void normalizeContractionsTests() {
		assertThat(CacheGuardrails.normalizeContractions("can't")).isEqualTo("can not");
		assertThat(CacheGuardrails.normalizeContractions("can’t")).isEqualTo("can not");
		assertThat(CacheGuardrails.normalizeContractions("won't")).isEqualTo("will not");
		assertThat(CacheGuardrails.normalizeContractions("isn't")).isEqualTo("is not");
		assertThat(CacheGuardrails.normalizeContractions("didn't")).isEqualTo("did not");
		assertThat(CacheGuardrails.normalizeContractions("haven't")).isEqualTo("have not");
		assertThat(CacheGuardrails.normalizeContractions("shouldn't")).isEqualTo("should not");
	}

	@Test
	@DisplayName("checkEntityMatch correctly detects matching and conflicting entities and numbers")
	void entityMatchTests() {
		// Same entities
		assertThat(guardrails.checkEntityMatch("Who is the CEO of Apple?", "Tell me the Apple CEO")).isTrue();

		// Different named entities (Proper Nouns)
		assertThat(guardrails.checkEntityMatch("Who is the CEO of Apple?", "Who is the CEO of Microsoft?")).isFalse();
		assertThat(guardrails.checkEntityMatch("Deploy Docker on AWS", "Deploy Docker on Azure")).isFalse();

		// Uncased / lowercase entity swaps
		assertThat(guardrails.checkEntityMatch("deploy on aws", "deploy on azure")).isFalse();
		assertThat(guardrails.checkEntityMatch("deploy docker on aws", "deploy docker on azure")).isFalse();
		assertThat(guardrails.checkEntityMatch("any tips for aws", "any tips for azure")).isFalse();

		// Same numbers
		assertThat(guardrails.checkEntityMatch("Calculate 42 * 2", "What is 42 times 2?")).isTrue();

		// Different numbers
		assertThat(guardrails.checkEntityMatch("Calculate 42 * 2", "Calculate 100 * 2")).isFalse();
		assertThat(guardrails.checkEntityMatch("Pay 42.5 dollars", "Pay 42.6 dollars")).isFalse();
		assertThat(guardrails.checkEntityMatch("Order 5 items", "Order 10 items")).isFalse();

		// Asymmetric empty entity and number sets
		assertThat(guardrails.checkEntityMatch("No numbers here", "Here is number 42")).isFalse();
		assertThat(guardrails.checkEntityMatch("Here is number 42", "No numbers here")).isFalse();
		assertThat(guardrails.checkEntityMatch("all lowercase words", "Mentions France and Paris")).isFalse();
		assertThat(guardrails.checkEntityMatch("Mentions France and Paris", "all lowercase words")).isFalse();
	}

	@Test
	@DisplayName("validateSemanticMatch respects configuration toggles")
	void validateSemanticMatchToggles() {
		// Conflicting polarity: fails when polarity guard enabled
		assertThat(guardrails.validateSemanticMatch("enable 2FA", "disable 2FA", true, true)).isFalse();
		// Passes if polarity guard is disabled
		assertThat(guardrails.validateSemanticMatch("enable 2FA", "disable 2FA", false, false)).isTrue();

		// Conflicting entity: fails when entity guard enabled
		assertThat(guardrails.validateSemanticMatch("CEO of Apple", "CEO of Microsoft", true, true)).isFalse();
		// Passes if entity guard is disabled
		assertThat(guardrails.validateSemanticMatch("CEO of Apple", "CEO of Microsoft", false, false)).isTrue();
	}
}
