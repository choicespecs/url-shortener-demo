package com.demo.urlshortener.service;

import com.demo.urlshortener.dto.ShortenRequest;
import com.demo.urlshortener.dto.ShortenResponse;
import com.demo.urlshortener.exception.AliasAlreadyExistsException;
import com.demo.urlshortener.exception.UrlNotFoundException;
import com.demo.urlshortener.model.UrlMapping;
import com.demo.urlshortener.repository.UrlRepository;
import com.demo.urlshortener.util.Base62Encoder;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;

/**
 * Core business logic for the URL shortener service.
 *
 * <p>Implements two operations:
 * <ol>
 *   <li>{@link #shorten(ShortenRequest)} — creates a new short URL mapping in PostgreSQL
 *       and primes the Redis cache.</li>
 *   <li>{@link #resolve(String)} — looks up the original URL for a short code using a
 *       cache-aside strategy (Redis first, PostgreSQL on miss).</li>
 * </ol>
 *
 * <p><strong>Cache-aside pattern:</strong> Redis is consulted on every resolve call.
 * A cache miss triggers a database read followed by a cache write with a computed TTL.
 * URLs that have already expired are never written to the cache.
 *
 * <p>Redis keys follow the convention {@code url:{shortCode}} (e.g., {@code url:abc123}).
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class UrlService {

    /** Redis key prefix for all cached URL mappings. */
    private static final String CACHE_PREFIX = "url:";

    /**
     * Default Redis TTL applied to URLs that have no expiration timestamp.
     * Keeps frequently-used permanent links warm in cache for 24 hours.
     */
    private static final Duration DEFAULT_CACHE_TTL = Duration.ofHours(24);

    private final UrlRepository repository;
    private final Base62Encoder encoder;
    private final StringRedisTemplate redisTemplate;

    /** Base URL prepended to short codes in API responses (e.g., {@code http://localhost:8080}). */
    @Value("${app.base-url}")
    private String baseUrl;

    /**
     * Creates a new short URL mapping and returns the shortened URL details.
     *
     * <p>Two code-generation paths exist:
     * <ul>
     *   <li><strong>Custom alias:</strong> if {@code request.alias} is non-blank, it is used
     *       directly after verifying uniqueness. Throws {@link AliasAlreadyExistsException}
     *       if the alias is already taken.</li>
     *   <li><strong>Auto-generated:</strong> the record is persisted first with a placeholder
     *       short code ({@code "PENDING"}) to obtain the auto-assigned database ID, then the
     *       ID is Base62-encoded and the record is updated with the resulting short code.
     *       This requires two database writes.</li>
     * </ul>
     *
     * <p>After persistence the mapping is written to Redis with a TTL equal to the remaining
     * lifetime (if {@code expiresAt} is set) or {@link #DEFAULT_CACHE_TTL} (if not).
     *
     * @param request validated request containing {@code originalUrl}, optional {@code alias},
     *                and optional {@code expiresAt}
     * @return response containing the full short URL, short code, original URL, and timestamps
     * @throws AliasAlreadyExistsException if the requested alias is already registered
     */
    @Transactional
    public ShortenResponse shorten(ShortenRequest request) {
        String shortCode;
        UrlMapping mapping;

        if (request.getAlias() != null && !request.getAlias().isBlank()) {
            // Custom alias path: verify uniqueness before persisting
            if (repository.existsByShortCode(request.getAlias())) {
                throw new AliasAlreadyExistsException(request.getAlias());
            }
            shortCode = request.getAlias();
            mapping = UrlMapping.builder()
                    .shortCode(shortCode)
                    .originalUrl(request.getOriginalUrl())
                    .expiresAt(request.getExpiresAt())
                    .build();
            mapping = repository.save(mapping);
        } else {
            // Auto-generated path: save first to get the auto-generated ID, then encode it as the short code
            mapping = UrlMapping.builder()
                    .shortCode("PENDING")
                    .originalUrl(request.getOriginalUrl())
                    .expiresAt(request.getExpiresAt())
                    .build();
            mapping = repository.saveAndFlush(mapping);

            shortCode = encoder.encode(mapping.getId());
            mapping.setShortCode(shortCode);
            mapping = repository.save(mapping);
        }

        cacheUrl(shortCode, request.getOriginalUrl(), request.getExpiresAt());
        log.info("Created short URL: {} -> {}", shortCode, request.getOriginalUrl());

        return ShortenResponse.builder()
                .shortCode(shortCode)
                .shortUrl(baseUrl + "/" + shortCode)
                .originalUrl(mapping.getOriginalUrl())
                .createdAt(mapping.getCreatedAt())
                .expiresAt(mapping.getExpiresAt())
                .build();
    }

    /**
     * Resolves a short code to its original URL using the cache-aside pattern.
     *
     * <p>Resolution order:
     * <ol>
     *   <li>Check Redis for key {@code url:{shortCode}}. Return immediately on hit.</li>
     *   <li>Query PostgreSQL via {@link UrlRepository#findByShortCode(String)}.</li>
     *   <li>If not found, throw {@link UrlNotFoundException}.</li>
     *   <li>If found but expired ({@link UrlMapping#isExpired()}), throw {@link UrlNotFoundException}.</li>
     *   <li>Write the URL to Redis with an appropriate TTL, then return it.</li>
     * </ol>
     *
     * @param shortCode the short code to resolve (e.g., {@code "abc123"})
     * @return the original URL associated with the short code
     * @throws UrlNotFoundException if the short code is unknown or its URL has expired
     */
    @Transactional(readOnly = true)
    public String resolve(String shortCode) {
        // Step 1: Redis cache check
        String cached = redisTemplate.opsForValue().get(CACHE_PREFIX + shortCode);
        if (cached != null) {
            log.debug("Cache hit for short code: {}", shortCode);
            return cached;
        }

        // Step 2: Cache miss — fall through to database
        log.debug("Cache miss for short code: {}", shortCode);
        UrlMapping mapping = repository.findByShortCode(shortCode)
                .orElseThrow(() -> new UrlNotFoundException(shortCode));

        // Step 3: Expiry check — treat expired URLs as not found
        if (mapping.isExpired()) {
            log.info("Short code expired: {}", shortCode);
            throw new UrlNotFoundException(shortCode);
        }

        // Step 4: Re-populate cache for subsequent requests
        cacheUrl(shortCode, mapping.getOriginalUrl(), mapping.getExpiresAt());
        return mapping.getOriginalUrl();
    }

    /**
     * Writes a URL mapping to Redis with an appropriate TTL.
     *
     * <p>TTL selection:
     * <ul>
     *   <li>If {@code expiresAt} is provided, TTL = {@code expiresAt - now}.</li>
     *   <li>If the computed TTL is zero or negative (URL expired between DB read and now),
     *       the write is skipped entirely.</li>
     *   <li>If {@code expiresAt} is {@code null}, TTL defaults to {@link #DEFAULT_CACHE_TTL}
     *       (24 hours).</li>
     * </ul>
     *
     * @param shortCode   the short code used as the Redis key suffix
     * @param originalUrl the destination URL to store as the Redis value
     * @param expiresAt   the absolute expiry timestamp of the mapping, or {@code null} for permanent
     */
    private void cacheUrl(String shortCode, String originalUrl, Instant expiresAt) {
        Duration ttl;
        if (expiresAt != null) {
            ttl = Duration.between(Instant.now(), expiresAt);
            if (ttl.isNegative() || ttl.isZero()) {
                // URL is already expired; do not write a stale entry to the cache
                return;
            }
        } else {
            ttl = DEFAULT_CACHE_TTL;
        }
        redisTemplate.opsForValue().set(CACHE_PREFIX + shortCode, originalUrl, ttl);
    }
}
