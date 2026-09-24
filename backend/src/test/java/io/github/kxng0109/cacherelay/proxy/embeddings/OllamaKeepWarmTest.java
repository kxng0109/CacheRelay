package io.github.kxng0109.cacherelay.proxy.embeddings;

import io.github.kxng0109.cacherelay.cache.config.CacheRelayCacheProperties;
import io.github.kxng0109.cacherelay.contracts.ProviderConfig;
import io.github.kxng0109.cacherelay.contracts.ProviderType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.concurrent.atomic.AtomicReference;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import org.slf4j.LoggerFactory;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@DisplayName("OllamaKeepWarm")
class OllamaKeepWarmTest {

	private final EmbeddingService embeddingService = mock(EmbeddingService.class);
	private final HttpClient httpClient = mock(HttpClient.class);
	@SuppressWarnings("unchecked")
	private final HttpResponse<byte[]> response = mock(HttpResponse.class);
	private final CacheRelayCacheProperties cacheProperties = new CacheRelayCacheProperties();

	private final ManualClock clock =
			new ManualClock(Instant.parse("2026-09-23T12:00:00Z"));

	private OllamaKeepWarm keepWarm;

	@BeforeEach
	@SuppressWarnings("unchecked")
	void setUp() {
		cacheProperties.getSemantic().setEmbeddingModel("local-embed");
		when(response.statusCode()).thenReturn(200);
		keepWarm = new OllamaKeepWarm(
				embeddingService, cacheProperties, httpClient, EmbeddingProperties.DEFAULTS,
				clock);
	}

	private EmbeddingService.WarmTarget target(ProviderType type) {
		return target(type, URI.create("http://localhost:11434/api/embed"));
	}

	private EmbeddingService.WarmTarget target(ProviderType type, URI targetUri) {
		ProviderConfig config = new ProviderConfig(
				"provider-" + type.name().toLowerCase(),
				type,
				URI.create("http://localhost:11434"),
				null,
				Duration.ofSeconds(5),
				Duration.ofSeconds(60),
				false
		);
		return new EmbeddingService.WarmTarget(
				config, "nomic-embed-text:latest", targetUri);
	}

