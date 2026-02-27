package org.viators.orderprocessingsystem.exceptions.handler;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.ConstraintViolationException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.HttpMediaTypeNotSupportedException;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.servlet.resource.NoResourceFoundException;
import org.viators.orderprocessingsystem.exceptions.*;
import org.viators.orderprocessingsystem.exceptions.dto.ErrorResponse;
import org.viators.orderprocessingsystem.exceptions.dto.FieldError;
import org.viators.orderprocessingsystem.exceptions.dto.ValidationErrorResponse;

import java.util.List;

@RestControllerAdvice
@Order(Ordered.HIGHEST_PRECEDENCE)
@Slf4j
public class GlobalExceptionHandler {

    @ExceptionHandler(ResourceNotFoundException.class)
    public ResponseEntity<ErrorResponse> handleResourceNotFound(ResourceNotFoundException ex, HttpServletRequest request) {
        log.debug("Resource not found: {} - Path: {}", ex.getMessage(), request.getRequestURI());
        return buildErrorResponse(HttpStatus.NOT_FOUND, ex, request);
    }

    @ExceptionHandler(DuplicateResourceException.class)
    public ResponseEntity<ErrorResponse> handleDuplicateResource(DuplicateResourceException ex, HttpServletRequest request) {
        log.warn("Duplicate resource attempt: {} - Path: {}", ex.getMessage(), request.getRequestURI());
        return buildErrorResponse(HttpStatus.CONFLICT, ex, request);
    }

    @ExceptionHandler(BusinessValidationException.class)
    public ResponseEntity<ErrorResponse> handleBusinessValidation(BusinessValidationException ex, HttpServletRequest request) {
        log.debug("Business validation failed: {} -Path: {}", ex.getMessage(), request.getRequestURI());
        return buildErrorResponse(HttpStatus.BAD_REQUEST, ex, request);
    }

    @ExceptionHandler(InvalidCredentialsException.class)
    public ResponseEntity<ErrorResponse> handleInvalidCredentials(InvalidCredentialsException ex, HttpServletRequest request) {

        log.warn("Invalid credentials attempt - Path: {}", request.getRequestURI());
        return buildErrorResponse(HttpStatus.UNAUTHORIZED, ex, request);
    }

    @ExceptionHandler(AccessDeniedException.class)
    public ResponseEntity<ErrorResponse> handleAccessDenied(AccessDeniedException ex, HttpServletRequest request) {

        log.warn("Access denied: {} - Path: {}", ex.getMessage(), request.getRequestURI());
        return buildErrorResponse(HttpStatus.FORBIDDEN, ex, request);
    }

    @ExceptionHandler(InvalidStateException.class)
    public ResponseEntity<ErrorResponse> handleInvalidState(InvalidStateException ex, HttpServletRequest request) {

        log.debug("Invalid state {} - Path: {}", ex.getMessage(), request.getRequestURI());
        return buildErrorResponse(HttpStatus.CONFLICT, ex, request);
    }

    // Bean Validation Exception

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ValidationErrorResponse> handleMethodArgumentNotValid(MethodArgumentNotValidException ex, HttpServletRequest request) {

        log.debug("Validation failed with {} errors - Path: {}", ex.getErrorCount(), request.getRequestURI());

        List<FieldError> fieldErrors = ex.getBindingResult()
                .getFieldErrors()
                .stream()
                .map(error -> new FieldError(
                                error.getField(),
                                error.getDefaultMessage() != null ? error.getDefaultMessage() : "Invalid value",
                                error.getRejectedValue()
                        )
                )
                .toList();

        ValidationErrorResponse response = ValidationErrorResponse.of(fieldErrors, request.getRequestURI());
        return ResponseEntity.badRequest().body(response);
    }

    @ExceptionHandler(ConstraintViolationException.class)
    public ResponseEntity<ValidationErrorResponse> handleConstraintViolation(ConstraintViolationException ex, HttpServletRequest request) {
        log.debug("Constraint violation - Path: {}", request.getRequestURI());

        List<FieldError> fieldErrors = ex.getConstraintViolations()
                .stream()
                .map(violation -> {
                    String field = extractFieldValue(violation.getPropertyPath().toString());
                    return new FieldError(field, violation.getMessage(), null);
                })
                .toList();

        ValidationErrorResponse response = ValidationErrorResponse.of(fieldErrors, request.getRequestURI());
        return ResponseEntity.badRequest().body(response);
    }


