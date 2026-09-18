package io.github.kxng0109.cacherelay.auth;

import java.lang.reflect.Field;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/**
 * Unit tests for opaque refresh rotation: mint, success, invalid, reuse, races.
 */
@DisplayName("RefreshService")
class RefreshServiceTest {

	private RefreshTokenRepository repository;
	private UserAccountRepository users;
	private RefreshService service;

	@BeforeEach
	void setUp() {
		repository = mock(RefreshTokenRepository.class);
		users = mock(UserAccountRepository.class);
		service = new RefreshService(repository, users, AuthProperties.defaults());
	}

	@Test
	@DisplayName("mint persists a hashed row and returns the opaque token")
	void mintPersistsHashedRow() {
		UUID userId = UUID.randomUUID();

		String token = service.mint(userId, false);

		assertThat(token).as("opaque token").hasSize(43);
		ArgumentCaptor<RefreshToken> saved = ArgumentCaptor.forClass(RefreshToken.class);
		verify(repository).save(saved.capture());
		assertThat(saved.getValue().getUserId()).isEqualTo(userId);
		assertThat(saved.getValue().getTokenHash())
				.as("stored hash, not plaintext")
				.isNotEqualTo(token)
				.hasSize(64);
		assertThat(saved.getValue().getAbsoluteExpiresAt())
				.isAfter(saved.getValue().getExpiresAt());
	}

	@Test
	@DisplayName("rotate of an unknown token is invalid")
	void rotateUnknownIsInvalid() {
		when(repository.findByTokenHash(any())).thenReturn(Optional.empty());

		assertThat(service.rotate("nope")).isInstanceOf(RefreshService.RotationInvalid.class);
	}

	@Test
	@DisplayName("rotate of a live token succeeds with a successor in the same family")
	void rotateLiveSucceeds() {
		UUID userId = UUID.randomUUID();
		UUID family = UUID.randomUUID();
		RefreshToken current = liveRow(family, userId);
		UserAccount account = new UserAccount("op", "hash", null, true);
		set(account, "id", userId);
		when(repository.findByTokenHash(any())).thenReturn(Optional.of(current));
		when(users.findById(userId)).thenReturn(Optional.of(account));
		when(repository.markReplaced(any(), any())).thenReturn(1);

		RefreshService.RotationResult result = service.rotate(plainFor(current));

		assertThat(result).isInstanceOf(RefreshService.RotationSuccess.class);
		RefreshService.RotationSuccess success = (RefreshService.RotationSuccess) result;
		assertThat(success.userId()).isEqualTo(userId);
		assertThat(success.admin()).isTrue();
		assertThat(success.token()).isNotEmpty();
		ArgumentCaptor<RefreshToken> saved = ArgumentCaptor.forClass(RefreshToken.class);
		verify(repository).save(saved.capture());
		assertThat(saved.getValue().getFamilyId()).isEqualTo(family);
		assertThat(saved.getValue().getAbsoluteExpiresAt())
				.isEqualTo(current.getAbsoluteExpiresAt());
	}

	@Test
	@DisplayName("rotate of a replaced token revokes the family (reuse)")
	void rotateReplacedRevokesFamily() {
		RefreshToken current = liveRow(UUID.randomUUID(), UUID.randomUUID());
		set(current, "replacedBy", UUID.randomUUID());
		when(repository.findByTokenHash(any())).thenReturn(Optional.of(current));

		assertThat(service.rotate(plainFor(current)))
				.isInstanceOf(RefreshService.RotationReuse.class);
		verify(repository).revokeFamily(any(), any());
	}

	@Test
	@DisplayName("rotate of expired, revoked, or ceiling-hit tokens is invalid")
	void rotateDeadTokensInvalid() {
		RefreshToken expired = liveRow(UUID.randomUUID(), UUID.randomUUID());
		set(expired, "expiresAt", Instant.now().minusSeconds(60));
		RefreshToken revoked = liveRow(UUID.randomUUID(), UUID.randomUUID());
		set(revoked, "revokedAt", Instant.now());
		RefreshToken ceiling = liveRow(UUID.randomUUID(), UUID.randomUUID());
		set(ceiling, "absoluteExpiresAt", Instant.now().minusSeconds(60));

		when(repository.findByTokenHash("expired")).thenReturn(Optional.of(expired));
		when(repository.findByTokenHash("revoked")).thenReturn(Optional.of(revoked));
		when(repository.findByTokenHash("ceiling")).thenReturn(Optional.of(ceiling));

		assertThat(service.rotate("expired")).isInstanceOf(RefreshService.RotationInvalid.class);
		assertThat(service.rotate("revoked")).isInstanceOf(RefreshService.RotationInvalid.class);
		assertThat(service.rotate("ceiling")).isInstanceOf(RefreshService.RotationInvalid.class);
		verifyNoInteractions(users);
	}

