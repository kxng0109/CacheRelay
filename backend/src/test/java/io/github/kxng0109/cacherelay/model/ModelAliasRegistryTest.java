package io.github.kxng0109.cacherelay.model;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.net.URI;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import io.github.kxng0109.cacherelay.config.SensitiveString;
import io.github.kxng0109.cacherelay.contracts.FailoverStrategy;
import io.github.kxng0109.cacherelay.contracts.GatewayProperties;
import io.github.kxng0109.cacherelay.contracts.ModelAlias;
import io.github.kxng0109.cacherelay.contracts.ProviderConfig;
import io.github.kxng0109.cacherelay.contracts.ProviderRef;
import io.github.kxng0109.cacherelay.contracts.ProviderType;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

import org.assertj.core.groups.Tuple;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

/**
 * Unit tests for {@link ModelAliasRegistry}: validation matrix, overlay
 * precedence, status-code mapping, outage degradation, and concurrent
 * mutation safety.
 */
@DisplayName("ModelAliasRegistry")
class ModelAliasRegistryTest {

	private static final List<ProviderRef> OPENAI_CHAIN =
			List.of(new ProviderRef("openai", "gpt-5.6-luna"));

	private List<ModelAliasDefinition> rows;

	private GatewayProperties properties;

	private ModelAliasRepository repository;

	private ModelAliasRegistry registry;

	@BeforeEach
	void setUp() {
		rows = new CopyOnWriteArrayList<>();
		properties = new GatewayProperties();
		properties.setProviders(Map.of("openai", new ProviderConfig("openai", ProviderType.OPENAI,
				URI.create("https://api.openai.com"), new SensitiveString("test"),
				Duration.ofSeconds(3), Duration.ofSeconds(30))));
		properties.setAliases(Map.of("file-model", new ModelAlias(
				List.of(new ProviderRef("openai", null)), FailoverStrategy.SEQUENTIAL)));
		repository = fakeRepository(rows);
		registry = new ModelAliasRegistry(repository, properties, new ObjectMapper());
		registry.loadOnReady();
	}

	@Test
	@DisplayName("create publishes a database alias and republishes the catalog")
	void createPublishesDatabaseAlias() {
		ModelAlias created = registry.create("fast-gpt", OPENAI_CHAIN, "sequential");

		assertThat(created.strategy()).isEqualTo(FailoverStrategy.SEQUENTIAL);
		assertThat(created.chain()).isEqualTo(OPENAI_CHAIN);
		assertThat(properties.getAliases()).containsKey("fast-gpt");
		assertThat(registry.list())
				.extracting(ModelDefinition::name, ModelDefinition::source)
				.contains(Tuple.tuple("file-model", AliasSource.FILE),
						Tuple.tuple("fast-gpt", AliasSource.DATABASE));
	}

	@Test
	@DisplayName("create rejects a duplicate name with 409")
	void createRejectsDuplicate() {
		registry.create("fast-gpt", OPENAI_CHAIN, "SEQUENTIAL");

		assertThatThrownBy(() -> registry.create("fast-gpt", OPENAI_CHAIN, "SEQUENTIAL"))
				.isInstanceOf(ResponseStatusException.class)
				.extracting(ex -> ((ResponseStatusException) ex).getStatusCode())
				.isEqualTo(HttpStatus.CONFLICT);
	}

	@Test
	@DisplayName("create rejects a file-shadowed name with 409")
	void createRejectsFileShadowedName() {
		assertThatThrownBy(() -> registry.create("file-model", OPENAI_CHAIN, "SEQUENTIAL"))
				.isInstanceOf(ResponseStatusException.class)
				.extracting(ex -> ((ResponseStatusException) ex).getStatusCode())
				.isEqualTo(HttpStatus.CONFLICT);
	}

	@Test
	@DisplayName("create persists the chain as JSON")
	void createPersistsChainAsJson() {
		registry.create("fast-gpt", OPENAI_CHAIN, "RACE");

		assertThat(rows).hasSize(1);
		assertThat(rows.get(0).getChainJson()).contains("openai").contains("gpt-5.6-luna");
		assertThat(rows.get(0).getStrategy()).isEqualTo("RACE");
	}

