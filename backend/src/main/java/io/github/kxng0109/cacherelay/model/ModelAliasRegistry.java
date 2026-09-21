package io.github.kxng0109.cacherelay.model;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;

import io.github.kxng0109.cacherelay.contracts.FailoverStrategy;
import io.github.kxng0109.cacherelay.contracts.GatewayProperties;
import io.github.kxng0109.cacherelay.contracts.ModelAlias;
import io.github.kxng0109.cacherelay.contracts.ProviderRef;
import lombok.extern.slf4j.Slf4j;
import tools.jackson.core.JacksonException;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

/**
 * Runtime registry for model aliases behind client facing model names.
 *
 * <p>File-bound aliases from {@code gateway.aliases} configuration are the
 * immutable floor: they are snapshotted before any database overlay, always
 * win on name conflict, and stay read-only through the admin API. Database
 * rows from the {@code model_alias} table supply the rest and are managed
 * through create, update, and delete operations that persist first and then
 * republish the merged map into {@link GatewayProperties} atomically, so
 * proxy, embedding, and model-listing readers never observe a partial
 * catalog.</p>
 *
 * <p>Startup ordering is explicit: this loader runs after
 * {@code DatabaseMigrator} (see its {@code HIGHEST_PRECEDENCE} order) so the
 * {@code model_alias} table exists before the first read. When the database
 * is unreachable the loader degrades to file-bound aliases and retries on a
 * schedule; reads never fail because the catalog is unavailable, while
 * mutations answer 503 until the first successful load.</p>
 *
 * <h2>Thread safety</h2>
 * <p>All state transitions hold the registry monitor. Concurrent readers of
 * {@link GatewayProperties#getAliases()} observe only fully rebuilt,
 * immutable maps.</p>
 */
@Slf4j
@Service
public class ModelAliasRegistry {

	/** Model names are lowercase slugs of at most 64 characters. */
	public static final String NAME_PATTERN = "^[a-z0-9][a-z0-9._-]{0,63}$";

	/** Maximum provider steps in one alias chain. */
	public static final int MAX_CHAIN_STEPS = 8;

	/** Maximum length of a per-step model override. */
	public static final int MAX_MODEL_OVERRIDE_LENGTH = 256;

	private final ModelAliasRepository repository;
	private final GatewayProperties gatewayProperties;
	private final ObjectMapper objectMapper;
	private final AtomicBoolean loaded = new AtomicBoolean();
	private volatile Map<String, ModelAlias> fileAliases;
	private volatile Set<String> databaseNames = Set.of();

	/**
	 * Creates the registry.
	 *
	 * @param repository         alias persistence
	 * @param gatewayProperties  live alias map republished on every change
	 * @param objectMapper       codec for the stored chain JSON
	 */
	public ModelAliasRegistry(ModelAliasRepository repository, GatewayProperties gatewayProperties,
			ObjectMapper objectMapper) {
		this.repository = repository;
		this.gatewayProperties = gatewayProperties;
		this.objectMapper = objectMapper;
	}

	/**
	 * Loads database aliases once the application is ready, after schema
	 * migration has been attempted.
	 */
	@Order(Ordered.HIGHEST_PRECEDENCE + 1)
	@EventListener(ApplicationReadyEvent.class)
	public void loadOnReady() {
		tryLoad();
	}

	/**
	 * Retries the initial load until it succeeds.
	 */
	@Scheduled(fixedDelayString = "${gateway.model-alias.init-retry-interval:30s}")
	public void retryLoad() {
		if (!loaded.get()) {
			tryLoad();
		}
	}

	/**
	 * Lists every effective alias with its origin.
	 *
	 * @return effective aliases in file order first, then database order
	 */
	public synchronized List<ModelDefinition> list() {
		Map<String, ModelAlias> current = gatewayProperties.getAliases();
		Set<String> dbNames = databaseNames;
		List<ModelDefinition> definitions = new ArrayList<>(current.size());
		for (Map.Entry<String, ModelAlias> entry : current.entrySet()) {
			ModelAlias alias = entry.getValue();
			AliasSource source = dbNames.contains(entry.getKey()) ? AliasSource.DATABASE : AliasSource.FILE;
			definitions.add(new ModelDefinition(entry.getKey(), alias.chain(), alias.strategy(), source));
		}
		return List.copyOf(definitions);
	}

	/**
	 * Creates a database-managed alias and republishes the catalog.
	 *
	 * @param name     client facing model name
	 * @param chain    ordered provider steps
	 * @param strategy failover strategy name (case-insensitive)
	 * @return the created routing plan
	 * @throws ResponseStatusException 400 on invalid input, 404 never, 409 on
	 *                                  duplicate or file-shadowed name, 503
	 *                                  while the database overlay is down
	 */
	@Transactional
	public synchronized ModelAlias create(String name, List<ProviderRef> chain, String strategy) {
		ensureLoaded();
		String cleanName = requireValidName(name);
		List<ProviderRef> steps = requireValidChain(chain);
		FailoverStrategy mode = requireValidStrategy(strategy);
		requireWritable(cleanName);
		if (repository.existsById(cleanName)) {
			throw new ResponseStatusException(HttpStatus.CONFLICT,
					"Model already exists: " + cleanName);
		}
		try {
			repository.save(new ModelAliasDefinition(cleanName, encode(steps), mode.name()));
		} catch (DataIntegrityViolationException ex) {
			throw new ResponseStatusException(HttpStatus.CONFLICT,
					"Model already exists: " + cleanName, ex);
		}
		rebuild();
		return new ModelAlias(steps, mode);
	}

