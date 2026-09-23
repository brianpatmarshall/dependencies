package com.boeing.depgraph.backend.api;

import com.boeing.depgraph.scanner.NotAMavenProjectException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.Map;

/**
 * Errors as a readable body with the right status.
 *
 * <p>The frontend shows {@code message} directly, so these are written to be read by
 * somebody who is not looking at the logs.
 */
@RestControllerAdvice
public class ApiExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(ApiExceptionHandler.class);

    @ExceptionHandler(NotAMavenProjectException.class)
    public ResponseEntity<Map<String, Object>> notMaven(NotAMavenProjectException e) {
        return body(HttpStatus.UNPROCESSABLE_ENTITY, e.getMessage());
    }

    /** Bad Cypher, an unknown query id or a path outside the allowed roots. */
    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<Map<String, Object>> badRequest(IllegalArgumentException e) {
        return body(HttpStatus.BAD_REQUEST, e.getMessage());
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<Map<String, Object>> failed(Exception e) {
        log.error("Unhandled failure", e);
        return body(HttpStatus.INTERNAL_SERVER_ERROR,
            e.getMessage() == null ? e.toString() : e.getMessage());
    }

    private static ResponseEntity<Map<String, Object>> body(HttpStatus status, String message) {
        return ResponseEntity.status(status).body(Map.of(
            "status", status.value(),
            "message", message == null ? status.getReasonPhrase() : message));
    }
}
