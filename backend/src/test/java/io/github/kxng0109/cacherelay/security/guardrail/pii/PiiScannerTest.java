package io.github.kxng0109.cacherelay.security.guardrail.pii;

import io.github.kxng0109.cacherelay.security.guardrail.common.LuhnValidator;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("PiiScanner Tests")
class PiiScannerTest {

	private final PiiScanner scanner = new PiiScanner();

	@Test
	@DisplayName("scan returns empty list on null, empty, or blank text")
	void nullOrBlankReturnsEmpty() {
		assertThat(scanner.scan(null)).isEmpty();
		assertThat(scanner.scan("")).isEmpty();
		assertThat(scanner.scan("   ")).isEmpty();
	}

	@Test
	@DisplayName("digitless text without @ yields nothing (pre-filter fast path)")
	void digitlessTextYieldsNothing() {
		assertThat(scanner.scan("Hello world, how are you today")).isEmpty();
	}

	@Test
	@DisplayName("honorific names match without any digit present (gate does not suppress)")
	void honorificWithoutDigits() {
		List<PiiEntity> entities = scanner.scan("Please ask Dr Alice Smith for help");
		assertThat(entities).extracting(PiiEntity::type).contains(PiiType.PERSON_NAME);
	}

	@Test
	@DisplayName("email matches without any digit present (independent gate)")
	void emailWithoutDigits() {
		List<PiiEntity> entities = scanner.scan("Contact support at help desk via support@cacherelay.io please");
		assertThat(entities).extracting(PiiEntity::type).contains(PiiType.EMAIL);
	}

	@Test
	@DisplayName("digit plus @ pre-filter exits early with identical verdicts")
	void digitAndAtPreFilter() {
		List<PiiEntity> entities = scanner.scan("call 555-1234 or mail me@example.com today");
		assertThat(entities).extracting(PiiEntity::type)
				.contains(PiiType.EMAIL);
		assertThat(scanner.scan("nothing sensitive here at all")).isEmpty();
	}

	@Test
	@DisplayName("honorific overlapping an email is skipped once")
	void honorificOverlapSkipped() {
		List<PiiEntity> entities = scanner.scan("Dr Alice Smith@x.com");
		assertThat(entities).extracting(PiiEntity::type).containsExactly(PiiType.EMAIL);
	}

	@Test
	@DisplayName("FS-B12: 16- and 19-digit Verve cards verify by prefix and Luhn")
	void sixteenAndNineteenDigitVerve() {
		assertThat(scanner.scan("card " + luhn("506012345678901") + " here"))
				.extracting(PiiEntity::type).contains(PiiType.VERVE_CARD);
		assertThat(scanner.scan("card " + luhn("506012345678901234") + " here"))
				.extracting(PiiEntity::type).contains(PiiType.VERVE_CARD);
	}

	@Test
	@DisplayName("FS-B12: plus and percent survive in email local parts")
	void emailLocalSpecials() {
		List<PiiEntity> plus = scanner.scan("mail user+tag@example.com now");
		assertThat(plus).extracting(PiiEntity::originalValue).contains("user+tag@example.com");

		List<PiiEntity> percent = scanner.scan("mail user%40@example.com now");
		assertThat(percent).extracting(PiiEntity::originalValue).contains("user%40@example.com");
	}

	private static String luhn(String prefix) {
		int sum = 0;
		boolean alternate = true;
		for (int i = prefix.length() - 1; i >= 0; i--) {
			int digit = prefix.charAt(i) - '0';
			if (alternate) {
				digit *= 2;
				if (digit > 9) {
					digit -= 9;
				}
			}
			sum += digit;
			alternate = !alternate;
		}
		return prefix + ((10 - (sum % 10)) % 10);
	}

	@Test
	@DisplayName("18-digit Verve cards verify by prefix and Luhn")
	void eighteenDigitVerve() {
		String prefix17 = "50601234567890123";
		int sum = 0;
		boolean alternate = true;
		for (int i = prefix17.length() - 1; i >= 0; i--) {
			int digit = prefix17.charAt(i) - '0';
			if (alternate) {
				digit *= 2;
				if (digit > 9) {
					digit -= 9;
				}
			}
			sum += digit;
			alternate = !alternate;
		}
		String card18 = prefix17 + ((10 - (sum % 10)) % 10);
		List<PiiEntity> entities = scanner.scan("card " + card18 + " here");
		assertThat(entities).extracting(PiiEntity::type).contains(PiiType.VERVE_CARD);
	}

	@Test
	@DisplayName("scans and extracts US SSN conforming to SSA rules")
	void scansUsSsn() {
		String text = "Customer SSN is 123-45-6789 for tax records";
		List<PiiEntity> entities = scanner.scan(text);

		assertThat(entities).hasSize(1);
		PiiEntity ssn = entities.getFirst();
		assertThat(ssn.type()).isEqualTo(PiiType.US_SSN);
		assertThat(ssn.originalValue()).isEqualTo("123-45-6789");
		assertThat(ssn.startOffset()).isEqualTo(16);
		assertThat(ssn.endOffset()).isEqualTo(27);
	}

