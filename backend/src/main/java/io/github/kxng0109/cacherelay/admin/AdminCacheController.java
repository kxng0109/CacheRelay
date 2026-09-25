package io.github.kxng0109.cacherelay.admin;

import io.github.kxng0109.cacherelay.admin.dto.CachePurgeResponse;
import io.github.kxng0109.cacherelay.admin.dto.CacheStatsResponse;
import io.github.kxng0109.cacherelay.admin.dto.CacheTierStatsResponse;
import io.github.kxng0109.cacherelay.cache.config.CacheRelayCacheProperties;
import io.github.kxng0109.cacherelay.cache.engine.CacheRelayCacheService;
import io.github.kxng0109.cacherelay.cache.engine.l2.RediSearchVectorClient;
import io.github.kxng0109.cacherelay.cache.engine.l2.RedisSemanticVectorCache;
import io.github.kxng0109.cacherelay.config.OpenApiConfig;
import io.github.kxng0109.cacherelay.replay.ReplayService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.ExampleObject;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.Nullable;
import org.springframework.data.redis.core.Cursor;
import org.springframework.data.redis.core.ScanOptions;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.ArrayList;
import java.util.List;

/**
 * REST controller for administrative management and inspection of the multi-tier semantic cache under
 * {@code /v1/admin/cache}.
 */
@Slf4j
@RestController
@RequestMapping("/v1/admin/cache")
@RequiredArgsConstructor
@Tag(name = "Admin - Cache Control", description = "Inspecting multi-tier cache telemetry and executing tenant-level or global cache purges")
public class AdminCacheController {

	private final CacheRelayCacheService cacheService;
	private final CacheRelayCacheProperties properties;
	private final StringRedisTemplate stringRedisTemplate;
	private final RediSearchVectorClient vectorClient;
	private final RedisTierProbe tierProbe;

	/**
	 * Returns active cache configuration and status metrics.
	 *
	 * @return HTTP 200 OK with cache statistics
	 */
	@Operation(
			summary = "Inspect multi-tier cache configuration and status",
			description = "Retrieves active cache layer statuses (L0, L1, L2), similarity threshold settings, configured embedding model alias, and guardrail flags.",
			security = {
					@SecurityRequirement(name = OpenApiConfig.SCHEME_ADMIN_KEY_HEADER),
					@SecurityRequirement(name = OpenApiConfig.SCHEME_ADMIN_BEARER)
			}
	)
	@ApiResponses(value = {
			@ApiResponse(
					responseCode = "200",
					description = "Cache telemetry and configuration retrieved",
					content = @Content(
							mediaType = "application/json",
							schema = @Schema(implementation = CacheStatsResponse.class),
							examples = @ExampleObject(
									name = "Cache Telemetry Response",
									value = """
											{
											  "enabled": true,
											  "defaultScope": "TENANT",
											  "similarityThreshold": 0.80,
											  "embeddingModel": "text-embedding-3-small",
											  "l0MaxBytes": 268435456,
											  "l0InMemoryTtlSeconds": 60,
											  "l1RedisEnabled": true,
											  "l2SemanticEnabled": true,
											  "polarityGuardEnabled": true,
											  "entityGuardEnabled": true
											}
											"""
							)
					)
			),
			@ApiResponse(responseCode = "401", description = "Unauthorized: Master Admin key missing or incorrect")
	})
	@GetMapping("/stats")
	public ResponseEntity<CacheStatsResponse> getCacheStats() {
		CacheStatsResponse response = new CacheStatsResponse(
				properties.isEnabled(),
				properties.getDefaultScope().name(),
				properties.getSemantic().getSimilarityThreshold(),
				properties.getSemantic().getEmbeddingModel(),
				properties.getExact().getL0MaxBytes(),
				properties.getExact().getL0InMemoryTtl().toSeconds(),
				properties.getExact().isL1RedisEnabled(),
				properties.getSemantic().isEnabled(),
				properties.getSemantic().isPolarityGuardEnabled(),
				properties.getSemantic().isEntityGuardEnabled()
		);
		return ResponseEntity.ok(response);
	}

	/**
	 * Returns live point-in-time telemetry for both Redis tiers.
	 *
	 * @return HTTP 200 OK with the tier snapshot (unreachable tiers read as absent metrics, never an error)
	 */
	@Operation(
			summary = "Inspect live Redis tier telemetry",
			description = "Reads INFO memory plus INFO stats from the accounting (noeviction) and cache (allkeys-lru) tiers behind a ten-second memo. A dead tier degrades to absent metrics.",
			security = {
					@SecurityRequirement(name = OpenApiConfig.SCHEME_ADMIN_KEY_HEADER),
					@SecurityRequirement(name = OpenApiConfig.SCHEME_ADMIN_BEARER)
			}
	)
	@ApiResponses(value = {
			@ApiResponse(
					responseCode = "200",
					description = "Tier telemetry snapshot",
					content = @Content(
							mediaType = "application/json",
							schema = @Schema(implementation = CacheTierStatsResponse.class)
					)
			),
			@ApiResponse(responseCode = "401", description = "Unauthorized: Master Admin key missing or incorrect")
	})
	@GetMapping("/tiers")
	public ResponseEntity<CacheTierStatsResponse> getTierStats() {
		return ResponseEntity.ok(tierProbe.tiers());
	}

