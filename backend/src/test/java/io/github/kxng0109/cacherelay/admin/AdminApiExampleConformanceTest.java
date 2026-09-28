package io.github.kxng0109.cacherelay.admin;

import io.github.kxng0109.cacherelay.admin.dto.CachePurgeResponse;
import io.github.kxng0109.cacherelay.admin.dto.CacheStatsResponse;
import io.github.kxng0109.cacherelay.admin.dto.CreatedKeyResponse;
import io.github.kxng0109.cacherelay.admin.dto.LedgerSummaryResponse;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.ExampleObject;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.lang.reflect.Method;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Guards documented admin response shapes (ADM-B12): every {@code @ExampleObject}
 * attached to a DTO schema must carry exactly the DTO's serialized wire names.
 * Drift here means operators code against fiction. Wire names come from
 * serializing real instances — never from assumed annotation semantics.
 */
@DisplayName("Admin API example conformance")
class AdminApiExampleConformanceTest {

	private final ObjectMapper objectMapper = new ObjectMapper();

	@Test
	@DisplayName("ADM-B12: documented examples match served DTO wire shapes")
	void examplesMatchWireShapes() {
		List<String> mismatches = new ArrayList<>();
		checkController(AdminKeyController.class, mismatches);
		checkController(AdminCacheController.class, mismatches);
		checkController(AdminLedgerController.class, mismatches);
		assertThat(mismatches).isEmpty();
	}

	private void checkController(Class<?> controller, List<String> mismatches) {
		for (Method method : controller.getMethods()) {
			ApiResponses responses = method.getAnnotation(ApiResponses.class);
			if (responses == null) {
				continue;
			}
			for (ApiResponse response : responses.value()) {
				for (Content content : response.content()) {
					Class<?> dto = content.schema().implementation();
					if (dto == null || dto == void.class) {
						continue;
					}
					for (ExampleObject example : content.examples()) {
						checkExample(controller, method, dto, example, mismatches);
					}
				}
			}
		}
	}

	private void checkExample(
			Class<?> controller, Method method, Class<?> dto, ExampleObject example,
			List<String> mismatches
	) {
		String location = controller.getSimpleName() + "#" + method.getName()
				+ " example '" + example.name() + "'";
		if (example.value() == null || example.value().isBlank()) {
			mismatches.add(location + ": blank example");
			return;
		}
		JsonNode tree;
		try {
			tree = objectMapper.readTree(example.value());
		} catch (Exception unparseable) {
			mismatches.add(location + ": example is not valid JSON");
			return;
		}
		if (!tree.isObject()) {
			return;
		}
		Set<String> documented = new LinkedHashSet<>();
		tree.properties().forEach(entry -> documented.add(entry.getKey()));
		Set<String> served;
		try {
			served = wireNames(dto);
		} catch (Exception noWitness) {
			mismatches.add(location + ": no wire witness for " + dto.getSimpleName());
			return;
		}
		if (!documented.equals(served)) {
			mismatches.add(location + ": documented keys " + documented
					+ " != served wire names " + served);
		}
	}

	/**
	 * Serializes a witness instance to read the actual wire names.
	 *
	 * @param dto DTO record class
	 * @return wire names in serialization order
	 */
	private Set<String> wireNames(Class<?> dto) throws Exception {
		Object witness = witness(dto);
		JsonNode tree = objectMapper.valueToTree(witness);
		Set<String> names = new LinkedHashSet<>();
		tree.properties().forEach(entry -> names.add(entry.getKey()));
		return names;
	}

	/**
	 * Builds a witness instance for each documented DTO.
	 *
	 * @param dto DTO record class
	 * @return populated instance
	 */
	private static Object witness(Class<?> dto) {
		if (dto == CachePurgeResponse.class) {
			return new CachePurgeResponse(true, "Purged", "ALL", 42L);
		}
		if (dto == CacheStatsResponse.class) {
			return new CacheStatsResponse(true, "TENANT", 0.8, "text-embedding-3-small",
					268435456L, 60L, true, true, true, true);
		}
		if (dto == CreatedKeyResponse.class) {
			return new CreatedKeyResponse(
					"keyId", "key", "gw-", "owner", "name", 60, 1000,
					Set.of(), Set.of(), Set.of(), Set.of(), Set.of(), Set.of(),
					Set.of(), Set.of(), true, true, Instant.EPOCH, Set.of(),
					Set.of(), Set.of(), null, null);
		}
		if (dto == LedgerSummaryResponse.class) {
			return new LedgerSummaryResponse(1L, 2L, 3L, 5L, 6L, BigDecimal.valueOf(6, 6),
					1.5, List.of(), List.of(), List.of());
		}
		throw new IllegalArgumentException("no witness for " + dto);
	}
}
