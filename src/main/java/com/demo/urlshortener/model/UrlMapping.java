package com.demo.urlshortener.model;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;

/**
 * JPA entity representing a single short-URL-to-original-URL mapping.
 *
 * <p>Persisted to the {@code url_mappings} table in PostgreSQL. The {@code short_code}
 * column has a unique index ({@code idx_short_code}) to enforce global uniqueness and
 * support fast point lookups on the redirect hot-path.
 *
 * <p>Lifecycle notes:
 * <ul>
 *   <li>{@code createdAt} is set automatically by the {@link #prePersist()} JPA lifecycle
 *       callback and is immutable after insert ({@code updatable = false}).</li>
 *   <li>{@code expiresAt} is nullable. A {@code null} value means the URL never expires.</li>
 *   <li>During auto-code generation the entity is first saved with {@code shortCode = "PENDING"}
 *       to obtain the auto-assigned ID. The code is then updated in a second save.</li>
 * </ul>
 */
@Entity
@Table(
    name = "url_mappings",
    indexes = {
        @Index(name = "idx_short_code", columnList = "short_code", unique = true)
    }
)
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class UrlMapping {

    /** Auto-assigned primary key. Used as the input to {@link com.demo.urlshortener.util.Base62Encoder}. */
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /**
     * The unique short code served to clients (e.g., {@code "abc123"} or a custom alias).
     * Maximum length is 20 characters to accommodate both Base62-encoded IDs and user aliases.
     */
    @Column(name = "short_code", nullable = false, unique = true, length = 20)
    private String shortCode;

    /**
     * The full original URL that clients are redirected to.
     * Maximum length is 2048 characters, covering most real-world URLs.
     */
    @Column(name = "original_url", nullable = false, length = 2048)
    private String originalUrl;

    /**
     * Timestamp when this mapping was created. Set automatically by {@link #prePersist()}
     * and never updated thereafter.
     */
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    /**
     * Optional absolute expiration timestamp. When set, the URL becomes inaccessible
     * after this instant. {@code null} means the URL never expires.
     */
    @Column(name = "expires_at")
    private Instant expiresAt;

    /**
     * JPA pre-persist lifecycle callback that stamps {@link #createdAt} with the
     * current UTC instant before the entity is first written to the database.
     */
    @PrePersist
    void prePersist() {
        createdAt = Instant.now();
    }

    /**
     * Checks whether this URL mapping has passed its expiration time.
     *
     * @return {@code true} if {@link #expiresAt} is non-null and the current time is
     *         after it; {@code false} if the URL has no expiry or has not yet expired
     */
    public boolean isExpired() {
        return expiresAt != null && Instant.now().isAfter(expiresAt);
    }
}