	/**
	 * Purges cached entries globally or for a specific tenant.
	 *
	 * @param ownerId optional tenant identifier to restrict the purge
	 * @return HTTP 200 OK with purge confirmation
	 */
	@Operation(
			summary = "Purge cache entries globally or by tenant",
			description = "Evicts local Caffeine memory cache entries, deletes Redis exact match keys, and resets/clears RediSearch vector documents. If `ownerId` is provided, only that tenant's keys are evicted.",
			security = {
					@SecurityRequirement(name = OpenApiConfig.SCHEME_ADMIN_KEY_HEADER),
					@SecurityRequirement(name = OpenApiConfig.SCHEME_ADMIN_BEARER)
			}
	)
	@ApiResponses(value = {
			@ApiResponse(
					responseCode = "200",
					description = "Cache purge operation completed successfully",
					content = @Content(
							mediaType = "application/json",
							schema = @Schema(implementation = CachePurgeResponse.class),
							examples = @ExampleObject(
									name = "Purge Response",
									value = """
											{
											  "success": true,
											  "message": "Purged cache for tenant tenant-corp",
											  "evictedScope": "tenant-corp"
											}
											"""
							)
					)
			),
			@ApiResponse(responseCode = "401", description = "Unauthorized: Master Admin key missing or incorrect")
	})
	@DeleteMapping
	public ResponseEntity<CachePurgeResponse> purgeCache(
			@Parameter(description = "Optional tenant owner ID to restrict purge. If omitted, triggers global purge.", example = "tenant-corp")
			@RequestParam(name = "ownerId", required = false) @Nullable String ownerId
	) {
		cacheService.purgeLocalCache();

		if (ownerId != null && !ownerId.isBlank()) {
			purgeKeysByPattern("cacherelay:cache:exact:" + ownerId + ":*");
			purgeKeysByPattern("cacherelay:cache:doc:" + ownerId + ":*");
			log.info("Administrative cache purge executed for tenant '{}'", ownerId);
			return ResponseEntity.ok(new CachePurgeResponse(true, "Purged cache for tenant " + ownerId, ownerId));
		}

		purgeKeysByPattern("cacherelay:cache:exact:*");
		purgeKeysByPattern("cacherelay:cache:doc:*");
		purgeKeysByPattern(ReplayService.PREFIX + "*");
		purgeKeysByPattern(ReplayService.FILL_PREFIX + "*");
		try {
			// PERF-14: rebuild at the live dimension read back from FT.INFO — the old
			// hardcoded 1536 broke L2 whenever the model used another width. When the
			// dimension is unreadable the rebuild is skipped (fail-safe: a wrong-dim
			// index is worse than a stale graph over deleted keys).
			int dimensions = vectorClient.vectorDimensionOf(RedisSemanticVectorCache.INDEX_NAME);
			if (dimensions > 0) {
				vectorClient.dropIndex(RedisSemanticVectorCache.INDEX_NAME, false);
				vectorClient.createIndexIfNotExists(
						RedisSemanticVectorCache.INDEX_NAME,
						RedisSemanticVectorCache.PREFIX,
						dimensions
				);
			} else {
				log.warn("Purge skipped index rebuild: live dimension unreadable");
			}
		} catch (Exception ex) {
			log.debug("Index re-creation notice: {}", ex.getMessage());
		}

		log.info("Global administrative cache purge executed successfully");
		return ResponseEntity.ok(new CachePurgeResponse(true, "Global cache purge completed successfully", "ALL"));
	}

	/**
	 * Deletes keys matching a pattern with cursor SCAN (PERF-14): never blocking
	 * KEYS, batched deletes, best-effort per batch.
	 *
	 * @param pattern Redis match pattern
	 * @return count of deleted keys
	 */
	private long purgeKeysByPattern(String pattern) {
		long deleted = 0;
		try (Cursor<String> cursor = stringRedisTemplate.scan(
				ScanOptions.scanOptions().match(pattern).count(500).build())) {
			List<String> batch = new ArrayList<>(500);
			while (cursor.hasNext()) {
				batch.add(cursor.next());
				if (batch.size() >= 500) {
					deleted += deleteKeyBatch(batch, pattern);
					batch.clear();
				}
			}
			if (!batch.isEmpty()) {
				deleted += deleteKeyBatch(batch, pattern);
			}
		} catch (Exception ex) {
			log.warn("Failed to purge keys with pattern '{}': {}", pattern, ex.getMessage());
		}
		return deleted;
	}

	private long deleteKeyBatch(List<String> batch, String pattern) {
		try {
			Long removed = stringRedisTemplate.delete(batch);
			return removed == null ? 0 : removed;
		} catch (Exception ex) {
			log.warn("Failed to delete purged key batch for pattern '{}': {}", pattern, ex.getMessage());
			return 0;
		}
	}
}

