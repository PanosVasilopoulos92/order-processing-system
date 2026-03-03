# Exception Handling & Custom Exceptions Guide

## For the Order Processing System — Spring Boot 4 / Java 25

---

## Table of Contents

1. [Understanding the Big Picture](#1-understanding-the-big-picture)
2. [How Exception Handling Works in Spring (The Flow)](#2-how-exception-handling-works-in-spring-the-flow)
3. [The Two Exception Zones](#3-the-two-exception-zones)
4. [Implementation: Layer by Layer](#4-implementation-layer-by-layer)
   - 4.1 [ErrorCodeEnum — The Error Vocabulary](#41-errorcodeenum--the-error-vocabulary)
   - 4.2 [ErrorResponse — The Client Contract](#42-errorresponse--the-client-contract)
   - 4.3 [ValidationErrorResponse & FieldError — Validation-Specific Responses](#43-validationerrorresponse--fielderror--validation-specific-responses)
   - 4.4 [BaseException — The Custom Exception Foundation](#44-baseexception--the-custom-exception-foundation)
   - 4.5 [ResourceNotFoundException — 404 Not Found](#45-resourcenotfoundexception--404-not-found)
   - 4.6 [DuplicateResourceException — 409 Conflict](#46-duplicateresourceexception--409-conflict)
   - 4.7 [BusinessValidationException — 400 Bad Request](#47-businessvalidationexception--400-bad-request)
   - 4.8 [InvalidCredentialsException — 401 Unauthorized](#48-invalidcredentialsexception--401-unauthorized)
   - 4.9 [AccessDeniedException — 403 Forbidden](#49-accessdeniedexception--403-forbidden)
   - 4.10 [InvalidStateException — 409 Conflict](#410-invalidstateexception--409-conflict)
   - 4.11 [GlobalExceptionHandler — The Central Brain](#411-globalexceptionhandler--the-central-brain)
   - 4.12 [SecurityExceptionHandler — The Filter-Level Handler](#412-securityexceptionhandler--the-filter-level-handler)
5. [How Everything Connects: Exception Lifecycle](#5-how-everything-connects-exception-lifecycle)
6. [When to Throw What — A Decision Guide](#6-when-to-throw-what--a-decision-guide)
7. [Exception Handling Best Practices Checklist](#7-exception-handling-best-practices-checklist)
8. [Common Mistakes to Avoid](#8-common-mistakes-to-avoid)

---

## 1. Understanding the Big Picture

Before looking at any code, you need to understand **what problem we're solving** and **why Spring's default error handling isn't enough for REST APIs**.

### The Problem: REST APIs Need Consistent, Informative Error Responses

When something goes wrong in a Spring Boot application, Spring's default behavior is to return a `BasicErrorController` response — a JSON body called the "Whitelabel" error response:

```json
{
  "timestamp": "2026-03-03T10:00:00.000+00:00",
  "status": 500,
  "error": "Internal Server Error",
  "path": "/api/v1/products/abc123"
}
```

This is problematic for several reasons:

- **No error codes** — your Angular frontend can't programmatically distinguish between "product not found" and "database is down." It just sees a status code and a generic message.
- **Inconsistent format** — validation errors look different from business logic errors, which look different from 404s. The frontend needs separate parsing logic for each shape.
- **Information leakage** — sometimes Spring includes stack traces, SQL statements, or internal class names. An attacker can use these to probe for vulnerabilities.
- **No actionable detail** — "Internal Server Error" doesn't help the developer calling your API understand what they did wrong or how to fix it.

### The Solution: A Unified Exception Handling Architecture

We build a layered exception handling system where:

1. **Every error produces the same JSON structure** — the `ErrorResponse` record (or `ValidationErrorResponse` for field-level validation errors).
2. **Every error carries a machine-readable error code** — the `ErrorCodeEnum`, so the frontend can react programmatically (show a specific toast, redirect to login, etc.).
3. **Custom exceptions are domain-specific** — `ResourceNotFoundException`, `DuplicateResourceException`, etc. These read like business language, not technical jargon.
4. **One central handler catches everything** — the `GlobalExceptionHandler` (`@RestControllerAdvice`) maps each exception type to the correct HTTP status code and response body.
5. **Security errors get their own handler** — the `SecurityExceptionHandler` catches 401/403 errors that happen *before* the request reaches any controller.

Think of it like a hospital triage system: every patient (exception) walks in through the same door (the handler), gets classified (error code), and receives the same formatted report (error response) — regardless of whether they have a broken leg (404) or a fever (500).

### Where Spring's Exception Mechanism Fits

Spring provides the machinery; we provide the strategy. Here's what Spring gives us:

- **`@RestControllerAdvice`** — a special `@Component` that intercepts exceptions thrown from any `@Controller` or `@RestController`. It's the single place where you define "when exception X happens, return response Y."
- **`@ExceptionHandler`** — methods inside the advice class, each annotated to catch a specific exception type. Spring matches the thrown exception to the most specific handler available.
- **`AuthenticationEntryPoint` / `AccessDeniedHandler`** — interfaces that Spring Security calls when authentication or authorization fails at the filter level (before the controller is ever reached).

---

## 2. How Exception Handling Works in Spring (The Flow)

### Controller-Level Exception Flow (The Common Case)
```
Client                              Server
  |                                    |
  |  GET /api/v1/products/abc123       |
  |  Authorization: Bearer <JWT>       |
  |----------------------------------->|
  |                              [JwtAuthenticationFilter]
  |                                    |  ✅ Token valid
  |                              [AuthorizationFilter]
  |                                    |  ✅ User authenticated
  |                              [DispatcherServlet]
  |                                    |  Routes to ProductController
  |                              [ProductController]
  |                                    |  Calls productService.findByUuid("abc123")
  |                              [ProductService]
  |                                    |  Queries DB → not found
  |                                    |  throws ResourceNotFoundException
  |                                    |
  |                              [GlobalExceptionHandler]
  |                                    |  @ExceptionHandler catches it
  |                                    |  Maps to 404 + ErrorResponse
  |                                    |
  |  404 Not Found                     |
  |  {                                 |
  |    "status": 404,                  |
  |    "errorCode": "RESOURCE_NOT_FOUND",
  |    "message": "Product not found   |
  |     with uuid: abc123 or is inactive",
  |    "path": "/api/v1/products/abc123",
  |    "timestamp": "2026-03-03T..."   |
  |  }                                 |
  |<-----------------------------------|
```

### Validation Exception Flow
```
Client                              Server
  |                                    |
  |  POST /api/v1/products             |
  |  { "name": "", "price": -5 }      |
  |----------------------------------->|
  |                              [DispatcherServlet]
  |                              [ProductController]
  |                                    |  @Valid on @RequestBody
  |                                    |  Spring's Validator runs
  |                                    |  2 fields fail → throws
  |                                    |  MethodArgumentNotValidException
  |                              [GlobalExceptionHandler]
  |                                    |  Maps to 400 + ValidationErrorResponse
  |                                    |
  |  400 Bad Request                   |
  |  {                                 |
  |    "status": 400,                  |
  |    "errorCode": "VALIDATION_FAILED",
  |    "message": "Validation failed   |
  |     for 2 field(s)",               |
  |    "path": "/api/v1/products",     |
  |    "timestamp": "...",             |
  |    "fieldErrors": [                |
  |      {                             |
  |        "field": "name",            |
  |        "message": "must not be blank",
  |        "rejectedValue": ""         |
  |      },                            |
  |      {                             |
  |        "field": "price",           |
  |        "message": "must be > 0",   |
  |        "rejectedValue": -5         |
  |      }                             |
  |    ]                               |
  |  }                                 |
  |<-----------------------------------|
```

### Security Exception Flow (Filter Level)
```
Client                              Server
  |                                    |
  |  GET /api/v1/products              |
  |  (no Authorization header)         |
  |----------------------------------->|
  |                              [JwtAuthenticationFilter]
  |                                    |  No token → skip, pass along
  |                              [AuthorizationFilter]
  |                                    |  Endpoint requires authentication
  |                                    |  No authentication in SecurityContext
  |                                    |  Triggers AuthenticationEntryPoint
  |                              [SecurityExceptionHandler]
  |                                    |  commence() method
  |                                    |  Writes 401 directly to response
  |                                    |
  |  401 Unauthorized                  |
  |  {                                 |
  |    "status": 401,                  |
  |    "errorCode": "INVALID_CREDENTIALS",
  |    "message": "Authentication is   |
  |     required to access this resource",
  |    "path": "/api/v1/products",     |
  |    "timestamp": "..."              |
  |  }                                 |
  |<-----------------------------------|
```

---

## 3. The Two Exception Zones

This is a crucial concept that many developers miss: **exceptions in Spring Boot happen in two fundamentally different zones**, and each zone requires a different handling mechanism.

### Zone 1: Controller Zone (Inside the DispatcherServlet)

This is the "normal" zone. Exceptions thrown from controllers, services, or repositories are caught by the `DispatcherServlet`, which then looks for a matching `@ExceptionHandler` method in your `@RestControllerAdvice`.

**What's caught here:**
- Your custom exceptions (`ResourceNotFoundException`, `DuplicateResourceException`, etc.)
- Bean Validation exceptions (`MethodArgumentNotValidException`, `ConstraintViolationException`)
- Spring MVC exceptions (`HttpMessageNotReadableException`, `HttpRequestMethodNotSupportedException`)
- JPA/Hibernate exceptions (`DataIntegrityViolationException`)
- Spring Security's `AccessDeniedException` (when thrown from `@PreAuthorize`)
- Any other `Exception` (via the catch-all handler)

**Handled by:** `GlobalExceptionHandler` (`@RestControllerAdvice`)

### Zone 2: Filter Zone (Before the DispatcherServlet)

This zone runs *before* the DispatcherServlet. Spring Security's filter chain lives here. If an exception occurs in a filter, the `@RestControllerAdvice` **never sees it** — the request hasn't reached the DispatcherServlet yet.

**What's caught here:**
- `AuthenticationException` — no token, expired token, invalid token (401)
- `AccessDeniedException` from Spring Security — valid token but insufficient role (403)

**Handled by:** `SecurityExceptionHandler` (implements `AuthenticationEntryPoint` + `AccessDeniedHandler`)

### Why This Matters

If you only build a `GlobalExceptionHandler` and forget the `SecurityExceptionHandler`, your 401 and 403 responses will revert to Spring Security's default format (which might be HTML or a minimal JSON that doesn't match your `ErrorResponse` structure). Your Angular frontend will break because it expects a consistent JSON shape.

```
HTTP Request
     │
     ▼
┌─────────────────────────────────────┐
│   ZONE 2: Security Filter Chain     │
│                                     │
│   AuthenticationException (401)     │──→ SecurityExceptionHandler
│   AccessDeniedException (403)       │──→ SecurityExceptionHandler
│                                     │
│   ✅ Authenticated?                 │
└──────────────┬──────────────────────┘
               │
               ▼
┌─────────────────────────────────────┐
│   ZONE 1: DispatcherServlet         │
│                                     │
│   ResourceNotFoundException (404)   │
│   DuplicateResourceException (409)  │
│   InvalidStateException (409)       │
│   BusinessValidationException (400) │──→ GlobalExceptionHandler
│   MethodArgumentNotValidException   │
│   DataIntegrityViolationException   │
│   AccessDeniedException (@PreAuth)  │
│   Exception (catch-all → 500)       │
│                                     │
└─────────────────────────────────────┘
```

> **Notice** that `AccessDeniedException` appears in **both** zones. In Zone 2, it's thrown by Spring Security's filter chain (e.g., user has a valid token but tries to access `/api/v1/admin/**` which requires `ADMIN` role). In Zone 1, it's thrown by `@PreAuthorize` annotations on controller/service methods. The `GlobalExceptionHandler` catches the Zone 1 version; the `SecurityExceptionHandler` catches the Zone 2 version.

---

## 4. Implementation: Layer by Layer

### 4.1 ErrorCodeEnum — The Error Vocabulary

This enum is the shared vocabulary between your backend and frontend. Every error response carries one of these codes, allowing the frontend to react programmatically (show specific UI, redirect to login, highlight a form field, etc.) without parsing human-readable messages.

```java
package org.viators.orderprocessingsystem.exceptions;

/**
 * Machine-readable error codes included in every error response.
 *
 * <p>These codes form the contract between backend and frontend.
 * The Angular frontend uses these to determine how to handle each error:
 * display a toast, redirect, highlight a field, etc.
 *
 * <p><strong>Naming convention:</strong> SCREAMING_SNAKE_CASE, grouped
 * by HTTP status category (4xx client errors, 5xx server errors).
 *
 * <p><strong>When to add a new code:</strong> When the frontend needs
 * to distinguish a new error scenario from existing ones. If the frontend
 * would handle two errors identically, they can share a code.
 */
public enum ErrorCodeEnum {

    // ── Business / Domain errors ──────────────────────────────────
    RESOURCE_NOT_FOUND,              // 404 — entity doesn't exist or is soft-deleted
    DUPLICATE_RESOURCE,              // 409 — unique constraint would be violated
    BUSINESS_VALIDATION_FAILED,      // 400 — domain rule violated (not a field-level error)
    INVALID_STATE,                   // 409 — operation not allowed in current entity state

    // ── Authentication / Authorization ────────────────────────────
    INVALID_CREDENTIALS,             // 401 — bad username/password or missing/expired token
    ACCESS_DENIED,                   // 403 — authenticated but insufficient role

    // ── Validation ────────────────────────────────────────────────
    VALIDATION_FAILED,               // 400 — @Valid field-level errors (with fieldErrors list)

    // ── Data Access ───────────────────────────────────────────────
    DATA_INTEGRITY_VIOLATION,        // 409 — DB constraint violation (unique, FK, etc.)

    // ── HTTP / Protocol ───────────────────────────────────────────
    MALFORMED_REQUEST,               // 400 — unparseable JSON body
    METHOD_NOT_ALLOWED,              // 405 — wrong HTTP method (POST on a GET endpoint)
    UNSUPPORTED_MEDIA_TYPE,          // 415 — wrong Content-Type header
    MISSING_REQUIRED_QUERY_PARAMETER,// 400 — required @RequestParam missing
    TYPE_MISMATCH,                   // 400 — path variable or param wrong type
    ENDPOINT_NOT_FOUND,              // 404 — URL doesn't map to any controller

    // ── Catch-All ─────────────────────────────────────────────────
    INTERNAL_SERVER_ERROR            // 500 — unexpected / unhandled error
}
```

**Design decisions:**

- **Why an enum and not Strings?** Enums are type-safe — you can't accidentally write `"RESORCE_NOT_FOUND"`. The compiler catches typos. They also serialize predictably to JSON (as the enum name string).

- **Why separate `RESOURCE_NOT_FOUND` from `ENDPOINT_NOT_FOUND`?** They're both 404s, but they mean very different things. `RESOURCE_NOT_FOUND` means "the URL is valid but this specific product/user doesn't exist." `ENDPOINT_NOT_FOUND` means "there is no controller mapped to this URL at all." The frontend might show a "not found" page for one and an "invalid link" page for the other.

- **Why separate `VALIDATION_FAILED` from `BUSINESS_VALIDATION_FAILED`?** `VALIDATION_FAILED` comes from Bean Validation (`@NotBlank`, `@Size`) and includes specific field errors. `BUSINESS_VALIDATION_FAILED` comes from your service layer (e.g., "order total exceeds credit limit") and is a general message, not tied to a specific form field.

---

### 4.2 ErrorResponse — The Client Contract

This is the standard error response shape. Every non-validation error in the entire application produces this exact structure:

```java
package org.viators.orderprocessingsystem.exceptions.dto;

import org.viators.orderprocessingsystem.exceptions.ErrorCodeEnum;

import java.time.Instant;

/**
 * Standard error response returned by all API error handlers.
 *
 * <p>This record defines the contract between the backend and any client
 * (Angular frontend, mobile app, third-party integration). Every error
 * response — whether from a business exception, a Spring Security rejection,
 * or a catch-all server error — produces this exact JSON structure.
 *
 * <p><strong>Why a record?</strong> Records are immutable by design, which
 * is exactly what we want for a response DTO — once created, it should
 * never be modified. Records also auto-generate {@code equals()},
 * {@code hashCode()}, and {@code toString()}, which helps with testing
 * and logging.
 *
 * <p><strong>JSON output example:</strong>
 * <pre>
 * {
 *   "status": 404,
 *   "errorCode": "RESOURCE_NOT_FOUND",
 *   "message": "Product not found with uuid: abc123 or is inactive",
 *   "path": "/api/v1/products/abc123",
 *   "timestamp": "2026-03-03T10:30:00.123Z"
 * }
 * </pre>
 *
 * @param status    the HTTP status code (e.g., 404, 409, 500)
 * @param errorCode the machine-readable error code for frontend handling
 * @param message   a human-readable description of what went wrong
 * @param path      the request URI that triggered the error
 * @param timestamp when the error occurred (UTC)
 */
public record ErrorResponse(
        int status,
        ErrorCodeEnum errorCode,
        String message,
        String path,
        Instant timestamp
) {

    /**
     * Factory method that auto-populates the timestamp.
     *
     * <p>Using a factory method instead of the canonical constructor
     * keeps the call sites clean — callers don't need to pass
     * {@code Instant.now()} every time.
     *
     * @param status    HTTP status code
     * @param errorCode machine-readable error code
     * @param message   human-readable error description
     * @param path      the request URI
     * @return a fully populated ErrorResponse with current timestamp
     */
    public static ErrorResponse of(int status, ErrorCodeEnum errorCode, String message, String path) {
        return new ErrorResponse(
                status,
                errorCode,
                message,
                path,
                Instant.now()
        );
    }
}
```

**Key design principles:**

- **`Instant` for timestamps, not `LocalDateTime`** — `Instant` is always UTC, which eliminates timezone ambiguity. The Angular frontend can convert it to the user's local time. `LocalDateTime` has no timezone information, so the client wouldn't know if "10:30:00" means UTC, Athens time, or New York time.

- **`int status` not `HttpStatus`** — We use the raw int so Jackson serializes it as a number (`404`), not an object or string. The frontend can directly compare `response.status === 404`.

- **Why a static factory `of()` instead of constructor overloading?** Factory methods have names that describe their intent. Also, it keeps the canonical constructor pure (all fields required) while providing a convenience method for the common case. If you later need a different factory (e.g., `ErrorResponse.ofNow(...)` with a custom clock for testing), you can add it without touching the record definition.

---

### 4.3 ValidationErrorResponse & FieldError — Validation-Specific Responses

When Bean Validation fails (e.g., `@NotBlank`, `@Size`, `@Email` on your request DTOs), the frontend needs to know *which fields* failed and *why*, so it can highlight the relevant form inputs. This requires a richer structure than `ErrorResponse`:

```java
package org.viators.orderprocessingsystem.exceptions.dto;

/**
 * Represents a single field-level validation error.
 *
 * <p>Used by the Angular frontend to highlight specific form fields
 * and display inline error messages.
 *
 * @param field         the field name (matches the JSON property / form input)
 * @param message       what's wrong (e.g., "must not be blank")
 * @param rejectedValue the value that was rejected (for debugging)
 */
public record FieldError(
        String field,
        String message,
        Object rejectedValue
) {}
```

```java
package org.viators.orderprocessingsystem.exceptions.dto;

import org.viators.orderprocessingsystem.exceptions.ErrorCodeEnum;

import java.time.Instant;
import java.util.List;

/**
 * Extended error response for validation failures.
 *
 * <p>Includes the same top-level fields as {@link ErrorResponse} plus
 * a {@code fieldErrors} list. This allows the frontend to:
 * <ul>
 *   <li>Show a general error banner using the {@code message} field</li>
 *   <li>Highlight specific form fields using the {@code fieldErrors} list</li>
 * </ul>
 *
 * <p><strong>JSON output example:</strong>
 * <pre>
 * {
 *   "status": 400,
 *   "errorCode": "VALIDATION_FAILED",
 *   "message": "Validation failed for 2 field(s)",
 *   "path": "/api/v1/auth/register",
 *   "timestamp": "2026-03-03T10:30:00.123Z",
 *   "fieldErrors": [
 *     {
 *       "field": "username",
 *       "message": "Username must be between 3 and 50 characters",
 *       "rejectedValue": "ab"
 *     },
 *     {
 *       "field": "email",
 *       "message": "Email must be a valid email address",
 *       "rejectedValue": "not-an-email"
 *     }
 *   ]
 * }
 * </pre>
 *
 * @param status      always 400
 * @param errorCode   always VALIDATION_FAILED
 * @param message     summary (e.g., "Validation failed for 2 field(s)")
 * @param path        the request URI
 * @param timestamp   when the error occurred
 * @param fieldErrors individual field-level errors
 */
public record ValidationErrorResponse(
        int status,
        ErrorCodeEnum errorCode,
        String message,
        String path,
        Instant timestamp,
        List<FieldError> fieldErrors
) {

    /**
     * Factory method that auto-populates status, errorCode, message, and timestamp.
     *
     * @param fieldErrors the list of field validation errors
     * @param path        the request URI
     * @return a fully populated ValidationErrorResponse
     */
    public static ValidationErrorResponse of(List<FieldError> fieldErrors, String path) {
        return new ValidationErrorResponse(
                400,
                ErrorCodeEnum.VALIDATION_FAILED,
                String.format("Validation failed for %d field(s)", fieldErrors.size()),
                path,
                Instant.now(),
                fieldErrors
        );
    }
}
```

**Why separate from `ErrorResponse`?** You could add an optional `fieldErrors` field to `ErrorResponse` and set it to `null` for non-validation errors. But that pollutes every error response with a field that's almost always null. Two distinct shapes make the API contract clearer: if `errorCode` is `VALIDATION_FAILED`, expect `fieldErrors`; otherwise, use `ErrorResponse`.

---

### 4.4 BaseException — The Custom Exception Foundation

All custom exceptions in the application extend this abstract base class. It ensures every exception carries a machine-readable `ErrorCodeEnum` and optional structured details.

```java
package org.viators.orderprocessingsystem.exceptions;

import lombok.Getter;

/**
 * Abstract base class for all application-specific exceptions.
 *
 * <p>Extends {@link RuntimeException} because we use Spring's declarative
 * transaction management, which only rolls back on unchecked exceptions
 * by default. If our exceptions were checked ({@code extends Exception}),
 * we'd need to add {@code rollbackFor} to every {@code @Transactional}
 * annotation.
 *
 * <p>Every subclass must provide:
 * <ul>
 *   <li>A human-readable {@code message} — displayed to the client</li>
 *   <li>An {@link ErrorCodeEnum} — used by the frontend for programmatic handling</li>
 * </ul>
 *
 * <p>Optionally, subclasses can include structured {@code details}
 * (e.g., a map of field errors for {@link BusinessValidationException}).
 *
 * <p><strong>Why {@code protected} constructors?</strong> This class is abstract —
 * you should never instantiate {@code BaseException} directly. The protected
 * constructors force you to create a meaningful subclass for each error scenario.
 */
@Getter
public abstract class BaseException extends RuntimeException {

    /** Machine-readable error code for frontend handling. */
    private final ErrorCodeEnum errorCode;

    /**
     * Optional structured details about the error.
     * Marked {@code transient} because exception serialization (if it happens)
     * shouldn't include arbitrary objects.
     */
    private final transient Object details;

    protected BaseException(String message, ErrorCodeEnum errorCode) {
        super(message);
        this.errorCode = errorCode;
        this.details = null;
    }

    protected BaseException(String message, ErrorCodeEnum errorCode, Object details) {
        super(message);
        this.errorCode = errorCode;
        this.details = details;
    }

    protected BaseException(String message, ErrorCodeEnum errorCode, Throwable cause) {
        super(message, cause);
        this.errorCode = errorCode;
        this.details = null;
    }
}
```

**Critical concepts:**

- **Why `RuntimeException` not `Exception`?** This is about Spring's `@Transactional` behavior. By default, `@Transactional` only rolls back on *unchecked* exceptions (`RuntimeException` and its subclasses). If `BaseException` extended `Exception` (a checked exception), you'd need to write `@Transactional(rollbackFor = BaseException.class)` everywhere — which is easy to forget and leads to subtle bugs where a transaction commits despite an error.

- **Why three constructors?** Different error scenarios need different data. A simple "not found" just needs a message and error code. A business validation error might include a map of field-specific messages (`details`). A retry-related error might wrap a root cause (`Throwable cause`).

- **Why `transient` on `details`?** The `details` field can hold anything (a `Map`, a `List`, a DTO). If the exception were ever serialized (e.g., sent across a network boundary in a distributed system), we don't want arbitrary objects in the serialized form. The `transient` keyword tells Java's serializer to skip this field.

---

### 4.5 ResourceNotFoundException — 404 Not Found

Thrown when a requested entity doesn't exist (or is soft-deleted/inactive):

```java
package org.viators.orderprocessingsystem.exceptions;

/**
 * Thrown when a requested resource cannot be found or is inactive.
 *
 * <p>Maps to HTTP 404 Not Found in the {@code GlobalExceptionHandler}.
 *
 * <p><strong>Usage in services:</strong>
 * <pre>
 * Product product = productRepository.findByUuidAndStatus(uuid, StatusEnum.ACTIVE)
 *     .orElseThrow(() -> new ResourceNotFoundException("Product", "uuid", uuid));
 * </pre>
 *
 * <p>The convenience constructor produces messages like:
 * "Product not found with uuid: abc123 or is inactive"
 */
public class ResourceNotFoundException extends BaseException {

    public ResourceNotFoundException(String message) {
        super(message, ErrorCodeEnum.RESOURCE_NOT_FOUND);
    }

    /**
     * Convenience constructor that builds a standard "not found" message.
     *
     * @param resourceType the entity type (e.g., "Product", "User", "Order")
     * @param identifier   the field used for lookup (e.g., "uuid", "username")
     * @param value        the actual value that wasn't found
     */
    public ResourceNotFoundException(String resourceType, String identifier, String value) {
        super(
                "%s not found with %s: %s or is inactive".formatted(resourceType, identifier, value),
                ErrorCodeEnum.RESOURCE_NOT_FOUND
        );
    }
}
```

**Why "or is inactive" in the message?** Because our `findByUuidAndStatus` queries filter by `StatusEnum.ACTIVE`. If a product exists but is soft-deleted, the query returns `Optional.empty()`. Rather than exposing whether the entity exists but is soft-deleted (which could be an information leak), we use a generic message that covers both cases.

---

### 4.6 DuplicateResourceException — 409 Conflict

Thrown when creating/updating would violate a unique constraint:

```java
package org.viators.orderprocessingsystem.exceptions;

/**
 * Thrown when a resource creation or update would result in a duplicate.
 *
 * <p>Maps to HTTP 409 Conflict in the {@code GlobalExceptionHandler}.
 *
 * <p><strong>Usage in services (registration example):</strong>
 * <pre>
 * if (userRepository.existsByUsername(request.username())) {
 *     throw new DuplicateResourceException("User", "username", request.username());
 * }
 * </pre>
 *
 * <p>The convenience constructor produces messages like:
 * "User with username: panos already exists in system"
 */
public class DuplicateResourceException extends BaseException {

    public DuplicateResourceException(String message) {
        super(message, ErrorCodeEnum.DUPLICATE_RESOURCE);
    }

    /**
     * Convenience constructor that builds a standard "already exists" message.
     *
     * @param resourceType the entity type (e.g., "User", "Product")
     * @param field        the field that would be duplicated (e.g., "username", "email")
     * @param value        the duplicate value
     */
    public DuplicateResourceException(String resourceType, String field, String value) {
        super(
                "%s with %s: %s already exists in system".formatted(resourceType, field, value),
                ErrorCodeEnum.DUPLICATE_RESOURCE
        );
    }
}
```

**Why check before saving instead of catching `DataIntegrityViolationException`?** Two reasons. First, it gives a clear, user-friendly message ("User with username: panos already exists") instead of a generic database error. Second, it fails fast — we don't waste resources building the entity, hashing the password, and hitting the database just to get a constraint violation.

---

### 4.7 BusinessValidationException — 400 Bad Request

Thrown when a domain/business rule is violated (beyond simple field validation):

```java
package org.viators.orderprocessingsystem.exceptions;

import java.util.Map;

/**
 * Thrown when a business/domain rule is violated.
 *
 * <p>This is NOT for field-level validation (that's {@code @Valid} +
 * {@code MethodArgumentNotValidException}). This is for cross-field
 * or service-level rules like:
 * <ul>
 *   <li>"Order total exceeds the customer's credit limit"</li>
 *   <li>"Cannot create more than 50 items per user"</li>
 *   <li>"Start date must be before end date" (cross-field)</li>
 * </ul>
 *
 * <p>Maps to HTTP 400 Bad Request in the {@code GlobalExceptionHandler}.
 *
 * <p><strong>Usage with structured details:</strong>
 * <pre>
 * throw new BusinessValidationException(
 *     "Order validation failed",
 *     Map.of(
 *         "total", "Exceeds credit limit of $5000",
 *         "items", "Cannot order more than 100 items at once"
 *     )
 * );
 * </pre>
 */
public class BusinessValidationException extends BaseException {

    public BusinessValidationException(String message) {
        super(message, ErrorCodeEnum.BUSINESS_VALIDATION_FAILED);
    }

    /**
     * Constructor with structured field errors.
     *
     * <p>The map is stored in the {@code details} field of {@link BaseException}
     * and can be included in the error response if needed.
     *
     * @param message     summary of what failed
     * @param fieldErrors map of field name → error message
     */
    public BusinessValidationException(String message, Map<String, String> fieldErrors) {
        super(message, ErrorCodeEnum.BUSINESS_VALIDATION_FAILED, fieldErrors);
    }
}
```

---

### 4.8 InvalidCredentialsException — 401 Unauthorized

Thrown when authentication fails. Intentionally vague to prevent information leakage:

```java
package org.viators.orderprocessingsystem.exceptions;

/**
 * Thrown when authentication credentials are invalid.
 *
 * <p>Maps to HTTP 401 Unauthorized in the {@code GlobalExceptionHandler}.
 *
 * <p><strong>Security consideration:</strong> The default message is deliberately
 * generic — "Invalid credentials provided". It does NOT say "user not found"
 * or "wrong password". Revealing which part failed helps attackers enumerate
 * valid usernames.
 *
 * <p><strong>Usage in AuthenticationService:</strong>
 * <pre>
 * try {
 *     authenticationManager.authenticate(
 *         new UsernamePasswordAuthenticationToken(username, password)
 *     );
 * } catch (BadCredentialsException e) {
 *     throw new InvalidCredentialsException();
 * }
 * </pre>
 */
public class InvalidCredentialsException extends BaseException {

    public InvalidCredentialsException(String message) {
        super(message, ErrorCodeEnum.INVALID_CREDENTIALS);
    }

    public InvalidCredentialsException() {
        super(
                "Invalid credentials provided",
                ErrorCodeEnum.INVALID_CREDENTIALS
        );
    }
}
```

---

### 4.9 AccessDeniedException — 403 Forbidden

A custom (non-Spring-Security) exception for application-level access control:

```java
package org.viators.orderprocessingsystem.exceptions;

/**
 * Application-level access denied exception.
 *
 * <p>This is our CUSTOM exception, separate from Spring Security's
 * {@link org.springframework.security.access.AccessDeniedException}.
 *
 * <p>Maps to HTTP 403 Forbidden in the {@code GlobalExceptionHandler}.
 *
 * <p><strong>When to use which:</strong>
 * <ul>
 *   <li>Spring Security's {@code AccessDeniedException} — thrown automatically
 *       by Spring Security when {@code @PreAuthorize} fails or role checks fail
 *       in the filter chain. You DON'T throw this manually.</li>
 *   <li>This custom exception — thrown from your service layer when you
 *       implement custom authorization logic. Example: "A user can only
 *       view their own orders, not other users' orders."</li>
 * </ul>
 *
 * <p><strong>Usage example:</strong>
 * <pre>
 * if (!order.getUser().getUuid().equals(currentUserUuid)) {
 *     throw new AccessDeniedException(
 *         "You can only access your own orders"
 *     );
 * }
 * </pre>
 */
public class AccessDeniedException extends BaseException {

    public AccessDeniedException(String message) {
        super(message, ErrorCodeEnum.ACCESS_DENIED);
    }

    public AccessDeniedException() {
        super(
                "You don't have permission to access this resource",
                ErrorCodeEnum.ACCESS_DENIED
        );
    }
}
```

---

### 4.10 InvalidStateException — 409 Conflict

Thrown when an operation is attempted on an entity in an incompatible state:

```java
package org.viators.orderprocessingsystem.exceptions;

/**
 * Thrown when an operation is not valid for the entity's current state.
 *
 * <p>Maps to HTTP 409 Conflict in the {@code GlobalExceptionHandler}.
 *
 * <p>This enforces state machine rules in your domain. Examples:
 * <ul>
 *   <li>"Cannot cancel an order that has already been shipped"</li>
 *   <li>"Cannot delete a product that has active orders"</li>
 *   <li>"Cannot reactivate a permanently banned user"</li>
 * </ul>
 *
 * <p><strong>Usage example:</strong>
 * <pre>
 * if (order.getStatus() == OrderStatus.SHIPPED) {
 *     throw new InvalidStateException("Order", "cancel", "SHIPPED");
 *     // Message: "Cannot cancel Order in state SHIPPED"
 * }
 * </pre>
 */
public class InvalidStateException extends BaseException {

    public InvalidStateException(String message) {
        super(message, ErrorCodeEnum.INVALID_STATE);
    }

    /**
     * Convenience constructor for state-transition violations.
     *
     * @param resource        the entity type (e.g., "Order")
     * @param attemptedAction what was attempted (e.g., "cancel", "delete")
     * @param currentState    the current state that blocks the action
     */
    public InvalidStateException(String resource, String attemptedAction, String currentState) {
        super(
                "Cannot %s %s in state %s".formatted(attemptedAction, resource, currentState),
                ErrorCodeEnum.INVALID_STATE
        );
    }
}
```

---

### 4.11 GlobalExceptionHandler — The Central Brain

This is the heart of the exception handling system. It's a single class that maps every exception type to the correct HTTP status and response body. Spring discovers it via `@RestControllerAdvice` and routes all controller-level exceptions here.

```java
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

/**
 * Global exception handler for the entire application.
 *
 * <p>Uses {@link RestControllerAdvice} to intercept exceptions thrown by
 * any controller, and maps them to consistent JSON error responses.
 *
 * <p><strong>How {@code @RestControllerAdvice} works:</strong>
 * It combines {@code @ControllerAdvice} (scans all controllers for exception
 * handling) with {@code @ResponseBody} (automatically serializes return values
 * to JSON). When an exception is thrown from a controller, Spring looks for
 * a matching {@code @ExceptionHandler} method in all registered advice classes.
 * It picks the handler whose exception parameter is the most specific match.
 *
 * <p><strong>Handler priority:</strong> Most specific wins. If you throw a
 * {@code ResourceNotFoundException}, Spring will prefer the handler for
 * {@code ResourceNotFoundException} over the catch-all {@code Exception} handler.
 *
 * <p><strong>Why {@code @Order(Ordered.HIGHEST_PRECEDENCE)}?</strong>
 * If there are multiple {@code @RestControllerAdvice} classes in the
 * application (e.g., a library adds one), this ensures our handler
 * is checked first.
 *
 * <p>This handler is organized into sections:
 * <ol>
 *   <li>Business/Domain exceptions (our custom exceptions)</li>
 *   <li>Validation exceptions (Bean Validation, constraints)</li>
 *   <li>Security exceptions (Spring Security in controller zone)</li>
 *   <li>Data access exceptions (JPA/Hibernate)</li>
 *   <li>HTTP/Web exceptions (Spring MVC)</li>
 *   <li>Catch-all (safety net for unexpected errors)</li>
 * </ol>
 */
@RestControllerAdvice
@Order(Ordered.HIGHEST_PRECEDENCE)
@Slf4j
public class GlobalExceptionHandler {

    // ═══════════════════════════════════════════════════════════════
    // SECTION 1: BUSINESS / DOMAIN EXCEPTIONS (Our Custom Exceptions)
    // ═══════════════════════════════════════════════════════════════

    /**
     * Handles entity not found scenarios.
     * <p>When: Repository query returns empty Optional
     * <p>Status: 404 Not Found
     */
    @ExceptionHandler(ResourceNotFoundException.class)
    public ResponseEntity<ErrorResponse> handleResourceNotFound(
            ResourceNotFoundException ex, HttpServletRequest request) {

        log.debug("Resource not found: {} - Path: {}",
                ex.getMessage(), request.getRequestURI());

        return buildErrorResponse(HttpStatus.NOT_FOUND, ex, request);
    }

    /**
     * Handles duplicate resource creation attempts.
     * <p>When: existsByUsername() or existsByEmail() returns true
     * <p>Status: 409 Conflict
     */
    @ExceptionHandler(DuplicateResourceException.class)
    public ResponseEntity<ErrorResponse> handleDuplicateResource(
            DuplicateResourceException ex, HttpServletRequest request) {

        log.warn("Duplicate resource attempt: {} - Path: {}",
                ex.getMessage(), request.getRequestURI());

        return buildErrorResponse(HttpStatus.CONFLICT, ex, request);
    }

    /**
     * Handles business rule violations.
     * <p>When: Service-layer domain validation fails
     * <p>Status: 400 Bad Request
     */
    @ExceptionHandler(BusinessValidationException.class)
    public ResponseEntity<ErrorResponse> handleBusinessValidation(
            BusinessValidationException ex, HttpServletRequest request) {

        log.debug("Business validation failed: {} - Path: {}",
                ex.getMessage(), request.getRequestURI());

        return buildErrorResponse(HttpStatus.BAD_REQUEST, ex, request);
    }

    /**
     * Handles invalid credentials during login.
     * <p>When: AuthenticationManager.authenticate() fails
     * <p>Status: 401 Unauthorized
     */
    @ExceptionHandler(InvalidCredentialsException.class)
    public ResponseEntity<ErrorResponse> handleInvalidCredentials(
            InvalidCredentialsException ex, HttpServletRequest request) {

        log.warn("Invalid credentials attempt - Path: {}",
                request.getRequestURI());

        return buildErrorResponse(HttpStatus.UNAUTHORIZED, ex, request);
    }

    /**
     * Handles application-level access denial.
     * <p>When: Custom authorization check fails in service layer
     * <p>Status: 403 Forbidden
     */
    @ExceptionHandler(AccessDeniedException.class)
    public ResponseEntity<ErrorResponse> handleAccessDenied(
            AccessDeniedException ex, HttpServletRequest request) {

        log.warn("Access denied: {} - Path: {}",
                ex.getMessage(), request.getRequestURI());

        return buildErrorResponse(HttpStatus.FORBIDDEN, ex, request);
    }

    /**
     * Handles invalid state transitions.
     * <p>When: Operation attempted on entity in wrong state
     * <p>Status: 409 Conflict
     */
    @ExceptionHandler(InvalidStateException.class)
    public ResponseEntity<ErrorResponse> handleInvalidState(
            InvalidStateException ex, HttpServletRequest request) {

        log.debug("Invalid state: {} - Path: {}",
                ex.getMessage(), request.getRequestURI());

        return buildErrorResponse(HttpStatus.CONFLICT, ex, request);
    }

    // ═══════════════════════════════════════════════════════════════
    // SECTION 2: VALIDATION EXCEPTIONS (Bean Validation)
    // ═══════════════════════════════════════════════════════════════

    /**
     * Handles @Valid failures on @RequestBody DTOs.
     *
     * <p>This fires when a controller parameter annotated with {@code @Valid}
     * fails Bean Validation. Spring collects ALL field errors before throwing
     * (it doesn't stop at the first failure).
     *
     * <p><strong>How the mapping works:</strong>
     * Spring's {@code BindingResult} contains Spring-specific
     * {@code FieldError} objects. We map them to our own {@code FieldError}
     * record to decouple the response format from Spring's internals.
     *
     * <p>Status: 400 Bad Request
     */
    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ValidationErrorResponse> handleMethodArgumentNotValid(
            MethodArgumentNotValidException ex, HttpServletRequest request) {

        log.debug("Validation failed with {} errors - Path: {}",
                ex.getErrorCount(), request.getRequestURI());

        List<FieldError> fieldErrors = ex.getBindingResult()
                .getFieldErrors()
                .stream()
                .map(error -> new FieldError(
                        error.getField(),
                        error.getDefaultMessage() != null
                                ? error.getDefaultMessage()
                                : "Invalid value",
                        error.getRejectedValue()
                ))
                .toList();

        ValidationErrorResponse response = ValidationErrorResponse.of(
                fieldErrors, request.getRequestURI()
        );

        return ResponseEntity.badRequest().body(response);
    }

    /**
     * Handles constraint violations on @PathVariable and @RequestParam.
     *
     * <p>Unlike {@code MethodArgumentNotValidException} (which fires for
     * {@code @RequestBody}), this fires for constraints on individual
     * method parameters: {@code @RequestParam @Min(1) int page}.
     *
     * <p>Status: 400 Bad Request
     */
    @ExceptionHandler(ConstraintViolationException.class)
    public ResponseEntity<ValidationErrorResponse> handleConstraintViolation(
            ConstraintViolationException ex, HttpServletRequest request) {

        log.debug("Constraint violation - Path: {}", request.getRequestURI());

        List<FieldError> fieldErrors = ex.getConstraintViolations()
                .stream()
                .map(violation -> {
                    String field = extractFieldName(
                            violation.getPropertyPath().toString()
                    );
                    return new FieldError(field, violation.getMessage(), null);
                })
                .toList();

        ValidationErrorResponse response = ValidationErrorResponse.of(
                fieldErrors, request.getRequestURI()
        );

        return ResponseEntity.badRequest().body(response);
    }

    // ═══════════════════════════════════════════════════════════════
    // SECTION 3: SECURITY EXCEPTIONS (Spring Security — Zone 1)
    // ═══════════════════════════════════════════════════════════════

    /**
     * Handles Spring Security AccessDeniedException from @PreAuthorize.
     *
     * <p><strong>Important distinction:</strong> This handles the SPRING
     * SECURITY exception (thrown when {@code @PreAuthorize} fails),
     * NOT our custom {@code AccessDeniedException}. Note the fully
     * qualified class name to avoid ambiguity.
     *
     * <p>Status: 403 Forbidden
     */
    @ExceptionHandler(org.springframework.security.access.AccessDeniedException.class)
    public ResponseEntity<ErrorResponse> handleSpringSecurityAccessDenied(
            org.springframework.security.access.AccessDeniedException ex,
            HttpServletRequest request) {

        log.warn("Spring Security access denied - Path: {}",
                request.getRequestURI());

        ErrorResponse error = ErrorResponse.of(
                HttpStatus.FORBIDDEN.value(),
                ErrorCodeEnum.ACCESS_DENIED,
                "You do not have permission to access this resource",
                request.getRequestURI()
        );

        return ResponseEntity.status(HttpStatus.FORBIDDEN).body(error);
    }

    // ═══════════════════════════════════════════════════════════════
    // SECTION 4: DATA ACCESS EXCEPTIONS (JPA / Hibernate)
    // ═══════════════════════════════════════════════════════════════

    /**
     * Handles database constraint violations that bypass our service checks.
     *
     * <p>This is a safety net. Ideally, our service layer checks for
     * duplicates before saving (throwing {@code DuplicateResourceException}).
     * But if a constraint violation slips through (race condition, missing
     * check), this catches it.
     *
     * <p><strong>SECURITY: Never expose SQL details to the client.</strong>
     * The actual SQL error is in {@code ex.getMostSpecificCause()} — we
     * log it at ERROR level for debugging but return a generic message.
     *
     * <p>Status: 409 Conflict
     */
    @ExceptionHandler(DataIntegrityViolationException.class)
    public ResponseEntity<ErrorResponse> handleDataIntegrityViolation(
            DataIntegrityViolationException ex, HttpServletRequest request) {

        log.error("Data integrity violation - Path: {} - Cause: {}",
                request.getRequestURI(),
                ex.getMostSpecificCause().getMessage());

        ErrorResponse error = ErrorResponse.of(
                HttpStatus.CONFLICT.value(),
                ErrorCodeEnum.DATA_INTEGRITY_VIOLATION,
                "A data integrity constraint was violated. "
                        + "Please check your request for duplicate or invalid references.",
                request.getRequestURI()
        );

        return ResponseEntity.status(HttpStatus.CONFLICT).body(error);
    }

    // ═══════════════════════════════════════════════════════════════
    // SECTION 5: HTTP / WEB EXCEPTIONS (Spring MVC)
    // ═══════════════════════════════════════════════════════════════

    /**
     * Handles malformed or unparseable JSON request bodies.
     * <p>Status: 400 Bad Request
     */
    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<ErrorResponse> handleHttpMessageNotReadable(
            HttpMessageNotReadableException ex, HttpServletRequest request) {

        log.debug("Malformed request body - Path: {} - Detail: {}",
                request.getRequestURI(), ex.getMessage());

        ErrorResponse error = ErrorResponse.of(
                HttpStatus.BAD_REQUEST.value(),
                ErrorCodeEnum.MALFORMED_REQUEST,
                "The request body is malformed or contains invalid JSON",
                request.getRequestURI()
        );

        return ResponseEntity.badRequest().body(error);
    }

    /**
     * Handles wrong HTTP method on an endpoint (e.g., POST on a GET-only endpoint).
     * <p>Status: 405 Method Not Allowed
     */
    @ExceptionHandler(HttpRequestMethodNotSupportedException.class)
    public ResponseEntity<ErrorResponse> handleMethodNotSupported(
            HttpRequestMethodNotSupportedException ex, HttpServletRequest request) {

        log.debug("Method {} not supported for {} - Supported: {}",
                ex.getMethod(), request.getRequestURI(),
                ex.getSupportedHttpMethods());

        ErrorResponse error = ErrorResponse.of(
                HttpStatus.METHOD_NOT_ALLOWED.value(),
                ErrorCodeEnum.METHOD_NOT_ALLOWED,
                ex.getMessage(),
                request.getRequestURI()
        );

        return ResponseEntity.status(HttpStatus.METHOD_NOT_ALLOWED).body(error);
    }

    /**
     * Handles wrong Content-Type header (e.g., sending XML to a JSON endpoint).
     * <p>Status: 415 Unsupported Media Type
     */
    @ExceptionHandler(HttpMediaTypeNotSupportedException.class)
    public ResponseEntity<ErrorResponse> handleMediaTypeNotSupported(
            HttpMediaTypeNotSupportedException ex, HttpServletRequest request) {

        log.debug("Media type {} not supported - Path: {}",
                ex.getContentType(), request.getRequestURI());

        ErrorResponse error = ErrorResponse.of(
                HttpStatus.UNSUPPORTED_MEDIA_TYPE.value(),
                ErrorCodeEnum.UNSUPPORTED_MEDIA_TYPE,
                ex.getMessage(),
                request.getRequestURI()
        );

        return ResponseEntity.status(HttpStatus.UNSUPPORTED_MEDIA_TYPE).body(error);
    }

    /**
     * Handles missing required @RequestParam.
     * <p>Status: 400 Bad Request
     */
    @ExceptionHandler(MissingServletRequestParameterException.class)
    public ResponseEntity<ErrorResponse> handleMissingParameter(
            MissingServletRequestParameterException ex, HttpServletRequest request) {

        log.debug("Missing parameter '{}' - Path: {}",
                ex.getParameterName(), request.getRequestURI());

        ErrorResponse error = ErrorResponse.of(
                HttpStatus.BAD_REQUEST.value(),
                ErrorCodeEnum.MISSING_REQUIRED_QUERY_PARAMETER,
                ex.getMessage(),
                request.getRequestURI()
        );

        return ResponseEntity.badRequest().body(error);
    }

    /**
     * Handles type conversion failures on @PathVariable and @RequestParam.
     *
     * <p>Example: endpoint expects {@code /products/{id}} where id is Long,
     * but client sends {@code /products/abc}.
     *
     * <p>Status: 400 Bad Request
     */
    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    public ResponseEntity<ErrorResponse> handleTypeMismatch(
            MethodArgumentTypeMismatchException ex, HttpServletRequest request) {

        log.debug("Type mismatch for '{}' - Path: {}",
                ex.getName(), request.getRequestURI());

        String expectedType = ex.getRequiredType() != null
                ? ex.getRequiredType().getSimpleName()
                : "unknown";

        ErrorResponse error = ErrorResponse.of(
                HttpStatus.BAD_REQUEST.value(),
                ErrorCodeEnum.TYPE_MISMATCH,
                String.format("Parameter %s should be of type %s",
                        ex.getName(), expectedType),
                request.getRequestURI()
        );

        return ResponseEntity.badRequest().body(error);
    }

    /**
     * Handles requests to URLs that don't map to any controller.
     *
     * <p>Requires {@code spring.mvc.throw-exception-if-no-handler-found=true}
     * (default in Spring Boot 4) and
     * {@code spring.web.resources.add-mappings=false} to work for static
     * resources too.
     *
     * <p>Status: 404 Not Found
     */
    @ExceptionHandler(NoResourceFoundException.class)
    public ResponseEntity<ErrorResponse> handleNoResourceFound(
            NoResourceFoundException ex, HttpServletRequest request) {

        log.debug("No resource found - Path: {}", request.getRequestURI());

        ErrorResponse error = ErrorResponse.of(
                HttpStatus.NOT_FOUND.value(),
                ErrorCodeEnum.ENDPOINT_NOT_FOUND,
                "The requested endpoint does not exist",
                request.getRequestURI()
        );

        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(error);
    }

    // ═══════════════════════════════════════════════════════════════
    // SECTION 6: CATCH-ALL (Safety Net)
    // ═══════════════════════════════════════════════════════════════

    /**
     * Catches any exception not handled by a more specific handler.
     *
     * <p><strong>This is a safety net.</strong> If an exception reaches here:
     * <ol>
     *   <li>It's a bug or an unhandled edge case in our code</li>
     *   <li>Log EVERYTHING for debugging (full stack trace)</li>
     *   <li>Return NOTHING specific to the client (security)</li>
     * </ol>
     *
     * <p><strong>Why log at ERROR with the full exception?</strong>
     * Passing {@code ex} as the last argument to {@code log.error()}
     * tells SLF4J to print the full stack trace. This is critical for
     * debugging — without it, you'd only see the message and wouldn't
     * know where the error originated.
     *
     * <p><strong>Why a generic message?</strong> Returning internal details
     * (class names, SQL, stack traces) to the client is a security risk.
     * An attacker could use them to probe for vulnerabilities. The generic
     * message tells the client "something went wrong" without revealing
     * implementation details.
     *
     * <p>Status: 500 Internal Server Error
     */
    @ExceptionHandler(Exception.class)
    public ResponseEntity<ErrorResponse> handleAllUncaughtExceptions(
            Exception ex, HttpServletRequest request) {

        log.error("Unexpected error at {} - Exception: {}",
                request.getRequestURI(),
                ex.getClass().getName(),
                ex); // Full stack trace

        ErrorResponse error = ErrorResponse.of(
                HttpStatus.INTERNAL_SERVER_ERROR.value(),
                ErrorCodeEnum.INTERNAL_SERVER_ERROR,
                "An unexpected error occurred. Please try again later.",
                request.getRequestURI()
        );

        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(error);
    }

    // ═══════════════════════════════════════════════════════════════
    // PRIVATE HELPERS
    // ═══════════════════════════════════════════════════════════════

    /**
     * Builds a standard error response from a BaseException.
     *
     * <p>This helper exists because all our custom exceptions share the
     * same structure (message + errorCode from BaseException). Without it,
     * every handler would repeat the same 5 lines of ErrorResponse creation.
     */
    private ResponseEntity<ErrorResponse> buildErrorResponse(
            HttpStatus status, BaseException ex, HttpServletRequest request) {

        ErrorResponse error = ErrorResponse.of(
                status.value(),
                ex.getErrorCode(),
                ex.getMessage(),
                request.getRequestURI()
        );

        return ResponseEntity.status(status).body(error);
    }

    /**
     * Extracts the field name from a Jakarta Validation property path.
     *
     * <p>Property paths look like "methodName.parameterName" (e.g.,
     * "findProducts.page"). We want just "page" — the last segment
     * after the dot.
     */
    private String extractFieldName(String propertyPath) {
        if (propertyPath == null || propertyPath.isEmpty()) {
            return "unknown";
        }

        int lastDot = propertyPath.lastIndexOf('.');
        return lastDot >= 0 ? propertyPath.substring(lastDot + 1) : propertyPath;
    }
}
```

**Key concepts to internalize:**

- **`@RestControllerAdvice`** = `@ControllerAdvice` + `@ResponseBody`. The `@ResponseBody` part means Spring automatically serializes whatever you return (our records) to JSON via Jackson. Without it, Spring would try to resolve a view name.

- **Handler priority** — Spring picks the most specific handler. If `ResourceNotFoundException extends BaseException extends RuntimeException extends Exception`, and you throw a `ResourceNotFoundException`, Spring will match the `ResourceNotFoundException` handler, NOT the `Exception` catch-all.

- **`@Order(Ordered.HIGHEST_PRECEDENCE)`** — if you or a third-party library registers multiple `@RestControllerAdvice` beans, this ensures ours is evaluated first. Without explicit ordering, Spring's behavior is non-deterministic (whichever advice class loads first wins).

- **Log levels matter** — `log.debug()` for expected errors (not found, validation), `log.warn()` for suspicious activity (bad credentials, access denied), `log.error()` for bugs and unexpected failures. This helps when filtering logs in production.

- **The `buildErrorResponse` helper** — DRY principle applied. All six custom exception handlers follow the same pattern, so we extract it. The Spring MVC exception handlers don't use it because they create `ErrorResponse` directly (they're not `BaseException` subclasses).

---

### 4.12 SecurityExceptionHandler — The Filter-Level Handler

This handler covers Zone 2 — the security filter chain. It's already covered in the JWT guide (Section 6.10), but let's understand how it connects to the exception handling architecture:

```java
package org.viators.orderprocessingsystem.auth;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.security.web.access.AccessDeniedHandler;
import org.springframework.stereotype.Component;
import org.viators.orderprocessingsystem.exceptions.ErrorCodeEnum;
import org.viators.orderprocessingsystem.exceptions.dto.ErrorResponse;
import tools.jackson.databind.ObjectMapper;

import java.io.IOException;

/**
 * Handles security exceptions at the filter level (Zone 2).
 *
 * <p>This class produces the same {@link ErrorResponse} JSON as the
 * {@code GlobalExceptionHandler}, ensuring the Angular frontend sees
 * a consistent error format regardless of where the exception originated.
 *
 * <p><strong>Why ObjectMapper instead of ResponseEntity?</strong>
 * At the filter level, we don't have access to Spring MVC's response
 * handling pipeline. We write directly to the raw
 * {@link HttpServletResponse} using Jackson's {@link ObjectMapper}.
 *
 * <p><strong>Registration:</strong> These handlers are registered in
 * {@code SecurityConfig} via:
 * <pre>
 * .exceptionHandling(exceptions -> exceptions
 *     .authenticationEntryPoint(securityExceptionHandler)
 *     .accessDeniedHandler(securityExceptionHandler)
 * )
 * </pre>
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class SecurityExceptionHandler implements AuthenticationEntryPoint, AccessDeniedHandler {

    private final ObjectMapper objectMapper;

    /**
     * Handles 401 Unauthorized — no token, expired token, invalid token.
     */
    @Override
    public void commence(HttpServletRequest request,
                         HttpServletResponse response,
                         AuthenticationException authException) throws IOException {

        log.debug("Authentication failed for {} {} - {}",
                request.getMethod(), request.getRequestURI(),
                authException.getMessage());

        writeErrorResponse(
                response,
                HttpStatus.UNAUTHORIZED,
                ErrorCodeEnum.INVALID_CREDENTIALS,
                "Authentication is required to access this resource",
                request.getRequestURI()
        );
    }

    /**
     * Handles 403 Forbidden — valid token but role check failed in filter chain.
     */
    @Override
    public void handle(HttpServletRequest request,
                       HttpServletResponse response,
                       AccessDeniedException accessDeniedException) throws IOException {

        log.warn("Access denied for {} {} - User lacks required permissions",
                request.getMethod(), request.getRequestURI());

        writeErrorResponse(
                response,
                HttpStatus.FORBIDDEN,
                ErrorCodeEnum.ACCESS_DENIED,
                "You do not have permission to access this resource",
                request.getRequestURI()
        );
    }

    /**
     * Writes JSON directly to the servlet response output stream.
     *
     * <p>This bypasses Spring MVC entirely — we're writing raw bytes.
     * We must set both the status code and Content-Type header manually.
     */
    private void writeErrorResponse(HttpServletResponse response,
                                    HttpStatus status,
                                    ErrorCodeEnum errorCode,
                                    String message,
                                    String path) throws IOException {

        ErrorResponse errorResponse = ErrorResponse.of(
                status.value(), errorCode, message, path
        );

        response.setStatus(status.value());
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        objectMapper.writeValue(response.getOutputStream(), errorResponse);
    }
}
```

---

## 5. How Everything Connects: Exception Lifecycle

Let's trace a complete 409 Conflict scenario end-to-end. A user tries to register with an existing username:

```
┌─────────────────────────────────────────────────────────────────────┐
│                         HTTP Request                                │
│  POST /api/v1/auth/register                                         │
│  { "username": "panos", "email": "new@example.com", ... }          │
└─────────────────────────────┬───────────────────────────────────────┘
                              │
                              ▼
┌─────────────────────────────────────────────────────────────────────┐
│                   JwtAuthenticationFilter                           │
│  No Authorization header → skip authentication, pass to next filter │
└─────────────────────────────┬───────────────────────────────────────┘
                              │
                              ▼
┌─────────────────────────────────────────────────────────────────────┐
│                   AuthorizationFilter                                │
│  /api/v1/auth/** is permitAll() → ✅ allow through                  │
└─────────────────────────────┬───────────────────────────────────────┘
                              │
                              ▼
┌─────────────────────────────────────────────────────────────────────┐
│                   AuthenticationController                           │
│  register(@Valid @RequestBody RegisterRequest request)               │
│  ✅ @Valid passes (all fields are valid)                             │
│  → calls authenticationService.register(request)                    │
└─────────────────────────────┬───────────────────────────────────────┘
                              │
                              ▼
┌─────────────────────────────────────────────────────────────────────┐
│                   AuthenticationService                              │
│  userRepository.existsByUsername("panos") → true ❌                  │
│  → throw new DuplicateResourceException("User", "username", "panos")│
└─────────────────────────────┬───────────────────────────────────────┘
                              │ exception propagates up
                              ▼
┌─────────────────────────────────────────────────────────────────────┐
│                   GlobalExceptionHandler                             │
│  @ExceptionHandler(DuplicateResourceException.class) matches!       │
│  → buildErrorResponse(HttpStatus.CONFLICT, ex, request)             │
│  → log.warn("Duplicate resource attempt: User with username:        │
│              panos already exists in system - Path: ...")            │
└─────────────────────────────┬───────────────────────────────────────┘
                              │
                              ▼
┌─────────────────────────────────────────────────────────────────────┐
│                         HTTP Response                               │
│  409 Conflict                                                       │
│  Content-Type: application/json                                     │
│  {                                                                  │
│    "status": 409,                                                   │
│    "errorCode": "DUPLICATE_RESOURCE",                               │
│    "message": "User with username: panos already exists in system", │
│    "path": "/api/v1/auth/register",                                 │
│    "timestamp": "2026-03-03T10:30:00.123Z"                         │
│  }                                                                  │
└─────────────────────────────────────────────────────────────────────┘
```

---

## 6. When to Throw What — A Decision Guide

| Scenario | Exception | HTTP Status | ErrorCode |
|----------|-----------|-------------|-----------|
| Entity not found by UUID | `ResourceNotFoundException` | 404 | `RESOURCE_NOT_FOUND` |
| Soft-deleted entity accessed | `ResourceNotFoundException` | 404 | `RESOURCE_NOT_FOUND` |
| Duplicate username/email | `DuplicateResourceException` | 409 | `DUPLICATE_RESOURCE` |
| Wrong password at login | `InvalidCredentialsException` | 401 | `INVALID_CREDENTIALS` |
| Missing/expired JWT token | *(handled by SecurityExceptionHandler)* | 401 | `INVALID_CREDENTIALS` |
| Insufficient role (filter) | *(handled by SecurityExceptionHandler)* | 403 | `ACCESS_DENIED` |
| @PreAuthorize fails | *(caught by GlobalExceptionHandler)* | 403 | `ACCESS_DENIED` |
| User accesses another user's data | `AccessDeniedException` (custom) | 403 | `ACCESS_DENIED` |
| Cancel a shipped order | `InvalidStateException` | 409 | `INVALID_STATE` |
| Order total exceeds limit | `BusinessValidationException` | 400 | `BUSINESS_VALIDATION_FAILED` |
| @Valid fails on DTO | *(MethodArgumentNotValidException)* | 400 | `VALIDATION_FAILED` |
| Malformed JSON body | *(HttpMessageNotReadableException)* | 400 | `MALFORMED_REQUEST` |
| Wrong HTTP method | *(HttpRequestMethodNotSupportedException)* | 405 | `METHOD_NOT_ALLOWED` |
| DB unique constraint (race) | *(DataIntegrityViolationException)* | 409 | `DATA_INTEGRITY_VIOLATION` |
| URL doesn't exist | *(NoResourceFoundException)* | 404 | `ENDPOINT_NOT_FOUND` |
| Anything else unexpected | *(catch-all Exception handler)* | 500 | `INTERNAL_SERVER_ERROR` |

---

## 7. Exception Handling Best Practices Checklist

| Practice | Status | Notes |
|----------|--------|-------|
| Consistent JSON error format | ✅ | `ErrorResponse` / `ValidationErrorResponse` everywhere |
| Machine-readable error codes | ✅ | `ErrorCodeEnum` in every response |
| Both exception zones covered | ✅ | `GlobalExceptionHandler` (Zone 1) + `SecurityExceptionHandler` (Zone 2) |
| No information leakage | ✅ | Generic messages for 401 and 500; SQL errors never exposed |
| Custom exceptions extend RuntimeException | ✅ | Compatible with `@Transactional` rollback |
| Log levels appropriate | ✅ | DEBUG for expected, WARN for suspicious, ERROR for bugs |
| Catch-all handler present | ✅ | No exception returns a stack trace to the client |
| Validation errors include field details | ✅ | `ValidationErrorResponse` with `fieldErrors` list |
| Duplicate checks before DB save | ✅ | `existsByUsername`/`existsByEmail` for clear messages |
| Handler ordering explicit | ✅ | `@Order(Ordered.HIGHEST_PRECEDENCE)` |
| Separate `RESOURCE_NOT_FOUND` from `ENDPOINT_NOT_FOUND` | ✅ | Different 404 semantics |

---

## 8. Common Mistakes to Avoid

**1. Forgetting the Security Exception Handler**
If you only build a `GlobalExceptionHandler`, your 401/403 responses from the filter chain will use Spring Security's default format (HTML or minimal JSON). Your Angular frontend will break because it expects `ErrorResponse`.

**2. Catching `Exception` in your service layer**
Don't write `try { ... } catch (Exception e) { ... }` in services. Let exceptions propagate to the `GlobalExceptionHandler` — that's its job. The only exception is when you're translating one exception to another (e.g., `BadCredentialsException` → `InvalidCredentialsException` in `AuthenticationService`).

**3. Exposing internal details in error messages**
Never return stack traces, SQL queries, class names, or file paths in error responses. The `DataIntegrityViolationException` handler is a perfect example — we log the SQL cause at ERROR level for debugging but return a generic message to the client.

**4. Using checked exceptions for business errors**
If your custom exceptions extended `Exception` instead of `RuntimeException`, `@Transactional` would NOT roll back on them (by default). You'd need `@Transactional(rollbackFor = YourException.class)` everywhere — easy to forget, hard to debug.

**5. Not distinguishing between validation types**
Bean Validation (`@NotBlank`, `@Size`) and business validation ("order exceeds limit") are fundamentally different. One is field-level (the frontend highlights the input), the other is cross-cutting (the frontend shows a toast or banner). Using different response shapes (`ValidationErrorResponse` vs `ErrorResponse`) makes this clear to the frontend.

**6. Returning 200 OK with an error body**
Some developers return `200 OK` with `"success": false` in the body. This breaks HTTP semantics and makes frontend error handling harder. Use proper HTTP status codes — that's what they're for.

**7. Creating too many or too few custom exceptions**
Don't create `ProductNotFoundException`, `UserNotFoundException`, `OrderNotFoundException` — use `ResourceNotFoundException` with the entity name as a parameter. But also don't use a single generic `AppException` for everything — the `GlobalExceptionHandler` needs distinct types to map to different HTTP statuses.

**8. Name collision with Spring's `AccessDeniedException`**
Our custom `AccessDeniedException` has the same simple name as Spring Security's `org.springframework.security.access.AccessDeniedException`. In the `GlobalExceptionHandler`, we handle both — but the Spring Security version uses its fully qualified name to avoid ambiguity. If you import the wrong one, you'll catch the wrong exception.

---

## Project File Structure

```
src/main/java/org/viators/orderprocessingsystem/
│
├── exceptions/
│   ├── BaseException.java                         abstract RuntimeException
│   ├── ResourceNotFoundException.java             404
│   ├── DuplicateResourceException.java            409
│   ├── BusinessValidationException.java           400
│   ├── InvalidCredentialsException.java           401
│   ├── AccessDeniedException.java                 403 (custom, not Spring's)
│   ├── InvalidStateException.java                 409
│   ├── ErrorCodeEnum.java                         enum
│   ├── dto/
│   │   ├── ErrorResponse.java                     record
│   │   ├── ValidationErrorResponse.java           record
│   │   └── FieldError.java                        record
│   └── handler/
│       └── GlobalExceptionHandler.java            @RestControllerAdvice
│
├── auth/
│   └── SecurityExceptionHandler.java              @Component (filter-level)
│
└── ...
```
