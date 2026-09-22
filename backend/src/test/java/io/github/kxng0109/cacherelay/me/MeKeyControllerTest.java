package io.github.kxng0109.cacherelay.me;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.server.ResponseStatusException;

import io.github.kxng0109.cacherelay.admin.dto.KeyResponse;
import io.github.kxng0109.cacherelay.auth.JwtService;
import io.github.kxng0109.cacherelay.auth.UserAccount;
import io.github.kxng0109.cacherelay.auth.UserAccountRepository;
import io.github.kxng0109.cacherelay.contracts.SHA256Hash;
import io.github.kxng0109.cacherelay.contracts.VirtualApiKey;
import io.github.kxng0109.cacherelay.me.dto.DefaultKeyRequest;
import io.github.kxng0109.cacherelay.security.ratelimit.KeyManagementService;

/**
 * Self-service key surface: session-authenticated users manage only their own
 * keys by metadata (secrets never cross this boundary), with cross-user
 * access indistinguishable from absence.
 */
@DisplayName("MeKeyController")
class MeKeyControllerTest {

	private final KeyManagementService keys = mock(KeyManagementService.class);
	private final JwtService sessions = mock(JwtService.class);
	private final UserAccountRepository users = mock(UserAccountRepository.class);
	private final MeKeyController controller = new MeKeyController(keys, sessions, users);
	private final UUID userId = UUID.randomUUID();

	private static final String SESSION = "session-jwt";

	private Jwt sessionFor(UUID user) {
		Instant now = Instant.now();
		return new Jwt("session", now, now.plusSeconds(600),
				Map.of("alg", "HS256"), Map.of("sub", user.toString()));
	}

	private void stubSession() {
		when(sessions.validate(SESSION)).thenReturn(sessionFor(userId));
		UserAccount account = new UserAccount("local", null, null, false);
		when(users.findById(userId)).thenReturn(Optional.of(account));
	}

	private VirtualApiKey ownedKey(SHA256Hash hash, UUID owner) {
		return new VirtualApiKey(
				hash, "gw-", "tenant-a", "mine", 60, 1000,
				Set.of(), Set.of(), Set.of(), Set.of(), Set.of(), Set.of(),
				Set.of(), Set.of(), true, true, Instant.now(),
				Set.of(), Set.of(), Set.of(), owner, false);
	}

	private String bearer() {
		return "Bearer " + SESSION;
	}

	@Test
	@DisplayName("lists only the caller's keys without secrets")
	void listsOwnKeys() {
		stubSession();
		SHA256Hash mine = SHA256Hash.fromRawKey("gw-own-key-00000000000000000000001");
		when(keys.listKeysByUser(userId)).thenReturn(List.of(ownedKey(mine, userId)));

		ResponseEntity<List<KeyResponse>> response = controller.listMyKeys(bearer());

		assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
		assertThat(response.getBody()).isNotNull();
		assertThat(response.getBody()).hasSize(1);
		assertThat(response.getBody().getFirst().keyId()).isEqualTo(mine.hex());
	}

	@Test
	@DisplayName("invalid sessions answer 401 without touching keys")
	void invalidSessionAnswers401() {
		when(sessions.validate(SESSION)).thenThrow(new RuntimeException("bad signature"));

		assertThatThrownBy(() -> controller.listMyKeys(bearer()))
				.isInstanceOf(ResponseStatusException.class)
				.extracting(ex -> ((ResponseStatusException) ex).getStatusCode())
				.isEqualTo(HttpStatus.UNAUTHORIZED);
		verify(keys, never()).listKeysByUser(any());
	}

	@Test
	@DisplayName("disabled accounts answer 401")
	void disabledAccountAnswers401() {
		when(sessions.validate(SESSION)).thenReturn(sessionFor(userId));
		UserAccount disabled = mock(UserAccount.class);
		when(disabled.isDisabled()).thenReturn(true);
		when(users.findById(userId)).thenReturn(Optional.of(disabled));

		assertThatThrownBy(() -> controller.listMyKeys(bearer()))
				.isInstanceOf(ResponseStatusException.class)
				.extracting(ex -> ((ResponseStatusException) ex).getStatusCode())
				.isEqualTo(HttpStatus.UNAUTHORIZED);
	}

	@Test
	@DisplayName("setting the default requires ownership")
	void defaultRequiresOwnership() {
		stubSession();
		SHA256Hash mine = SHA256Hash.fromRawKey("gw-own-key-00000000000000000000001");
		SHA256Hash foreign = SHA256Hash.fromRawKey("gw-own-key-00000000000000000000002");
		when(keys.findByHash(mine)).thenReturn(Optional.of(ownedKey(mine, userId)));
		when(keys.findByHash(foreign))
				.thenReturn(Optional.of(ownedKey(foreign, UUID.randomUUID())));

		assertThat(controller.setMyDefault(bearer(), new DefaultKeyRequest(mine.hex())).getStatusCode())
				.isEqualTo(HttpStatus.NO_CONTENT);
		verify(keys).setDefaultKey(userId, mine);

		assertThatThrownBy(() -> controller.setMyDefault(bearer(), new DefaultKeyRequest(foreign.hex())))
				.isInstanceOf(ResponseStatusException.class)
				.extracting(ex -> ((ResponseStatusException) ex).getStatusCode())
				.isEqualTo(HttpStatus.NOT_FOUND);
		verify(keys, never()).setDefaultKey(eq(userId), eq(foreign));
	}