	/**
	 * Replaces the routing plan of a database-managed alias.
	 *
	 * @param name     client facing model name
	 * @param chain    ordered provider steps
	 * @param strategy failover strategy name (case-insensitive)
	 * @return the replaced routing plan
	 * @throws ResponseStatusException 400 on invalid input, 404 on unknown
	 *                                  name, 409 on file-shadowed name, 503
	 *                                  while the database overlay is down
	 */
	@Transactional
	public synchronized ModelAlias update(String name, List<ProviderRef> chain, String strategy) {
		ensureLoaded();
		String cleanName = requireValidName(name);
		List<ProviderRef> steps = requireValidChain(chain);
		FailoverStrategy mode = requireValidStrategy(strategy);
		requireWritable(cleanName);
		ModelAliasDefinition existing = repository.findById(cleanName).orElseThrow(() ->
				new ResponseStatusException(HttpStatus.NOT_FOUND, "Unknown model: " + cleanName));
		existing.replacePlan(encode(steps), mode.name());
		repository.save(existing);
		rebuild();
		return new ModelAlias(steps, mode);
	}

	/**
	 * Deletes a database-managed alias and republishes the catalog.
	 *
	 * @param name client facing model name
	 * @throws ResponseStatusException 400 on invalid input, 404 on unknown
	 *                                  name, 409 on file-shadowed name, 503
	 *                                  while the database overlay is down
	 */
	@Transactional
	public synchronized void delete(String name) {
		ensureLoaded();
		String cleanName = requireValidName(name);
		requireWritable(cleanName);
		if (!repository.existsById(cleanName)) {
			throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Unknown model: " + cleanName);
		}
		repository.deleteById(cleanName);
		rebuild();
	}

	private void ensureLoaded() {
		if (!loaded.get()) {
			tryLoad();
		}
		if (!loaded.get()) {
			throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,
					"Model catalog database overlay unavailable");
		}
	}

	private synchronized void tryLoad() {
		if (loaded.get()) {
			return;
		}
		try {
			List<ModelAliasDefinition> rows = repository.findAll();
			if (fileAliases == null) {
				fileAliases = Map.copyOf(gatewayProperties.getAliases());
			}
			rebuild(rows);
			loaded.set(true);
		} catch (RuntimeException ex) {
			log.warn("Model alias database overlay unavailable; serving file-bound aliases until retry: {}",
					ex.toString());
		}
	}

	private void rebuild() {
		rebuild(repository.findAll());
	}

	private void rebuild(List<ModelAliasDefinition> rows) {
		Map<String, ModelAlias> merged = new LinkedHashMap<>(fileAliases);
		Set<String> dbNames = new LinkedHashSet<>();
		for (ModelAliasDefinition row : rows) {
			if (merged.containsKey(row.getName())) {
				log.warn("Database model alias '{}' shadowed by file-bound alias; file wins", row.getName());
				continue;
			}
			try {
				merged.put(row.getName(), decode(row));
				dbNames.add(row.getName());
			} catch (RuntimeException ex) {
				log.error("Skipping corrupt database model alias '{}': {}", row.getName(), ex.toString());
			}
		}
		databaseNames = Set.copyOf(dbNames);
		gatewayProperties.setAliases(merged);
	}

	private ModelAlias decode(ModelAliasDefinition row) {
		List<ProviderRef> chain = objectMapper.readValue(row.getChainJson(),
				new TypeReference<List<ProviderRef>>() {
				});
		FailoverStrategy strategy = FailoverStrategy.valueOf(row.getStrategy());
		return new ModelAlias(chain, strategy);
	}

	private String encode(List<ProviderRef> chain) {
		try {
			return objectMapper.writeValueAsString(chain);
		} catch (JacksonException ex) {
			throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR,
					"Could not encode model chain", ex);
		}
	}

	private String requireValidName(String name) {
		if (name == null || name.isBlank() || !name.matches(NAME_PATTERN)) {
			throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
					"Invalid model name (lowercase slug, at most 64 characters): " + name);
		}
		return name;
	}

	private List<ProviderRef> requireValidChain(List<ProviderRef> chain) {
		if (chain == null || chain.isEmpty()) {
			throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
					"Model chain must contain at least one provider step");
		}
		if (chain.size() > MAX_CHAIN_STEPS) {
			throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
					"Model chain must contain at most " + MAX_CHAIN_STEPS + " provider steps");
		}
		Map<String, ?> providers = gatewayProperties.getProviders();
		for (ProviderRef step : chain) {
			if (step == null || step.providerName() == null || step.providerName().isBlank()) {
				throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
						"Model chain step must name a provider");
			}
			if (!providers.containsKey(step.providerName())) {
				throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
						"Unknown provider in model chain: " + step.providerName());
			}
			if (step.modelOverride() != null && step.modelOverride().length() > MAX_MODEL_OVERRIDE_LENGTH) {
				throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
						"Model override too long (max " + MAX_MODEL_OVERRIDE_LENGTH + "): "
								+ step.providerName());
			}
		}
		return List.copyOf(chain);
	}

	private FailoverStrategy requireValidStrategy(String strategy) {
		if (strategy == null || strategy.isBlank()) {
			throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
					"Model strategy must be SEQUENTIAL or RACE");
		}
		try {
			return FailoverStrategy.valueOf(strategy.trim().toUpperCase(Locale.ROOT));
		} catch (IllegalArgumentException ex) {
			throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
					"Unknown model strategy: " + strategy, ex);
		}
	}

	private void requireWritable(String name) {
		if (fileAliases.containsKey(name)) {
			throw new ResponseStatusException(HttpStatus.CONFLICT,
					"Model is file-bound and read-only: " + name);
		}
	}
}
