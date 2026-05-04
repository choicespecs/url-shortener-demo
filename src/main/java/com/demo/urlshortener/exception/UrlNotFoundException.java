package com.demo.urlshortener.exception;

/**
 * Thrown when a short code cannot be resolved to a valid URL.
 *
 * <p>This exception is raised in two scenarios:
 * <ol>
 *   <li>The short code does not exist in the database.</li>
 *   <li>The short code exists but its associated URL has passed the
 *       {@code expiresAt} timestamp.</li>
 * </ol>
 *
 * <p>Both cases are reported identically to the client ({@code 404 Not Found})
 * to avoid leaking information about whether a code ever existed.
 *
 * <p>Handled by {@link GlobalExceptionHandler#handleUrlNotFound(UrlNotFoundException)}.
 */
public class UrlNotFoundException extends RuntimeException {

    /**
     * Constructs the exception with a message identifying the missing short code.
     *
     * @param shortCode the short code that could not be resolved
     */
    public UrlNotFoundException(String shortCode) {
        super("No URL found for short code: " + shortCode);
    }
}