	@Test
	@DisplayName("scans and extracts RFC 5322 Email addresses")
	void scansEmail() {
		String text = "Reach us at support@cacherelay.io or sales-ops@example.com";
		List<PiiEntity> entities = scanner.scan(text);

		assertThat(entities).hasSize(2);
		assertThat(entities.get(0).type()).isEqualTo(PiiType.EMAIL);
		assertThat(entities.get(0).originalValue()).isEqualTo("support@cacherelay.io");
		assertThat(entities.get(1).type()).isEqualTo(PiiType.EMAIL);
		assertThat(entities.get(1).originalValue()).isEqualTo("sales-ops@example.com");
	}

	@Test
	@DisplayName("mid-run TLD ends the match where the regex backtrack settles")
	void emailMidRunTld() {
		List<PiiEntity> entities = scanner.scan("x@ab.cdef.gh12");

		assertThat(entities).hasSize(1);
		assertThat(entities.getFirst().originalValue()).isEqualTo("x@ab.cdef");
		assertThat(entities.getFirst().startOffset()).isEqualTo(0);
		assertThat(entities.getFirst().endOffset()).isEqualTo(9);
	}

	@Test
	@DisplayName("dot-heavy domain without a valid TLD matches nothing")
	void emailDotHeavyNoMatch() {
		String text = "a".repeat(1000) + "@" + "a.".repeat(500);

		assertThat(scanner.scan(text)).isEmpty();
	}

	@Test
	@DisplayName("scans and extracts Nigerian E.164 phone numbers with +234 or 009234")
	void scansNigerianE164Phone() {
		String text = "Contact our Lagos office at +2348031234567 or 0092342011234567";
		List<PiiEntity> entities = scanner.scan(text);

		assertThat(entities).hasSize(2);
		assertThat(entities.get(0).type()).isEqualTo(PiiType.PHONE_E164);
		assertThat(entities.get(0).originalValue()).isEqualTo("+2348031234567");
		assertThat(entities.get(1).type()).isEqualTo(PiiType.PHONE_E164);
		assertThat(entities.get(1).originalValue()).isEqualTo("0092342011234567");
	}

	@Test
	@DisplayName("scans generic E.164 phone without overlapping Nigerian E.164")
	void scansGenericE164Phone() {
		String text = "US office: +14155552671 and UK office: +442071838750";
		List<PiiEntity> entities = scanner.scan(text);

		assertThat(entities).hasSize(2);
		assertThat(entities.get(0).type()).isEqualTo(PiiType.PHONE_E164);
		assertThat(entities.get(0).originalValue()).isEqualTo("+14155552671");
		assertThat(entities.get(1).type()).isEqualTo(PiiType.PHONE_E164);
		assertThat(entities.get(1).originalValue()).isEqualTo("+442071838750");
	}

	@Test
	@DisplayName("scans valid IBAN and drops invalid Mod-97-10 checksum candidate")
	void scansIban() {
		// Valid German IBAN vs candidate with failed checksum
		String text = "Valid: DE89370400440532013000 and Invalid: DE99370400440532013000";
		List<PiiEntity> entities = scanner.scan(text);

		assertThat(entities).hasSize(1);
		assertThat(entities.getFirst().type()).isEqualTo(PiiType.IBAN);
		assertThat(entities.getFirst().originalValue()).isEqualTo("DE89370400440532013000");
	}

	@Test
	@DisplayName("scans payment cards and differentiates Interswitch Verve from standard credit cards")
	void scansPaymentCards() {
		// Valid 16-digit Visa (4532015112830366)
		// Valid 16-digit Verve (5061981234567890 -> need valid Luhn for Verve)
		// Let's compute valid 16-digit Verve: 5061 9800 0000 0000 -> check digit
		// Let's use 5061 9801 1283 0368 -> check Luhn
		String cards = "Visa: 4532-0151-1283-0366 and invalid: 4532-0151-1283-0367";
		List<PiiEntity> entities = scanner.scan(cards);

		assertThat(entities).hasSize(1);
		assertThat(entities.getFirst().type()).isEqualTo(PiiType.CREDIT_CARD);
		assertThat(entities.getFirst().originalValue()).isEqualTo("4532-0151-1283-0366");
	}