	@Test
	@DisplayName("lost persistence race maps to 409")
	void lostPersistenceRaceMapsToConflict() {
		ModelAliasRepository racing = mock(ModelAliasRepository.class);
		when(racing.findAll()).thenReturn(List.of());
		when(racing.existsById("fast-gpt")).thenReturn(false);
		when(racing.save(any(ModelAliasDefinition.class)))
				.thenThrow(new DataIntegrityViolationException("PK clash"));
		ModelAliasRegistry racingRegistry =
				new ModelAliasRegistry(racing, freshProperties(), new ObjectMapper());
		racingRegistry.loadOnReady();

		assertThatThrownBy(() -> racingRegistry.create("fast-gpt", OPENAI_CHAIN, "SEQUENTIAL"))
				.isInstanceOf(ResponseStatusException.class)
				.extracting(ex -> ((ResponseStatusException) ex).getStatusCode())
				.isEqualTo(HttpStatus.CONFLICT);
	}

	@ParameterizedTest
	@ValueSource(strings = {"", " ", "UPPER", "has space", "bang!"})
	@DisplayName("create rejects malformed names with 400")
	void createRejectsMalformedNames(String name) {
		assertThatThrownBy(() -> registry.create(name, OPENAI_CHAIN, "SEQUENTIAL"))
				.isInstanceOf(ResponseStatusException.class)
				.extracting(ex -> ((ResponseStatusException) ex).getStatusCode())
				.isEqualTo(HttpStatus.BAD_REQUEST);
	}

	@Test
	@DisplayName("create rejects an overlong name with 400")
	void createRejectsOverlongName() {
		assertThatThrownBy(() -> registry.create("a".repeat(65), OPENAI_CHAIN, "SEQUENTIAL"))
				.isInstanceOf(ResponseStatusException.class)
				.extracting(ex -> ((ResponseStatusException) ex).getStatusCode())
				.isEqualTo(HttpStatus.BAD_REQUEST);
	}

	@ParameterizedTest
	@ValueSource(strings = {"", " ", "bogus", "sequential!!"})
	@DisplayName("create rejects malformed strategies with 400")
	void createRejectsMalformedStrategies(String strategy) {
		assertThatThrownBy(() -> registry.create("fast-gpt", OPENAI_CHAIN, strategy))
				.isInstanceOf(ResponseStatusException.class)
				.extracting(ex -> ((ResponseStatusException) ex).getStatusCode())
				.isEqualTo(HttpStatus.BAD_REQUEST);
	}

	@Test
	@SuppressWarnings("DataFlowIssue")
	@DisplayName("create rejects null inputs with 400")
	void createRejectsNullInputs() {
		assertThatThrownBy(() -> registry.create(null, OPENAI_CHAIN, "SEQUENTIAL"))
				.isInstanceOf(ResponseStatusException.class)
				.extracting(ex -> ((ResponseStatusException) ex).getStatusCode())
				.isEqualTo(HttpStatus.BAD_REQUEST);
		assertThatThrownBy(() -> registry.create("fast-gpt", null, "SEQUENTIAL"))
				.isInstanceOf(ResponseStatusException.class)
				.extracting(ex -> ((ResponseStatusException) ex).getStatusCode())
				.isEqualTo(HttpStatus.BAD_REQUEST);
		assertThatThrownBy(() -> registry.create("fast-gpt", OPENAI_CHAIN, null))
				.isInstanceOf(ResponseStatusException.class)
				.extracting(ex -> ((ResponseStatusException) ex).getStatusCode())
				.isEqualTo(HttpStatus.BAD_REQUEST);
	}

	@Test
	@DisplayName("create rejects an empty chain with 400")
	void createRejectsEmptyChain() {
		assertThatThrownBy(() -> registry.create("fast-gpt", List.of(), "SEQUENTIAL"))
				.isInstanceOf(ResponseStatusException.class)
				.extracting(ex -> ((ResponseStatusException) ex).getStatusCode())
				.isEqualTo(HttpStatus.BAD_REQUEST);
	}

