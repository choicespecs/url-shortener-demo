package com.demo.urlshortener.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.Data;
import org.hibernate.validator.constraints.URL;

import java.time.Instant;

/**
 * Inbound request payload for the {@code POST /shorten} endpoint.
 *
 * <p>All validation is enforced by Bean Validation (Jakarta Validation) annotations.
 * Constraint violations are caught by
 * {@link com.demo.urlshortener.exception.GlobalExceptionHandler} and returned as
 * a {@code 400 Bad Request} response with per-field error messages.
 *
 * <p>Example JSON request body:
 * <pre>{@code
 * {
 *   "originalUrl": "https://example.com/some/very/long/path?query=value",
 *   "alias": "my-link",
 *   "expiresAt": "2026-12-31T23:59:59Z"
 * }
 * }</pre>
 */
@Data
public class ShortenRequest {

    /**
     * The long URL to be shortened. Must be non-blank and syntactically valid
     * according to Hibernate Validator's {@code @URL} constraint.
     *
     * <p><strong>Security note:</strong> {@code @URL} does not restrict the URL scheme.
     * Schemes such as {@code javascript:} and {@code data:} currently pass validation.
     * A scheme allowlist should be enforced at the service layer before production use.
     */
    @NotBlank(message = "URL must not be blank")
    @URL(message = "Must be a valid URL")
    private String originalUrl;

    /**
     * Optional custom alias to use as the short code instead of an auto-generated one.
     * When provided:
     * <ul>
     *   <li>Length must be between 1 and 20 characters.</li>
     *   <li>Only alphanumeric characters, hyphens ({@code -}), and underscores ({@code _})
     *       are permitted — characters that are safe in URL path segments.</li>
     * </ul>
     * When {@code null} or blank, the service auto-generates a short code by
     * Base62-encoding the new record's database ID.
     */
    @Size(min = 1, max = 20, message = "Alias must be between 1 and 20 characters")
    @Pattern(regexp = "^[a-zA-Z0-9_-]*$", message = "Alias may only contain letters, numbers, hyphens, and underscores")
    private String alias;

    /**
     * Optional absolute expiration timestamp. When set, the short URL becomes
     * inaccessible after this instant and will be removed from PostgreSQL by the
     * nightly {@link com.demo.urlshortener.scheduler.ExpirationCleanupJob}.
     * When {@code null}, the URL never expires.
     *
     * <p>Encoded as an ISO-8601 UTC timestamp in JSON (e.g., {@code "2026-12-31T23:59:59Z"}).
     */
    private Instant expiresAt;
}
