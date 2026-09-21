package io.github.kxng0109.cacherelay.admin;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;

import io.github.kxng0109.cacherelay.admin.dto.CreateModelRequest;
import io.github.kxng0109.cacherelay.admin.dto.ModelDefinitionResponse;
import io.github.kxng0109.cacherelay.admin.dto.ModelListResponse;
import io.github.kxng0109.cacherelay.admin.dto.ProviderStepRequest;
import io.github.kxng0109.cacherelay.admin.dto.UpdateModelRequest;
import io.github.kxng0109.cacherelay.contracts.FailoverStrategy;
import io.github.kxng0109.cacherelay.contracts.ModelAlias;
import io.github.kxng0109.cacherelay.contracts.ProviderRef;
import io.github.kxng0109.cacherelay.model.AliasSource;
import io.github.kxng0109.cacherelay.model.ModelAliasRegistry;
import io.github.kxng0109.cacherelay.model.ModelDefinition;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

/**
 * Unit tests for {@link AdminModelController}: envelope shapes, status codes,
 * and delegation to {@link ModelAliasRegistry}. Authentication and audit live
 * in {@link AdminAuthFilter} and are not exercised here.
 */
@DisplayName("AdminModelController")
class AdminModelControllerTest {

	private ModelAliasRegistry registry;

	private AdminModelController controller;

	@BeforeEach
	void setUp() {
		registry = mock(ModelAliasRegistry.class);
		controller = new AdminModelController(registry);
	}

	@Test
	@DisplayName("list returns the file-then-database envelope with lowercase sources")
	void listReturnsEnvelope() {
		when(registry.list()).thenReturn(List.of(
				new ModelDefinition("file-model", List.of(new ProviderRef("openai", null)),
						FailoverStrategy.SEQUENTIAL, AliasSource.FILE),
				new ModelDefinition("fast-gpt",
						List.of(new ProviderRef("openai", "gpt-5.6-luna")),
						FailoverStrategy.RACE, AliasSource.DATABASE)));

		ResponseEntity<ModelListResponse> response = controller.listModels();

		assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
		assertThat(response.getBody()).isNotNull();
		assertThat(response.getBody().models()).hasSize(2);
		assertThat(response.getBody().models().get(0).source()).isEqualTo("file");
		assertThat(response.getBody().models().get(1).source()).isEqualTo("database");
		assertThat(response.getBody().models().get(1).chain())
				.containsExactly(new ProviderRef("openai", "gpt-5.6-luna"));
	}

	@Test
	@DisplayName("create answers 201 with the database alias")
	void createAnswersCreated() {
		List<ProviderRef> chain = List.of(new ProviderRef("openai", "gpt-5.6-luna"));
		when(registry.create(eq("fast-gpt"), eq(chain), eq("SEQUENTIAL")))
				.thenReturn(new ModelAlias(chain, FailoverStrategy.SEQUENTIAL));

		ResponseEntity<ModelDefinitionResponse> response = controller.createModel(
				new CreateModelRequest("fast-gpt",
						List.of(new ProviderStepRequest("openai", "gpt-5.6-luna")), "SEQUENTIAL"));

		assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
		assertThat(response.getBody()).isNotNull();
		assertThat(response.getBody().name()).isEqualTo("fast-gpt");
		assertThat(response.getBody().strategy()).isEqualTo(FailoverStrategy.SEQUENTIAL);
		assertThat(response.getBody().source()).isEqualTo("database");
		verify(registry).create(eq("fast-gpt"), eq(chain), eq("SEQUENTIAL"));
	}

	@Test
	@DisplayName("create maps a null override through to the registry")
	void createMapsNullOverride() {
		List<ProviderRef> chain = List.of(new ProviderRef("openai", null));
		when(registry.create(eq("fast-gpt"), eq(chain), eq("RACE")))
				.thenReturn(new ModelAlias(chain, FailoverStrategy.RACE));

		ResponseEntity<ModelDefinitionResponse> response = controller.createModel(
				new CreateModelRequest("fast-gpt",
						List.of(new ProviderStepRequest("openai", null)), "RACE"));

		assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
		assertThat(response.getBody()).isNotNull();
		assertThat(response.getBody().chain()).containsExactly(new ProviderRef("openai", null));
	}

	@Test
	@DisplayName("update answers 200 with the replaced alias")
	void updateAnswersOk() {
		List<ProviderRef> chain = List.of(new ProviderRef("openai", "other"));
		when(registry.update(eq("fast-gpt"), eq(chain), eq("RACE")))
				.thenReturn(new ModelAlias(chain, FailoverStrategy.RACE));

		ResponseEntity<ModelDefinitionResponse> response = controller.updateModel("fast-gpt",
				new UpdateModelRequest(List.of(new ProviderStepRequest("openai", "other")), "RACE"));

		assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
		assertThat(response.getBody()).isNotNull();
		assertThat(response.getBody().name()).isEqualTo("fast-gpt");
		assertThat(response.getBody().strategy()).isEqualTo(FailoverStrategy.RACE);
	}

	@Test
	@DisplayName("delete answers 204")
	void deleteAnswersNoContent() {
		ResponseEntity<Void> response = controller.deleteModel("fast-gpt");

		assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
		verify(registry).delete("fast-gpt");
	}

	@Test
	@DisplayName("registry failures propagate untouched")
	void registryFailuresPropagate() {
		when(registry.list()).thenThrow(new IllegalStateException("boom"));

		assertThatThrownBy(() -> controller.listModels())
				.isInstanceOf(IllegalStateException.class)
				.hasMessage("boom");
	}
}