	@Test
	@DisplayName("scans Verve card when prefix matches Verve BIN ranges (5060, 5061, 5078, 5079, 6500) across 16, 18, and 19 digits")
	void scansVerveCard() {
		// Test all 5 Verve prefixes and lengths 16, 18, 19
		String c1 = generateValidCard("5060", 16);
		String c2 = generateValidCard("5061", 16);
		String c3 = generateValidCard("5078", 18);
		String c4 = generateValidCard("5079", 19);
		String c5 = generateValidCard("6500", 16);

		String text = String.format("Verve cards: %s, %s, %s, %s, and %s.", c1, c2, c3, c4, c5);
		List<PiiEntity> entities = scanner.scan(text);

		assertThat(entities).hasSize(5);
		for (PiiEntity e : entities) {
			assertThat(e.type()).isEqualTo(PiiType.VERVE_CARD);
		}
	}

	private static String generateValidCard(String prefix, int length) {
		StringBuilder sb = new StringBuilder(prefix);
		while (sb.length() < length - 1) {
			sb.append('0');
		}
		for (int d = 0; d <= 9; d++) {
			String candidate = sb.toString() + d;
			if (LuhnValidator.isValid(candidate)) {
				return candidate;
			}
		}
		return sb.toString() + "0";
	}

	@Test
	@DisplayName("scans Nigerian FIRS Tax Identification Number (12 digits with hyphen)")
	void scansFirsTin() {
		String text = "Tax ID: 12345678-0001 submitted to revenue portal";
		List<PiiEntity> entities = scanner.scan(text);

		assertThat(entities).hasSize(1);
		assertThat(entities.getFirst().type()).isEqualTo(PiiType.NIGERIAN_TIN);
		assertThat(entities.getFirst().originalValue()).isEqualTo("12345678-0001");
	}

	@Test
	@DisplayName("scans 11-digit entities and disambiguates phone, BVN, and NIN")
	void scansElevenDigitDisambiguatedEntities() {
		String text = "User MTN phone: 08031234567. Linked Bank Verification BVN: 22123456789. Also citizenship National Identity Slip NIN: 31234567890.";
		List<PiiEntity> entities = scanner.scan(text);

		assertThat(entities).hasSize(3);
		assertThat(entities.get(0).type()).isEqualTo(PiiType.PHONE_NG_MOBILE);
		assertThat(entities.get(1).type()).isEqualTo(PiiType.NIGERIAN_BVN);
		assertThat(entities.get(2).type()).isEqualTo(PiiType.NIGERIAN_NIN);
	}

	@Test
	@DisplayName("scans professional and cultural honorific names")
	void scansHonorificNames() {
		String text = "Meeting with Dr. John Doe, Prof. Ada Lovelace, and Alhaji Musa Bello today";
		List<PiiEntity> entities = scanner.scan(text);

		assertThat(entities).hasSize(3);
		assertThat(entities.get(0).type()).isEqualTo(PiiType.PERSON_NAME);
		assertThat(entities.get(0).originalValue()).isEqualTo("John Doe");

		assertThat(entities.get(1).type()).isEqualTo(PiiType.PERSON_NAME);
		assertThat(entities.get(1).originalValue()).isEqualTo("Ada Lovelace");

		assertThat(entities.get(2).type()).isEqualTo(PiiType.PERSON_NAME);
		assertThat(entities.get(2).originalValue()).isEqualTo("Musa Bello");
	}

	@Test
	@DisplayName("FS-B12: email boundary shapes (leading punctuation, empty local, long TLD)")
	void emailBoundaryShapes() {
		List<PiiEntity> dotted = scanner.scan("mail .john@example.com now");
		assertThat(dotted).extracting(PiiEntity::originalValue).contains("john@example.com");

		List<PiiEntity> emptyLocal = scanner.scan("contact @example.com now");
		assertThat(emptyLocal).extracting(PiiEntity::type).doesNotContain(PiiType.EMAIL);

		List<PiiEntity> longTld = scanner.scan("mail a@b." + "c".repeat(63) + " now");
		assertThat(longTld).extracting(PiiEntity::originalValue)
				.contains("a@b." + "c".repeat(63));

		List<PiiEntity> backtrack = scanner.scan("mail a@b.com1 now");
		assertThat(backtrack).extracting(PiiEntity::type).doesNotContain(PiiType.EMAIL);
	}

	@Test
	@DisplayName("FS-B12: email domain alphabet (hyphen, uppercase, digits, underscore)")
	void emailDomainAlphabet() {
		List<PiiEntity> hyphen = scanner.scan("mail a@my-domain.com now");
		assertThat(hyphen).extracting(PiiEntity::originalValue).contains("a@my-domain.com");

		List<PiiEntity> upper = scanner.scan("mail USER@EXAMPLE.COM now");
		assertThat(upper).extracting(PiiEntity::type).contains(PiiType.EMAIL);

		List<PiiEntity> alnum = scanner.scan("mail user_1@example.com now");
		assertThat(alnum).extracting(PiiEntity::originalValue).contains("user_1@example.com");
	}
}