	@Test
	@DisplayName("create rejects an oversized chain with 400")
	void createRejectsOversizedChain() {
		List<ProviderRef> steps = new ArrayList<>();
		for (int i = 0; i < ModelAliasRegistry.MAX_CHAIN_STEPS + 1; i++) {
			steps.add(new ProviderRef("openai", null));
		}

		assertThatThrownBy(() -> registry.create("fast-gpt", steps, "SEQUENTIAL"))
				.isInstanceOf(ResponseStatusException.class)
				.extracting(ex -> ((ResponseStatusException) ex).getStatusCode())
				.isEqualTo(HttpStatus.BAD_REQUEST);
	}

	@Test
	@SuppressWarnings("DataFlowIssue")
	@DisplayName("create rejects null steps and blank providers with 400")
	void createRejectsBadSteps() {
		List<ProviderRef> nullStep = new ArrayList<>(Arrays.asList((ProviderRef) null));
		assertThatThrownBy(() -> registry.create("fast-gpt", nullStep, "SEQUENTIAL"))
				.isInstanceOf(ResponseStatusException.class)
				.extracting(ex -> ((ResponseStatusException) ex).getStatusCode())
				.isEqualTo(HttpStatus.BAD_REQUEST);
		assertThatThrownBy(() -> registry.create("fast-gpt",
				List.of(new ProviderRef(null, null)), "SEQUENTIAL"))
				.isInstanceOf(ResponseStatusException.class)
				.extracting(ex -> ((ResponseStatusException) ex).getStatusCode())
				.isEqualTo(HttpStatus.BAD_REQUEST);
		assertThatThrownBy(() -> registry.create("fast-gpt",
				List.of(new ProviderRef("  ", null)), "SEQUENTIAL"))
				.isInstanceOf(ResponseStatusException.class)
				.extracting(ex -> ((ResponseStatusException) ex).getStatusCode())
				.isEqualTo(HttpStatus.BAD_REQUEST);
	}

	@Test
	@DisplayName("create rejects an unknown provider with 400")
	void createRejectsUnknownProvider() {
		assertThatThrownBy(() -> registry.create("fast-gpt",
				List.of(new ProviderRef("nope", null)), "SEQUENTIAL"))
				.isInstanceOf(ResponseStatusException.class)
				.extracting(ex -> ((ResponseStatusException) ex).getStatusCode())
				.isEqualTo(HttpStatus.BAD_REQUEST);
	}

	@Test
	@DisplayName("create rejects an oversized model override with 400")
	void createRejectsOversizedOverride() {
		String override = "m".repeat(ModelAliasRegistry.MAX_MODEL_OVERRIDE_LENGTH + 1);

		assertThatThrownBy(() -> registry.create("fast-gpt",
				List.of(new ProviderRef("openai", override)), "SEQUENTIAL"))
				.isInstanceOf(ResponseStatusException.class)
				.extracting(ex -> ((ResponseStatusException) ex).getStatusCode())
				.isEqualTo(HttpStatus.BAD_REQUEST);
	}

	@Test
	@DisplayName("create accepts a null model override and definition defaults null chains")
	void createAcceptsNullOverride() {
		ModelAlias created = registry.create("plain",
				List.of(new ProviderRef("openai", null)), "SEQUENTIAL");

		assertThat(created.chain()).containsExactly(new ProviderRef("openai", null));
		assertThat(new ModelDefinition("n", null, FailoverStrategy.SEQUENTIAL, AliasSource.FILE).chain())
				.isEmpty();
	}

	@Test
	@DisplayName("update replaces the routing plan")
	void updateReplacesPlan() {
		registry.create("fast-gpt", OPENAI_CHAIN, "SEQUENTIAL");
		List<ProviderRef> replacement = List.of(new ProviderRef("openai", "other"));

		ModelAlias updated = registry.update("fast-gpt", replacement, "RACE");

		assertThat(updated.strategy()).isEqualTo(FailoverStrategy.RACE);
		assertThat(updated.chain()).isEqualTo(replacement);
		assertThat(properties.getAliases().get("fast-gpt").strategy())
				.isEqualTo(FailoverStrategy.RACE);
	}

	@Test
	@DisplayName("update of an unknown name answers 404")
	void updateUnknownAnswersNotFound() {
		assertThatThrownBy(() -> registry.update("ghost", OPENAI_CHAIN, "SEQUENTIAL"))
				.isInstanceOf(ResponseStatusException.class)
				.extracting(ex -> ((ResponseStatusException) ex).getStatusCode())
				.isEqualTo(HttpStatus.NOT_FOUND);
	}