	@Test
	@DisplayName("disabled heartbeat never resolves a target nor sends a ping")
	void disabledSkipsPing() throws Exception {
		keepWarm = new OllamaKeepWarm(
				embeddingService, cacheProperties, httpClient,
				new EmbeddingProperties(2_048, 4, false), clock);

		keepWarm.warm();

		verify(embeddingService, never()).resolveWarmTarget(anyString());
		verify(httpClient, never()).send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class));
	}

	@Test
	@DisplayName("enabled heartbeat pings the resolved Ollama /api/embed endpoint")
	void enabledPingsOllamaTarget() throws Exception {
		when(embeddingService.resolveWarmTarget("local-embed")).thenReturn(target(ProviderType.OLLAMA));
		doReturn(response).when(httpClient).send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class));

		keepWarm.warm();

		HttpRequest sent = captureRequest();
		assertThat(sent).isNotNull();
		assertThat(sent.method()).isEqualTo("POST");
		assertThat(sent.uri().toString()).isEqualTo("http://localhost:11434/api/embed");
		assertThat(sent.timeout().orElseThrow()).isEqualTo(Duration.ofSeconds(5));
		assertThat(sent.bodyPublisher()).isPresent();
	}

	@Test
	@DisplayName("unresolvable semantic model skips the ping silently")
	void unresolvableTargetSkips() throws Exception {
		when(embeddingService.resolveWarmTarget("local-embed")).thenReturn(null);

		keepWarm.warm();

		verify(httpClient, never()).send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class));
	}

	@Test
	@DisplayName("non-Ollama semantic provider skips the ping (no local GPU to keep warm)")
	void nonOllamaTargetSkips() throws Exception {
		when(embeddingService.resolveWarmTarget("local-embed")).thenReturn(target(
				ProviderType.OPENAI, URI.create("https://api.openai.com/v1/embeddings")));

		keepWarm.warm();

		verify(httpClient, never()).send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class));
	}

	@Test
	@DisplayName("Ollama-typed providers ping even on custom ports")
	void ollamaTypeCustomPortPings() throws Exception {
		when(embeddingService.resolveWarmTarget("local-embed")).thenReturn(target(
				ProviderType.OLLAMA, URI.create("http://gpu-box:8080/v1/embeddings")));
		doReturn(response).when(httpClient).send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class));

		keepWarm.warm();

		verify(httpClient).send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class));
	}

	@Test
	@DisplayName("mislabeled Ollama URLs ping by port or embeddings path")
	void ollamaUrlPingsDespiteType() throws Exception {
		when(embeddingService.resolveWarmTarget("local-embed")).thenReturn(target(
				ProviderType.OPENAI, URI.create("http://localhost:11434/v1/embeddings")));
		doReturn(response).when(httpClient).send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class));

		keepWarm.warm();

		verify(httpClient).send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class));

		when(embeddingService.resolveWarmTarget("local-embed")).thenReturn(target(
				ProviderType.OPENAI, URI.create("https://ollama.example.com/api/embed")));
		doReturn(response).when(httpClient).send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class));

		keepWarm.warm();

		verify(httpClient, times(2)).send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class));
	}

	@Test
	@DisplayName("a dead Ollama never crashes the scheduler: send failures are swallowed")
	void sendFailureSwallowed() throws Exception {
		when(embeddingService.resolveWarmTarget("local-embed")).thenReturn(target(ProviderType.OLLAMA));
		doThrow(new IOException("connection refused")).when(httpClient)
				.send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class));

		assertThatCode(keepWarm::warm).doesNotThrowAnyException();
		assertThatCode(keepWarm::warm).doesNotThrowAnyException();

		verify(httpClient, times(2)).send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class));
	}

	@Test
	@DisplayName("an interrupted ping restores the interrupt flag and never crashes the scheduler")
	void interruptedPingSwallowed() throws Exception {
		when(embeddingService.resolveWarmTarget("local-embed")).thenReturn(target(ProviderType.OLLAMA));
		doThrow(new InterruptedException()).when(httpClient)
				.send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class));

		assertThatCode(keepWarm::warm).doesNotThrowAnyException();

		verify(httpClient).send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class));
	}

	@Test
	@DisplayName("a recovered ping after failures resets the failure counter and logs the recovery")
	void recoveredPingResetsFailureCounter() throws Exception {		when(embeddingService.resolveWarmTarget("local-embed")).thenReturn(target(ProviderType.OLLAMA));
		doThrow(new IOException("connection refused")).when(httpClient)
				.send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class));
		keepWarm.warm();

		doReturn(response).when(httpClient).send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class));
		keepWarm.warm();
		keepWarm.warm();

		verify(httpClient, times(3)).send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class));
	}

	@Test
	@DisplayName("opaque URLs without paths never count as Ollama targets")
	void opaqueUrlSkips() throws Exception {
		when(embeddingService.resolveWarmTarget("local-embed")).thenReturn(target(
				ProviderType.OPENAI, URI.create("mailto:ollama@example.com")));

		keepWarm.warm();

		verify(httpClient, never()).send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class));
	}

	@Test
	@DisplayName("failure warns once then at most hourly while the outage persists")
	void failureWarnsHourly() throws Exception {
		when(embeddingService.resolveWarmTarget("local-embed")).thenReturn(target(ProviderType.OLLAMA));
		doThrow(new IOException("connection refused")).when(httpClient)
				.send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class));
		Logger logger = (Logger) LoggerFactory.getLogger(OllamaKeepWarm.class);
		ListAppender<ILoggingEvent> appender = new ListAppender<>();
		appender.start();
		Level previous = logger.getLevel();		logger.setLevel(Level.DEBUG);
		logger.addAppender(appender);
		try {
			keepWarm.warm();
			keepWarm.warm();
			clock.advance(Duration.ofMinutes(59));
			keepWarm.warm();
			clock.advance(Duration.ofMinutes(2));
			keepWarm.warm();

			long warns = appender.list.stream()
					.filter(event -> event.getLevel() == Level.WARN)
					.count();
			long debugs = appender.list.stream()
					.filter(event -> event.getLevel() == Level.DEBUG)
					.count();
			assertThat(warns).isEqualTo(2);
			assertThat(debugs).isEqualTo(2);
			assertThat(appender.list.get(0).getFormattedMessage()).contains("(1 consecutive)");
			assertThat(appender.list.get(appender.list.size() - 1).getFormattedMessage())
					.contains("(4 consecutive)");
		} finally {
			logger.setLevel(previous);
			logger.detachAppender(appender);
			appender.stop();
		}
	}

	@Test
	@DisplayName("null exception messages fall back to the exception class")
	void nullMessageLogsClass() throws Exception {
		when(embeddingService.resolveWarmTarget("local-embed")).thenReturn(target(ProviderType.OLLAMA));
		doThrow(new IOException()).when(httpClient)
				.send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class));
		Logger logger = (Logger) LoggerFactory.getLogger(OllamaKeepWarm.class);
		ListAppender<ILoggingEvent> appender = new ListAppender<>();
		appender.start();
		logger.addAppender(appender);
		try {
			keepWarm.warm();

			assertThat(appender.list).hasSize(1);
			assertThat(appender.list.get(0).getFormattedMessage()).contains("IOException");
		} finally {
			logger.detachAppender(appender);
			appender.stop();
		}
	}

	@Test
	@DisplayName("recovery info carries the true consecutive failure count")
	void recoveryInfoCarriesCount() throws Exception {
		when(embeddingService.resolveWarmTarget("local-embed")).thenReturn(target(ProviderType.OLLAMA));
		doThrow(new IOException("connection refused")).when(httpClient)
				.send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class));
		keepWarm.warm();
		keepWarm.warm();
		keepWarm.warm();
		doReturn(response).when(httpClient).send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class));
		Logger logger = (Logger) LoggerFactory.getLogger(OllamaKeepWarm.class);
		ListAppender<ILoggingEvent> appender = new ListAppender<>();
		appender.start();
		logger.addAppender(appender);
		try {
			keepWarm.warm();

			assertThat(appender.list).hasSize(1);
			assertThat(appender.list.get(0).getLevel())
					.isEqualTo(Level.INFO);
			assertThat(appender.list.get(0).getFormattedMessage()).contains("3 consecutive");
		} finally {
			logger.detachAppender(appender);
			appender.stop();
		}
	}

	private static final class ManualClock extends Clock {
		private final AtomicReference<Instant> now;

		ManualClock(Instant now) {
			this.now = new AtomicReference<>(now);
		}

		void advance(Duration step) {
			now.updateAndGet(current -> current.plus(step));
		}

		@Override
		public ZoneId getZone() {
			return ZoneOffset.UTC;
		}

		@Override
		public Clock withZone(ZoneId zone) {
			return this;
		}

		@Override
		public Instant instant() {
			return now.get();
		}
	}

	private HttpRequest captureRequest() throws Exception {		ArgumentCaptor<HttpRequest> captor = ArgumentCaptor.forClass(HttpRequest.class);
		verify(httpClient).send(captor.capture(), any(HttpResponse.BodyHandler.class));
		return captor.getValue();
	}
}
