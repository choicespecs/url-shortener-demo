package com.demo.urlshortener.repository;

import com.demo.urlshortener.model.UrlMapping;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.Optional;

/**
 * Spring Data JPA repository for {@link UrlMapping} entities.
 *
 * <p>Provides standard CRUD operations inherited from {@link JpaRepository} plus
 * three custom queries for the URL-shortener business logic:
 * <ul>
 *   <li>Point lookup by short code (redirect hot-path)</li>
 *   <li>Existence check for alias uniqueness validation</li>
 *   <li>Bulk delete of expired records (nightly cleanup job)</li>
 * </ul>
 *
 * <p>The {@code short_code} column has a unique database index ({@code idx_short_code})
 * so both {@link #findByShortCode} and {@link #existsByShortCode} execute as O(log n)
 * B-tree index scans.
 */
@Repository
public interface UrlRepository extends JpaRepository<UrlMapping, Long> {

    /**
     * Finds a URL mapping by its short code.
     *
     * <p>Used by {@link com.demo.urlshortener.service.UrlService#resolve(String)} on a
     * Redis cache miss. Backed by the unique index on {@code short_code}.
     *
     * @param shortCode the short code to look up
     * @return an {@link Optional} containing the mapping, or empty if not found
     */
    Optional<UrlMapping> findByShortCode(String shortCode);

    /**
     * Checks whether a short code is already registered.
     *
     * <p>Used by {@link com.demo.urlshortener.service.UrlService#shorten} to validate
     * custom alias uniqueness before attempting an insert.
     *
     * @param shortCode the short code or alias to check
     * @return {@code true} if a record with this short code exists; {@code false} otherwise
     */
    boolean existsByShortCode(String shortCode);

    /**
     * Bulk-deletes all URL mappings whose expiration timestamp is set and has passed.
     *
     * <p>Executed by {@link com.demo.urlshortener.scheduler.ExpirationCleanupJob} on a
     * nightly schedule. The query targets only rows where {@code expires_at IS NOT NULL}
     * to leave permanent URLs (null expiry) untouched.
     *
     * <p>{@code @Modifying} is required for JPQL {@code DELETE} statements to signal to
     * Spring Data that this query mutates the database. Must be called within a transaction.
     *
     * @param now the current instant used as the expiry boundary; all rows with
     *            {@code expires_at < now} are deleted
     * @return the number of rows deleted
     */
    @Modifying
    @Query("DELETE FROM UrlMapping u WHERE u.expiresAt IS NOT NULL AND u.expiresAt < :now")
    int deleteAllExpired(Instant now);
}