	@Test
	@DisplayName("update of a file-bound name answers 409")
	void updateFileBoundAnswersConflict() {
		assertThatThrownBy(() -> registry.update("file-model", OPENAI_CHAIN, "SEQUENTIAL"))
				.isInstanceOf(ResponseStatusException.class)
				.extracting(ex -> ((ResponseStatusException) ex).getStatusCode())
				.isEqualTo(HttpStatus.CONFLICT);
	}

	@Test
	@DisplayName("delete removes the alias from the catalog")
	void deleteRemovesAlias() {
		registry.create("fast-gpt", OPENAI_CHAIN, "SEQUENTIAL");

		registry.delete("fast-gpt");

		assertThat(properties.getAliases()).doesNotContainKey("fast-gpt");
		assertThat(registry.list()).extracting(ModelDefinition::name).doesNotContain("fast-gpt");
	}

	@Test
	@DisplayName("delete of an unknown name answers 404")
	void deleteUnknownAnswersNotFound() {
		assertThatThrownBy(() -> registry.delete("ghost"))
				.isInstanceOf(ResponseStatusException.class)
				.extracting(ex -> ((ResponseStatusException) ex).getStatusCode())
				.isEqualTo(HttpStatus.NOT_FOUND);
	}

	@Test
	@DisplayName("delete of a file-bound name answers 409")
	void deleteFileBoundAnswersConflict() {
		assertThatThrownBy(() -> registry.delete("file-model"))
				.isInstanceOf(ResponseStatusException.class)
				.extracting(ex -> ((ResponseStatusException) ex).getStatusCode())
				.isEqualTo(HttpStatus.CONFLICT);
	}

	@Test
	@DisplayName("file-bound alias wins over a shadowing database row")
	void fileBoundAliasWinsOverlay() {
		rows.add(new ModelAliasDefinition("file-model",
				"[{\"providerName\":\"openai\",\"modelOverride\":\"db-wins\"}]", "RACE"));
		registry = new ModelAliasRegistry(repository, properties, new ObjectMapper());
		registry.loadOnReady();

		ModelAlias effective = properties.getAliases().get("file-model");

		assertThat(effective.strategy()).isEqualTo(FailoverStrategy.SEQUENTIAL);
		assertThat(registry.list())
				.filteredOn(def -> def.name().equals("file-model"))
				.extracting(ModelDefinition::source)
				.containsExactly(AliasSource.FILE);
	}

	@Test
	@DisplayName("corrupt rows are skipped without failing the load")
	void corruptRowsAreSkipped() {
		rows.add(new ModelAliasDefinition("broken-json", "not-json{{{", "SEQUENTIAL"));
		rows.add(new ModelAliasDefinition("broken-strategy",
				"[{\"providerName\":\"openai\",\"modelOverride\":null}]", "BOGUS"));

		registry = new ModelAliasRegistry(repository, properties, new ObjectMapper());
		registry.loadOnReady();

		assertThat(properties.getAliases()).doesNotContainKeys("broken-json", "broken-strategy");
		assertThat(properties.getAliases()).containsKey("file-model");
	}

	@Test
	@DisplayName("database outage degrades to file-only reads and 503 mutations")
	void databaseOutageDegrades() {
		List<ModelAliasDefinition> outageRows = new CopyOnWriteArrayList<>();
		AtomicBoolean dbUp = new AtomicBoolean(false);
		ModelAliasRepository failing = mock(ModelAliasRepository.class);
		when(failing.findAll()).thenAnswer(invocation -> {
			if (!dbUp.get()) {
				throw new RuntimeException("connection refused");
			}
			return List.copyOf(outageRows);
		});
		when(failing.save(any(ModelAliasDefinition.class))).thenAnswer(invocation -> {
			ModelAliasDefinition definition = invocation.getArgument(0);
			outageRows.add(definition);
			return definition;
		});
		when(failing.existsById(any(String.class))).thenReturn(false);
		GatewayProperties outageProperties = freshProperties();
		ModelAliasRegistry outage =
				new ModelAliasRegistry(failing, outageProperties, new ObjectMapper());

		outage.loadOnReady();

		assertThat(outage.list()).extracting(ModelDefinition::name).containsExactly("file-model");
		assertThatThrownBy(() -> outage.create("fast-gpt", OPENAI_CHAIN, "SEQUENTIAL"))
				.isInstanceOf(ResponseStatusException.class)
				.extracting(ex -> ((ResponseStatusException) ex).getStatusCode())
				.isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);

