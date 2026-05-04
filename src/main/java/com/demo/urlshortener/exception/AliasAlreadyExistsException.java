package com.demo.urlshortener.exception;

/**
 * Thrown when a caller requests a custom alias that is already registered.
 *
 * <p>Raised by {@link com.demo.urlshortener.service.UrlService#shorten} after
 * a positive result from {@link com.demo.urlshortener.repository.UrlRepository#existsByShortCode}.
 *
 * <p>Handled by {@link GlobalExceptionHandler#handleAliasConflict(AliasAlreadyExistsException)},
 * which maps this exception to a {@code 409 Conflict} HTTP response.
 */
public class AliasAlreadyExistsException extends RuntimeException {

    /**
     * Constructs the exception with a message identifying the conflicting alias.
     *
     * @param alias the alias value that is already in use
     */
    public AliasAlreadyExistsException(String alias) {
        super("Alias already in use: " + alias);
    }
}
