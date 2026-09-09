package com.smartoffice.breakfast.exception;

import com.smartoffice.breakfast.dto.ErrorResponse;
import jakarta.persistence.EntityNotFoundException;
import jakarta.validation.ConstraintViolationException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.core.AuthenticationException;
import org.springframework.validation.FieldError;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.servlet.NoHandlerFoundException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.Map;

/**
 * Centralised error translation.
 *
 * Two hard rules enforced here:
 *  1. Every exception Spring MVC can throw as part of *request validation*
 *     (bad JSON, wrong param type, missing param, constraint violation on a
 *     path/query param, unsupported method, unknown route) gets its own
 *     handler mapped to the correct 4xx status. Without explicit handlers
 *     these fell through to {@link #handleGeneric}, which — before this
 *     review — turned ordinary client mistakes into misleading 500s.
 *  2. Nothing derived from a raw {@link Exception#getMessage()} (stack traces,
 *     SQL text, internal class names) is ever put in the HTTP response body.
 *     It is logged server-side instead, and the client gets a fixed, safe
 *     message. This fixes the information-disclosure gap in the previous
 *     {@code handleGeneric}, which echoed {@code ex.getMessage()} straight
 *     back to the caller.
 */
@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    @ExceptionHandler(ResourceNotFoundException.class)
    public ResponseEntity<ErrorResponse> handleNotFound(ResourceNotFoundException ex) {
        return build(HttpStatus.NOT_FOUND, ex.getMessage(), null);
    }

    @ExceptionHandler(EntityNotFoundException.class)
    public ResponseEntity<ErrorResponse> handleEntityNotFound(EntityNotFoundException ex) {
        return build(HttpStatus.NOT_FOUND, ex.getMessage(), null);
    }

    /** Unknown URL (e.g. a typo'd path, or a client hitting a removed endpoint). */
    @ExceptionHandler(NoHandlerFoundException.class)
    public ResponseEntity<ErrorResponse> handleNoHandler(NoHandlerFoundException ex) {
        return build(HttpStatus.NOT_FOUND, "No such endpoint: " + ex.getHttpMethod() + " " + ex.getRequestURL(), null);
    }

    /** Spring 6.1+'s dedicated "no static resource / no route" exception, e.g. an unknown /api/... path. */
    @ExceptionHandler(NoResourceFoundException.class)
    public ResponseEntity<ErrorResponse> handleNoResource(NoResourceFoundException ex) {
        return build(HttpStatus.NOT_FOUND, "No such endpoint or resource: " + ex.getResourcePath(), null);
    }

    @ExceptionHandler(BadRequestException.class)
    public ResponseEntity<ErrorResponse> handleBadRequest(BadRequestException ex) {
        return build(HttpStatus.BAD_REQUEST, ex.getMessage(), null);
    }

    @ExceptionHandler(RoomClosedException.class)
    public ResponseEntity<ErrorResponse> handleRoomClosed(RoomClosedException ex) {
        return build(HttpStatus.CONFLICT, ex.getMessage(), null);
    }

    @ExceptionHandler(DuplicateResourceException.class)
    public ResponseEntity<ErrorResponse> handleDuplicate(DuplicateResourceException ex) {
        return build(HttpStatus.CONFLICT, ex.getMessage(), null);
    }

    /**
     * A unique-constraint or FK violation that slipped past application-level
     * checks (e.g. a race between two concurrent requests). Never echoes the
     * raw SQL/constraint name back to the client.
     */
    @ExceptionHandler(DataIntegrityViolationException.class)
    public ResponseEntity<ErrorResponse> handleDataIntegrity(DataIntegrityViolationException ex) {
        log.warn("Data integrity violation: {}", ex.getMessage());
        return build(HttpStatus.CONFLICT, "This action conflicts with existing data (e.g. a duplicate name).", null);
    }

    @ExceptionHandler(BadCredentialsException.class)
    public ResponseEntity<ErrorResponse> handleBadCredentials(BadCredentialsException ex) {
        return build(HttpStatus.UNAUTHORIZED, "Invalid phone or password", null);
    }

    @ExceptionHandler(AccessDeniedException.class)
    public ResponseEntity<ErrorResponse> handleAccessDenied(AccessDeniedException ex) {
        return build(HttpStatus.FORBIDDEN, "You do not have permission to perform this action", null);
    }

    /**
     * Safety net: this normally never fires, because JwtAuthFilter never lets an
     * AuthenticationException escape into the DispatcherServlet — unauthenticated
     * requests are caught by the AuthenticationEntryPoint configured in
     * SecurityConfig instead (see the "why 401 not 403" note there). This handler
     * only guards against some future @Controller/@Service code path that throws
     * one directly (e.g. a manual AuthenticationManager.authenticate() call), so
     * it fails safe as 401 rather than falling through to the generic 500 handler.
     */
    @ExceptionHandler(AuthenticationException.class)
    public ResponseEntity<ErrorResponse> handleAuthentication(AuthenticationException ex) {
        return build(HttpStatus.UNAUTHORIZED, "Authentication required or your session has expired. Please log in again.", null);
    }

    /** @Valid failures on a @RequestBody DTO — the main validation path. */
    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ErrorResponse> handleValidation(MethodArgumentNotValidException ex) {
        Map<String, String> fieldErrors = new HashMap<>();
        for (FieldError fe : ex.getBindingResult().getFieldErrors()) {
            fieldErrors.put(fe.getField(), fe.getDefaultMessage());
        }
        return build(HttpStatus.BAD_REQUEST, "Validation failed", fieldErrors);
    }

    /**
     * Constraint annotations (e.g. {@code @Positive}) applied directly to a
     * {@code @RequestParam}/{@code @PathVariable} on a {@code @Validated}
     * controller. Distinct code path from {@link MethodArgumentNotValidException},
     * which only covers {@code @Valid @RequestBody}.
     */
    @ExceptionHandler(ConstraintViolationException.class)
    public ResponseEntity<ErrorResponse> handleConstraintViolation(ConstraintViolationException ex) {
        Map<String, String> fieldErrors = new HashMap<>();
        ex.getConstraintViolations().forEach(cv -> {
            String path = cv.getPropertyPath() == null ? "value" : cv.getPropertyPath().toString();
            fieldErrors.put(path, cv.getMessage());
        });
        return build(HttpStatus.BAD_REQUEST, "Validation failed", fieldErrors);
    }

    /** A required @RequestParam was omitted entirely (e.g. ?totalDelivery= missing). */
    @ExceptionHandler(MissingServletRequestParameterException.class)
    public ResponseEntity<ErrorResponse> handleMissingParam(MissingServletRequestParameterException ex) {
        return build(HttpStatus.BAD_REQUEST, "Missing required parameter: " + ex.getParameterName(), null);
    }

    /** A path variable or query param couldn't be converted to its target type (e.g. roomId=abc). */
    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    public ResponseEntity<ErrorResponse> handleTypeMismatch(MethodArgumentTypeMismatchException ex) {
        String expected = ex.getRequiredType() != null ? ex.getRequiredType().getSimpleName() : "the expected type";
        return build(HttpStatus.BAD_REQUEST, "Invalid value for '" + ex.getName() + "': expected " + expected, null);
    }

    /** Malformed/unparsable JSON body, wrong content type, or an empty body where one was required. */
    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<ErrorResponse> handleUnreadableBody(HttpMessageNotReadableException ex) {
        return build(HttpStatus.BAD_REQUEST, "Request body is missing or malformed JSON", null);
    }

    /** e.g. a GET-only client sending POST, or vice versa. */
    @ExceptionHandler(HttpRequestMethodNotSupportedException.class)
    public ResponseEntity<ErrorResponse> handleMethodNotSupported(HttpRequestMethodNotSupportedException ex) {
        return build(HttpStatus.METHOD_NOT_ALLOWED, "HTTP method not supported for this endpoint: " + ex.getMethod(), null);
    }

    /**
     * Last-resort fallback for anything unanticipated. Deliberately does not
     * expose {@code ex.getMessage()} to the caller — that can contain SQL,
     * file paths, or other internals. Full detail goes to the server log only.
     */
    @ExceptionHandler(Exception.class)
    public ResponseEntity<ErrorResponse> handleGeneric(Exception ex) {
        log.error("Unhandled exception", ex);
        return build(HttpStatus.INTERNAL_SERVER_ERROR, "An unexpected error occurred. Please try again later.", null);
    }

    private ResponseEntity<ErrorResponse> build(HttpStatus status, String message, Map<String, String> fieldErrors) {
        ErrorResponse body = ErrorResponse.builder()
                .timestamp(LocalDateTime.now())
                .status(status.value())
                .error(status.getReasonPhrase())
                .message(message)
                .fieldErrors(fieldErrors)
                .build();
        return ResponseEntity.status(status).body(body);
    }
}