    // ═══════════════════════════════════════════════════════════════════════════
    // SECTION 3: SECURITY EXCEPTIONS (Spring Security)
    // ══=========================================================================

    /**
     * Handles Spring Security AccessDeniedException.
     * <p>
     * When: @PreAuthorize fails, insufficient role
     * Status: 403 Forbidden
     */
    @ExceptionHandler(org.springframework.security.access.AccessDeniedException.class)
    public ResponseEntity<ErrorResponse> handleSpringSecurityAccessDenied(org.springframework.security.access.AccessDeniedException ex,
                                                                          HttpServletRequest request) {

        log.warn("Spring Security access denied - Path: {}", request.getRequestURI());
        ErrorResponse error = ErrorResponse.of(
                HttpStatus.FORBIDDEN.value(),
                ErrorCodeEnum.ACCESS_DENIED,
                "You do not have permission to access this resource",
                request.getRequestURI()
        );

        return ResponseEntity.status(HttpStatus.FORBIDDEN).body(error);
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // SECTION 4: DATA ACCESS EXCEPTIONS (JPA/Hibernate)
    // ═══════════════════════════════════════════════════════════════════════════

    /**
     * Handles database constraint violations.
     * <p>
     * When: Unique constraint, foreign key violation
     * Status: 409 Conflict
     * <p>
     * IMPORTANT: Never expose SQL details to client
     */
    @ExceptionHandler(DataIntegrityViolationException.class)
    public ResponseEntity<ErrorResponse> handleDataIntegrityViolation(DataIntegrityViolationException ex, HttpServletRequest request) {

        log.error("Data integrity violation - Path: {} - Cause: {}", request.getRequestURI(), ex.getMostSpecificCause().getMessage());
        ErrorResponse error = ErrorResponse.of(
                HttpStatus.CONFLICT.value(),
                ErrorCodeEnum.DATA_INTEGRITY_VIOLATION,
                ex.getMostSpecificCause().getMessage(),
                request.getRequestURI()
        );

        return ResponseEntity.status(HttpStatus.CONFLICT).body(error);
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // SECTION 5: HTTP/WEB EXCEPTIONS (Spring MVC)
    // ═══════════════════════════════════════════════════════════════════════════

    /**
     * Handles malformed JSON in request body.
     * <p>
     * When: Invalid JSON syntax, missing required fields
     * Status: 400 Bad Request
     */
    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<ErrorResponse> handleHttpMessageNotReadable(HttpMessageNotReadableException ex, HttpServletRequest request) {

        log.debug("Malformed request body - Path: {}", request.getRequestURI());
        ErrorResponse error = ErrorResponse.of(
                HttpStatus.BAD_REQUEST.value(),
                ErrorCodeEnum.MALFORMED_REQUEST,
                ex.getMessage(),
                request.getRequestURI()
        );

        return ResponseEntity.badRequest().body(error);
    }

    @ExceptionHandler(HttpRequestMethodNotSupportedException.class)
    public ResponseEntity<ErrorResponse> handleHttpRequestMethodNotSupported(HttpRequestMethodNotSupportedException ex, HttpServletRequest request) {

        log.debug("Method {} not supported for {} - Supported: {}",
                ex.getMethod(),
                request.getRequestURI(),
                ex.getSupportedHttpMethods());

        ErrorResponse error = ErrorResponse.of(
                HttpStatus.METHOD_NOT_ALLOWED.value(),
                ErrorCodeEnum.METHOD_NOT_ALLOWED,
                ex.getMessage(),
                request.getRequestURI()
        );

        return ResponseEntity.status(HttpStatus.METHOD_NOT_ALLOWED).body(error);
    }

    @ExceptionHandler(HttpMediaTypeNotSupportedException.class)
    public ResponseEntity<ErrorResponse> handleHttpMediaTypeNotSupported(HttpMediaTypeNotSupportedException ex, HttpServletRequest request) {

        log.debug("Media type {} not supported - Path: {}",
                ex.getContentType(),
                request.getRequestURI());

        ErrorResponse error = ErrorResponse.of(
                HttpStatus.UNSUPPORTED_MEDIA_TYPE.value(),
                ErrorCodeEnum.UNSUPPORTED_MEDIA_TYPE,
                ex.getMessage(),
                request.getRequestURI()
        );

        return ResponseEntity.status(HttpStatus.UNSUPPORTED_MEDIA_TYPE).body(error);
    }

    @ExceptionHandler(MissingServletRequestParameterException.class)
    public ResponseEntity<ErrorResponse> handleMissingServletRequestParameter(MissingServletRequestParameterException ex, HttpServletRequest request) {

        log.debug("Missing parameter '{}' - Path: {}", ex.getParameterName(), request.getRequestURI());

        ErrorResponse error = ErrorResponse.of(
                HttpStatus.BAD_REQUEST.value(),
                ErrorCodeEnum.MISSING_REQUIRED_QUERY_PARAMETER,
                ex.getMessage(),
                request.getRequestURI()
        );

        return ResponseEntity.badRequest().body(error);
    }

    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    public ResponseEntity<ErrorResponse> handleMethodArgumentTypeMismatchException(MethodArgumentTypeMismatchException ex, HttpServletRequest request) {

        log.debug("Type mismatch for '{}' - Path: {}", ex.getName(), request.getRequestURI());

        String expectedType = ex.getRequiredType() != null
                ? ex.getRequiredType().getSimpleName()
                : "unknown";

        ErrorResponse error = ErrorResponse.of(
                HttpStatus.BAD_REQUEST.value(),
                ErrorCodeEnum.TYPE_MISMATCH,
                String.format("Parameter %s should be of type %s", ex.getName(), expectedType),
                request.getRequestURI()
        );

        return ResponseEntity.badRequest().body(error);
    }

    @ExceptionHandler(NoResourceFoundException.class)
    public ResponseEntity<ErrorResponse> handleNoResourceFoundException(NoResourceFoundException ex, HttpServletRequest request) {

        log.debug("No resource found - Path: {}", request.getRequestURI());

        ErrorResponse error = ErrorResponse.of(
                HttpStatus.NOT_FOUND.value(),
                ErrorCodeEnum.ENDPOINT_NOT_FOUND,
                "The requested endpoint does not exist",
                request.getRequestURI()
        );

        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(error);

    }

    // ═══════════════════════════════════════════════════════════════════════════
    // SECTION 6: CATCH-ALL (Unexpected Errors)
    // ═══════════════════════════════════════════════════════════════════════════

    /**
     * Catch-all handler for unexpected exceptions.
     * <p>
     * This is a safety net. If an exception reaches here:
     * 1. It's a bug or unhandled edge case
     * 2. Log EVERYTHING for debugging
     * 3. Return NOTHING specific to client (security)
     * <p>
     * Status: 500 Internal Server Error
     * Log Level: ERROR with full stack trace
     */
    @ExceptionHandler(Exception.class)
    public ResponseEntity<ErrorResponse> handleAllUncaughtExceptions(Exception ex, HttpServletRequest request) {

        log.error("Unexpected error at {} - Exception: {}",
                request.getRequestURI(),
                ex.getClass().getName(),
                ex); // Logs full stack trace

        ErrorResponse error = ErrorResponse.of(
                HttpStatus.INTERNAL_SERVER_ERROR.value(),
                ErrorCodeEnum.INTERNAL_SERVER_ERROR,
                "An unexpected error occurred. Please try again later.",
                request.getRequestURI()
        );

        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(error);
    }

    private ResponseEntity<ErrorResponse> buildErrorResponse(HttpStatus status, BaseException ex, HttpServletRequest request) {
        ErrorResponse error = ErrorResponse.of(
                status.value(),
                ex.getErrorCode(),
                ex.getMessage(),
                request.getRequestURI()
        );

        return ResponseEntity.status(status).body(error);
    }

    private String extractFieldValue(String propertyPath) {
        if (propertyPath == null || propertyPath.isEmpty()) {
            return "unknown";
        }

        int lastDot = propertyPath.lastIndexOf('.');
        return lastDot >= 0 ? propertyPath.substring(lastDot + 1) : propertyPath;
    }
}