	@Test
	@DisplayName("revoking touches only owned keys, terminally")
	void revokeOwnOnly() {
		stubSession();
		SHA256Hash mine = SHA256Hash.fromRawKey("gw-own-key-00000000000000000000001");
		SHA256Hash foreign = SHA256Hash.fromRawKey("gw-own-key-00000000000000000000002");
		when(keys.findByHash(mine)).thenReturn(Optional.of(ownedKey(mine, userId)));
		when(keys.findByHash(foreign))
				.thenReturn(Optional.of(ownedKey(foreign, UUID.randomUUID())));

		assertThat(controller.revokeMyKey(bearer(), mine.hex()).getStatusCode())
				.isEqualTo(HttpStatus.NO_CONTENT);
		verify(keys).revokeKey(mine);

		assertThatThrownBy(() -> controller.revokeMyKey(bearer(), foreign.hex()))
				.isInstanceOf(ResponseStatusException.class)
				.extracting(ex -> ((ResponseStatusException) ex).getStatusCode())
				.isEqualTo(HttpStatus.NOT_FOUND);
		verify(keys, never()).revokeKey(foreign);
	}

	@Test
	@DisplayName("unknown keys answer 404 on default selection and revocation")
	void unknownKeysAnswer404() {
		stubSession();
		SHA256Hash missing = SHA256Hash.fromHex("b".repeat(64));
		when(keys.findByHash(missing)).thenReturn(Optional.empty());

		assertThatThrownBy(() -> controller.setMyDefault(bearer(), new DefaultKeyRequest(missing.hex())))
				.isInstanceOf(ResponseStatusException.class)
				.extracting(ex -> ((ResponseStatusException) ex).getStatusCode())
				.isEqualTo(HttpStatus.NOT_FOUND);
		assertThatThrownBy(() -> controller.revokeMyKey(bearer(), missing.hex()))
				.isInstanceOf(ResponseStatusException.class)
				.extracting(ex -> ((ResponseStatusException) ex).getStatusCode())
				.isEqualTo(HttpStatus.NOT_FOUND);
	}

	@Test
	@DisplayName("malformed hashes answer 400")
	void malformedHashesAnswer400() {
		stubSession();

		assertThatThrownBy(() -> controller.setMyDefault(bearer(), new DefaultKeyRequest("short")))
				.isInstanceOf(ResponseStatusException.class)
				.extracting(ex -> ((ResponseStatusException) ex).getStatusCode())
				.isEqualTo(HttpStatus.BAD_REQUEST);
		assertThatThrownBy(() -> controller.revokeMyKey(bearer(), "not-hex-at-all"))
				.isInstanceOf(ResponseStatusException.class)
				.extracting(ex -> ((ResponseStatusException) ex).getStatusCode())
				.isEqualTo(HttpStatus.BAD_REQUEST);
		assertThatThrownBy(() -> controller.revokeMyKey(bearer(), null))
				.isInstanceOf(ResponseStatusException.class)
				.extracting(ex -> ((ResponseStatusException) ex).getStatusCode())
				.isEqualTo(HttpStatus.BAD_REQUEST);
	}

	@Test
	@DisplayName("missing sessions answer 401 and prefix-less tokens work")
	void malformedSessionsAnswer401() {
		stubSession();

		assertThatThrownBy(() -> controller.listMyKeys(null))
				.isInstanceOf(ResponseStatusException.class)
				.extracting(ex -> ((ResponseStatusException) ex).getStatusCode())
				.isEqualTo(HttpStatus.UNAUTHORIZED);

		when(keys.listKeysByUser(userId)).thenReturn(List.of());
		ResponseEntity<List<KeyResponse>> response = controller.listMyKeys(SESSION);

		assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
		assertThat(response.getBody()).isNotNull();
		assertThat(response.getBody()).isEmpty();
	}

	@Test
	@DisplayName("null key hashes map to empty ids")
	void nullHashMapsEmpty() {
		stubSession();
		VirtualApiKey hashless = new VirtualApiKey(
				null, "gw-", "tenant-a", "mine", 60, 1000,
				Set.of(), Set.of(), Set.of(), Set.of(), Set.of(), Set.of(),
				Set.of(), Set.of(), true, true, Instant.now(),
				Set.of(), Set.of(), Set.of(), userId, false);
		when(keys.listKeysByUser(userId)).thenReturn(List.of(hashless));

		ResponseEntity<List<KeyResponse>> response = controller.listMyKeys(bearer());

		assertThat(response.getBody()).isNotNull();
		assertThat(response.getBody()).hasSize(1);
		assertThat(response.getBody().getFirst().keyId()).isEmpty();
	}
}