	@Test
	@DisplayName("rotate for a disabled account revokes the family and is invalid")
	void rotateDisabledAccountInvalid() {
		UUID userId = UUID.randomUUID();
		RefreshToken current = liveRow(UUID.randomUUID(), userId);
		UserAccount account = new UserAccount("op", "hash", null, false);
		set(account, "id", userId);
		set(account, "disabled", true);
		when(repository.findByTokenHash(any())).thenReturn(Optional.of(current));
		when(users.findById(userId)).thenReturn(Optional.of(account));

		assertThat(service.rotate(plainFor(current)))
				.isInstanceOf(RefreshService.RotationInvalid.class);
		verify(repository).revokeFamily(any(), any());
	}

	@Test
	@DisplayName("rotate lost race (replaced by a concurrent caller) is invalid")
	void rotateLostRaceInvalid() {
		RefreshToken current = liveRow(UUID.randomUUID(), UUID.randomUUID());
		UUID userId = current.getUserId();
		UserAccount account = new UserAccount("op", "hash", null, false);
		set(account, "id", userId);
		when(repository.findByTokenHash(any())).thenReturn(Optional.of(current));
		when(users.findById(userId)).thenReturn(Optional.of(account));
		when(repository.markReplaced(any(), any())).thenReturn(0);

		assertThat(service.rotate(plainFor(current)))
				.isInstanceOf(RefreshService.RotationInvalid.class);
	}

	@Test
	@DisplayName("revokeAll revokes every live family of the account")
	void revokeAllRevokesFamilies() {
		UUID userId = UUID.randomUUID();
		UUID other = UUID.randomUUID();
		RefreshToken mine = liveRow(UUID.randomUUID(), userId);
		RefreshToken mineRevoked = liveRow(UUID.randomUUID(), userId);
		set(mineRevoked, "revokedAt", Instant.now());
		RefreshToken theirs = liveRow(UUID.randomUUID(), other);
		when(repository.findAll()).thenReturn(List.of(mine, mineRevoked, theirs));

		service.revokeAll(userId);

		verify(repository, times(1)).revokeFamily(eq(mine.getFamilyId()), any());
		verify(repository, never()).revokeFamily(eq(theirs.getFamilyId()), any());
	}

	@Test
	@DisplayName("rotate for a deleted account revokes the family and is invalid")
	void rotateUnknownAccountInvalid() {
		RefreshToken current = liveRow(UUID.randomUUID(), UUID.randomUUID());
		when(repository.findByTokenHash(any())).thenReturn(Optional.of(current));
		when(users.findById(current.getUserId())).thenReturn(Optional.empty());

		assertThat(service.rotate("presented"))
				.isInstanceOf(RefreshService.RotationInvalid.class);
		verify(repository).revokeFamily(eq(current.getFamilyId()), any());
	}

	@Test
	@DisplayName("revoking an empty account revokes nothing")
	void revokeAllEmpty() {
		when(repository.findAll()).thenReturn(List.of());

		service.revokeAll(UUID.randomUUID());

		verify(repository, never()).revokeFamily(any(), any());
	}

	private RefreshToken liveRow(UUID family, UUID userId) {
		Instant now = Instant.now();
		RefreshToken row = new RefreshToken(
				family,
				RefreshService.sha256Hex("presented-" + UUID.randomUUID()),
				userId,
				now.plusSeconds(3600),
				now.plusSeconds(86_400));
		set(row, "id", UUID.randomUUID());
		return row;
	}

	private String plainFor(RefreshToken row) {
		return "presented-lookup";
	}

	private static void set(Object target, String field, Object value) {
		try {
			Field declared = findField(target.getClass(), field);
			declared.setAccessible(true);
			declared.set(target, value);
		} catch (ReflectiveOperationException e) {
			throw new IllegalStateException("Test reflection failed", e);
		}
	}

	private static Field findField(Class<?> type, String name) throws NoSuchFieldException {
		Class<?> current = type;
		while (current != null) {
			try {
				return current.getDeclaredField(name);
			} catch (NoSuchFieldException e) {
				current = current.getSuperclass();
			}
		}
		throw new NoSuchFieldException(name);
	}
}
