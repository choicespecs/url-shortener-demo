package com.demo.urlshortener.exception;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.time.Instant;
import java.util.HashMap;
import java.util.Map;

/**
 * Centralized exception handler for all REST controllers.
 *
 * <p>Translates application-level exceptions into structured JSON error responses,
 * preventing stack traces and internal details from reaching clients. Each handler
 * method returns a response body with at least {@code timestamp}, {@code status},
 * and {@code error} fields.
 *
 * <p>Handled exception types and their HTTP status codes:
 * <ul>
 *   <li>{@link UrlNotFoundException} → {@code 404 Not Found}</li>
 *   <li>{@link AliasAlreadyExistsException} → {@code 409 Conflict}</li>
 *   <li>{@link MethodArgumentNotValidException} → {@code 400 Bad Request}
 *       with a per-field {@code fields} map</li>
 *   <li>{@link Exception} (catch-all) → {@code 500 Internal Server Error}
 *       with a generic message (no internal detail exposed)</li>
 * </ul>
 */
@RestControllerAdvice
public class GlobalExceptionHandler {

    /**
     * Handles requests for unknown or expired short codes.
     *
     * @param ex the exception carrying the "No URL found for short code: X" message
     * @return {@code 404 Not Found} with a JSON error body
     */
    @ExceptionHandler(UrlNotFoundException.class)
    public ResponseEntity<Map<String, Object>> handleUrlNotFound(UrlNotFoundException ex) {
        return buildError(HttpStatus.NOT_FOUND, ex.getMessage());
    }

    /**
     * Handles attempts to register a custom alias that already exists.
     *
     * @param ex the exception carrying the "Alias already in use: X" message
     * @return {@code 409 Conflict} with a JSON error body
     */
    @ExceptionHandler(AliasAlreadyExistsException.class)
    public ResponseEntity<Map<String, Object>> handleAliasConflict(AliasAlreadyExistsException ex) {
        return buildError(HttpStatus.CONFLICT, ex.getMessage());
    }

    /**
     * Handles {@code @Valid} constraint violations on request body fields.
     *
     * <p>Returns a {@code 400 Bad Request} response that includes a {@code fields} map
     * where each key is a field name and each value is the violated constraint message
     * (e.g., {@code {"originalUrl": "Must be a valid URL"}}).
     *
     * @param ex the validation exception produced by Spring MVC's argument resolution
     * @return {@code 400 Bad Request} with a JSON error body including per-field details
     */
    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<Map<String, Object>> handleValidation(MethodArgumentNotValidException ex) {
        Map<String, String> fieldErrors = new HashMap<>();
        for (FieldError error : ex.getBindingResult().getFieldErrors()) {
            fieldErrors.put(error.getField(), error.getDefaultMessage());
        }

        Map<String, Object> body = new HashMap<>();
        body.put("timestamp", Instant.now());
        body.put("status", HttpStatus.BAD_REQUEST.value());
        body.put("error", "Validation failed");
        body.put("fields", fieldErrors);
        return ResponseEntity.badRequest().body(body);
    }

    /**
     * Catch-all handler for any unhandled exception.
     *
     * <p>Returns a generic error message without internal detail to avoid leaking
     * stack traces or sensitive system information to clients. The exception is
     * logged at a higher level by Spring's dispatcher servlet.
     *
     * @param ex the unhandled exception (details intentionally not forwarded to the client)
     * @return {@code 500 Internal Server Error} with a generic JSON error body
     */
    @ExceptionHandler(Exception.class)
    public ResponseEntity<Map<String, Object>> handleGeneric(Exception ex) {
        return buildError(HttpStatus.INTERNAL_SERVER_ERROR, "An unexpected error occurred");
    }

    /**
     * Builds a standard error response body with {@code timestamp}, {@code status},
     * and {@code error} fields.
     *
     * @param status  the HTTP status to return
     * @param message the human-readable error description
     * @return a {@link ResponseEntity} with the given status and a JSON body
     */
    private ResponseEntity<Map<String, Object>> buildError(HttpStatus status, String message) {
        Map<String, Object> body = new HashMap<>();
        body.put("timestamp", Instant.now());
        body.put("status", status.value());
        body.put("error", message);
        return ResponseEntity.status(status).body(body);
    }
}