		dbUp.set(true);
		outage.retryLoad();
		outage.create("fast-gpt", OPENAI_CHAIN, "SEQUENTIAL");

		assertThat(outageProperties.getAliases()).containsKey("fast-gpt");
	}

	@Test
	@DisplayName("retry is a no-op once loaded and reloads are idempotent")
	void retryAndReloadAreIdempotent() {
		registry.retryLoad();
		registry.loadOnReady();

		assertThat(properties.getAliases()).containsKey("file-model");
	}

	@Test
	@DisplayName("codec failure maps to 500")
	void codecFailureMapsToServerError() throws Exception {
		ObjectMapper failingMapper = mock(ObjectMapper.class);
		when(failingMapper.writeValueAsString(any()))
				.thenThrow(new JacksonException("encode failed") {
				});
		ModelAliasRegistry failingRegistry =
				new ModelAliasRegistry(repository, freshProperties(), failingMapper);
		failingRegistry.loadOnReady();

		assertThatThrownBy(() -> failingRegistry.create("fast-gpt", OPENAI_CHAIN, "SEQUENTIAL"))
				.isInstanceOf(ResponseStatusException.class)
				.extracting(ex -> ((ResponseStatusException) ex).getStatusCode())
				.isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);
	}

	@Test
	@Timeout(value = 30, unit = TimeUnit.SECONDS)
	@DisplayName("concurrent creates publish every alias exactly once")
	void concurrentCreatesPublishAll() throws Exception {
		int writers = 32;
		ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();
		CountDownLatch start = new CountDownLatch(1);
		CountDownLatch done = new CountDownLatch(writers);
		AtomicReference<Throwable> failure = new AtomicReference<>();
		try {
			for (int i = 0; i < writers; i++) {
				String name = "model-" + i;
				executor.submit(() -> {
					try {
						start.await();
						registry.create(name, OPENAI_CHAIN, "SEQUENTIAL");
					} catch (Throwable ex) {
						failure.compareAndSet(null, ex);
					} finally {
						done.countDown();
					}
				});
			}
			start.countDown();
			assertThat(done.await(20, TimeUnit.SECONDS)).isTrue();
		} finally {
			executor.shutdownNow();
		}

		assertThat(failure.get()).isNull();
		for (int i = 0; i < writers; i++) {
			assertThat(properties.getAliases()).containsKey("model-" + i);
		}
	}

	private GatewayProperties freshProperties() {
		GatewayProperties fresh = new GatewayProperties();
		fresh.setProviders(Map.of("openai", new ProviderConfig("openai", ProviderType.OPENAI,
				URI.create("https://api.openai.com"), new SensitiveString("test"),
				Duration.ofSeconds(3), Duration.ofSeconds(30))));
		fresh.setAliases(Map.of("file-model", new ModelAlias(
				List.of(new ProviderRef("openai", null)), FailoverStrategy.SEQUENTIAL)));
		return fresh;
	}

	private static ModelAliasRepository fakeRepository(List<ModelAliasDefinition> rows) {
		ModelAliasRepository repo = mock(ModelAliasRepository.class);
		when(repo.findAll()).thenAnswer(invocation -> List.copyOf(rows));
		when(repo.save(any(ModelAliasDefinition.class))).thenAnswer(invocation -> {
			ModelAliasDefinition definition = invocation.getArgument(0);
			rows.removeIf(row -> row.getName().equals(definition.getName()));
			rows.add(definition);
			return definition;
		});
		when(repo.existsById(any(String.class))).thenAnswer(invocation ->
				rows.stream().anyMatch(row -> row.getName().equals(invocation.getArgument(0))));
		when(repo.findById(any(String.class))).thenAnswer(invocation ->
				rows.stream().filter(row -> row.getName().equals(invocation.getArgument(0))).findFirst());
		doAnswer(invocation -> {
			rows.removeIf(row -> row.getName().equals(invocation.getArgument(0)));
			return null;
		}).when(repo).deleteById(any(String.class));
		return repo;
	}
}
