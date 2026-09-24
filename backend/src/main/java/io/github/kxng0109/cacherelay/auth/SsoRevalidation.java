package io.github.kxng0109.cacherelay.auth;

import java.time.Instant;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;

/**
 * One account's revalidation watermark: when the sweep last checked the
 * account at the IdP and what it found.
 *
 * <p>Rows self-seed at the epoch so first checks run immediately. The
 * timestamp advances on every attempt (success or transport failure alike)
 * so IdP outages retry at cadence instead of hot-looping; the status only
 * ever reflects a positive IdP verdict, never a transport error.</p>
 */
@Entity
@Table(name = "sso_reval_watermark")
@Getter
public class SsoRevalidation {

	@Id
	@Column(name = "user_id", nullable = false, updatable = false)
	private UUID userId;

	@Column(name = "last_verified_at", nullable = false)
	private Instant lastVerifiedAt;

	@Enumerated(EnumType.STRING)
	@Column(name = "last_status", length = 16)
	private RevalidationStatus lastStatus;

	@Column(name = "updated_at", nullable = false)
	private Instant updatedAt = Instant.now();

	/**
	 * No-argument constructor required by the JPA specification.
	 */
	protected SsoRevalidation() {
	}

	/**
	 * @param userId         owning account, never {@code null}
	 * @param lastVerifiedAt last check time, never {@code null}
	 * @param lastStatus     last positive verdict, or {@code null} when never checked
	 */
	public SsoRevalidation(UUID userId, Instant lastVerifiedAt, RevalidationStatus lastStatus) {
		this.userId = userId;
		this.lastVerifiedAt = lastVerifiedAt;
		this.lastStatus = lastStatus;
	}

	/**
	 * Records one attempt's outcome.
	 *
	 * @param verifiedAt check time, never {@code null}
	 * @param status     positive verdict, or {@code null} to keep the previous one
	 */
	public void mark(Instant verifiedAt, RevalidationStatus status) {
		this.lastVerifiedAt = verifiedAt;
		if (status != null) {
			this.lastStatus = status;
		}
		this.updatedAt = Instant.now();
	}
}
