package com.demo.urlshortener.dto;

import lombok.Builder;
import lombok.Data;

import java.time.Instant;

/**
 * Outbound response payload returned by the {@code POST /shorten} endpoint.
 *
 * <p>Contains everything the caller needs to use and share the shortened URL,
 * including the full clickable short URL, the raw short code, the original
 * destination, and the creation and expiration timestamps.
 *
 * <p>Example JSON response body ({@code 201 Created}):
 * <pre>{@code
 * {
 *   "shortUrl":    "http://localhost:8080/abc123",
 *   "shortCode":   "abc123",
 *   "originalUrl": "https://example.com/some/very/long/path",
 *   "createdAt":   "2026-05-04T12:00:00Z",
 *   "expiresAt":   "2026-12-31T23:59:59Z"
 * }
 * }</pre>
 */
@Data
@Builder
public class ShortenResponse {

    /**
     * The fully-qualified short URL that redirects to {@link #originalUrl}.
     * Formed as {@code app.base-url + "/" + shortCode}
     * (e.g., {@code "http://localhost:8080/abc123"}).
     */
    private String shortUrl;

    /**
     * The short code segment only (e.g., {@code "abc123"} or a custom alias).
     * Useful when the caller needs to construct URLs with a different base.
     */
    private String shortCode;

    /** The original long URL that this mapping points to. */
    private String originalUrl;

    /** The UTC instant when this mapping was created. */
    private Instant createdAt;

    /**
     * The UTC instant when this mapping expires, or {@code null} if the URL
     * never expires. After this instant the short code returns {@code 404}.
     */
    private Instant expiresAt;
}
