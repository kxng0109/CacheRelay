package io.github.kxng0109.cacherelay.auth;

import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

/**
 * Persistence for {@link UserAccount}.
 */
public interface UserAccountRepository extends JpaRepository<UserAccount, UUID> {

	/**
	 * Finds an account by login name, case-insensitively (backed by {@code uq_auth_user_username}).
	 *
	 * @param username login name in any case
	 * @return the account, or empty
	 */
	Optional<UserAccount> findByUsernameIgnoreCase(String username);
}
