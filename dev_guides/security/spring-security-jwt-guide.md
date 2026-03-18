# Spring Security 7 + JWT Authentication Guide

## For the Order Processing System — Spring Boot 4 / Java 25

---

## Table of Contents

1. [Understanding the Big Picture](#1-understanding-the-big-picture)
2. [How JWT Authentication Works (The Flow)](#2-how-jwt-authentication-works-the-flow)
3. [JWT Structure Deep Dive](#3-jwt-structure-deep-dive)
4. [Maven Dependencies](#4-maven-dependencies)
5. [Application Configuration](#5-application-configuration)
6. [Implementation: Layer by Layer](#6-implementation-layer-by-layer)
   - 6.1 [UserT Entity Updates](#61-usert-entity-updates)
   - 6.2 [UserRepository Updates](#62-userrepository-updates)
   - 6.3 [UserDetailsService Implementation](#63-userdetailsservice-implementation)
   - 6.4 [JwtService — Token Creation & Validation](#64-jwtservice--token-creation--validation)
   - 6.5 [JwtAuthenticationFilter — The Gatekeeper](#65-jwtauthenticationfilter--the-gatekeeper)
   - 6.6 [SecurityConfig — Wiring Everything Together](#66-securityconfig--wiring-everything-together)
   - 6.7 [Authentication DTOs](#67-authentication-dtos)
   - 6.8 [AuthenticationService — Business Logic](#68-authenticationservice--business-logic)
   - 6.9 [AuthenticationController — REST Endpoints](#69-authenticationcontroller--rest-endpoints)
   - 6.10 [SecurityExceptionHandler — Clean Error Responses](#610-securityexceptionhandler--clean-error-responses)
   - 6.11 [AuditorAware — Completing the Audit Trail](#611-auditoraware--completing-the-audit-trail)
7. [How Everything Connects: Request Lifecycle](#7-how-everything-connects-request-lifecycle)
8. [Testing with cURL](#8-testing-with-curl)
9. [Security Best Practices Checklist](#9-security-best-practices-checklist)
10. [Common Mistakes to Avoid](#10-common-mistakes-to-avoid)

---

## 1. Understanding the Big Picture

Before writing any code, you need to understand **what problem we're solving** and **why JWT is the standard solution for REST APIs**.

### The Problem: Stateless APIs Need Authentication

Traditional web applications use **server-side sessions**: the server stores a session object in memory after login, and the browser sends a session cookie with each request. This works fine for monoliths, but breaks down for REST APIs because:

- **REST is stateless** — each request must be self-contained. The server should not need to remember previous requests.
- **Scalability** — if you have 3 server instances behind a load balancer, a session created on Server A doesn't exist on Server B (unless you set up sticky sessions or a shared session store, which adds complexity).
- **Cross-origin clients** — your Angular 20 frontend, a mobile app, and a third-party integration all need to authenticate. Cookies and sessions are browser-centric and have CORS complications.

### The Solution: Token-Based Authentication with JWT

Instead of the server remembering who you are (sessions), the server **gives you a signed token** after login. You send that token with every subsequent request. The server **verifies the signature** — it doesn't need to "remember" anything.

Think of it like a concert wristband: the security guard at the door doesn't need to remember your face. They just look at your wristband (token), verify it's genuine (signature check), and let you in.

### Where Spring Security Fits

Spring Security is a framework that intercepts every HTTP request and decides: "Should this request be allowed through, or should I reject it?" It does this through a **filter chain** — a pipeline of filters that each request passes through, one by one, before reaching your controller.

Our JWT implementation plugs into this filter chain. We create a custom filter (`JwtAuthenticationFilter`) that:
1. Looks for a JWT token in the `Authorization` header.
2. Validates the token.
3. Tells Spring Security: "This request is from user X, and they have role Y."
4. Spring Security then allows or denies access based on your configuration rules.

---

## 2. How JWT Authentication Works (The Flow)

### Registration Flow
```
Client                          Server
  |                                |
  |  POST /api/v1/auth/register    |
  |  { username, email, password } |
  |------------------------------->|
  |                                |  1. Validate input
  |                                |  2. Check for duplicate username/email
  |                                |  3. Hash password with BCrypt
  |                                |  4. Save user to DB
  |                                |  5. Generate JWT token
  |  { token, user details }       |
  |<-------------------------------|
```

### Login Flow
```
Client                          Server
  |                                |
  |  POST /api/v1/auth/login       |
  |  { username, password }        |
  |------------------------------->|
  |                                |  1. Find user by username
  |                                |  2. Verify password against hash
  |                                |  3. Generate JWT token
  |  { token, user details }       |
  |<-------------------------------|
```

### Authenticated Request Flow
```
Client                          Server
  |                                |
  |  GET /api/v1/products          |
  |  Authorization: Bearer <JWT>   |
  |------------------------------->|
  |                          [JwtAuthenticationFilter]
  |                                |  1. Extract token from header
  |                                |  2. Parse & validate token
  |                                |  3. Extract username from claims
  |                                |  4. Load UserDetails from DB
  |                                |  5. Set Authentication in SecurityContext
  |                          [SecurityFilterChain]
  |                                |  6. Check: is this endpoint permitted?
  |                                |  7. Check: does user have required role?
  |                          [ProductController]
  |                                |  8. Execute business logic
  |  { products response }         |
  |<-------------------------------|
```

---

## 3. JWT Structure Deep Dive

A JWT (JSON Web Token) is a Base64-encoded string made of three parts separated by dots:

```
eyJhbGciOiJIUzI1NiJ9.eyJzdWIiOiJwYW5vcyIsImlhdCI6MTcxNTAwMH0.abc123signature
|______ Header _______|.|__________ Payload ___________|.|____ Signature ____|
```

### Header
```json
{
  "alg": "HS256",     // Signing algorithm (HMAC-SHA256)
  "typ": "JWT"         // Token type
}
```
Tells the receiver *how* to verify the signature.

### Payload (Claims)
```json
{
  "sub": "panos",            // Subject — who this token belongs to
  "iat": 1715000000,         // Issued At — when the token was created (epoch seconds)
  "exp": 1715086400,         // Expiration — when the token expires
  "role": "USER"             // Custom claim — user's role (we add this)
}
```
Contains the actual data. These key-value pairs are called **claims**. There are three types:
- **Registered claims**: predefined by the JWT spec (`sub`, `iat`, `exp`, `iss`, `aud`). Not mandatory but recommended.
- **Public claims**: defined by you, but should avoid collisions (like `role`).
- **Private claims**: custom claims agreed between parties.

> **Important**: The payload is Base64-**encoded**, NOT encrypted. Anyone can decode and read it. Never put sensitive data (passwords, credit card numbers) in the payload. The signature only guarantees the token hasn't been **tampered with** — it does NOT hide the content.

### Signature
```
HMACSHA256(
  base64UrlEncode(header) + "." + base64UrlEncode(payload),
  secret_key
)
```
The server takes the header and payload, and signs them with a **secret key** that only the server knows. When the token comes back in a subsequent request, the server recomputes the signature and compares. If someone tampered with the payload (e.g., changed `"role": "USER"` to `"role": "ADMIN"`), the signature won't match, and the token is rejected.

---

## 4. Maven Dependencies

Add the JJWT library to your `pom.xml`. JJWT (Java JWT) is the most widely used JWT library in the Java ecosystem. It uses a modular design — the `api` module is for compile time, while `impl` and `jackson` are runtime-only (you code against the API, never against internals):

```xml
<!-- JWT Support (JJWT 0.13.0 — latest stable) -->
<dependency>
    <groupId>io.jsonwebtoken</groupId>
    <artifactId>jjwt-api</artifactId>
    <version>0.13.0</version>
</dependency>
<dependency>
    <groupId>io.jsonwebtoken</groupId>
    <artifactId>jjwt-impl</artifactId>
    <version>0.13.0</version>
    <scope>runtime</scope>
</dependency>
<dependency>
    <groupId>io.jsonwebtoken</groupId>
    <artifactId>jjwt-jackson</artifactId>
    <version>0.13.0</version>
    <scope>runtime</scope>
</dependency>
```

**Why three separate JARs?**
- `jjwt-api`: The public interfaces and builders you code against. Compile scope.
- `jjwt-impl`: The actual implementation. Runtime scope so you can never accidentally depend on internal classes, and the JJWT team can change internals without breaking your code.
- `jjwt-jackson`: JSON serialization plugin (JJWT auto-discovers it via classpath). Since Spring Boot already uses Jackson, this integrates naturally.

> You already have `spring-boot-starter-security` in your `pom.xml`, which provides Spring Security 7 — no additional security dependencies needed.

---

## 5. Application Configuration

Add JWT configuration properties to your `application.yaml`:

```yaml
spring:
  application:
    name: order-processing-system

# ── JWT Configuration ──────────────────────────────────────────
application:
  security:
    jwt:
      secret-key: ${JWT_SECRET_KEY}     # Always use environment variable!
      expiration: 86400000              # 24 hours in milliseconds
```

### Why Environment Variables for the Secret Key?

The secret key is the **single most sensitive value** in your entire JWT setup. If someone obtains it, they can forge tokens for any user with any role. Never hardcode it. Use an environment variable (`JWT_SECRET_KEY`) that you set:

- **For development**: In your IDE's run configuration (IntelliJ: Run → Edit Configurations → Environment Variables).
- **For production**: Through your deployment platform's secrets manager (AWS Secrets Manager, Kubernetes Secrets, etc.).

**Generating a secure key** (run this in a terminal):
```bash
openssl rand -base64 64
```
This gives you a 512-bit random key, which is more than sufficient for HMAC-SHA256 (which requires a minimum of 256 bits).

### Configuration Properties Class

Create a type-safe configuration class to bind these YAML values. This is preferable to scattering `@Value` annotations across your codebase because it centralizes all JWT config, provides validation, and is easier to test:

```java
package org.viators.orderprocessingsystem.config;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * Binds JWT-related properties from application.yaml.
 *
 * <p>Properties are prefixed with {@code application.security.jwt}.
 * The secret key should always be provided via environment variable.
 */
@Component
@ConfigurationProperties(prefix = "application.security.jwt")
@Getter
@Setter
public class JwtProperties {

    /**
     * HMAC-SHA secret key used to sign and verify JWT tokens.
     * Must be at least 256 bits (32 bytes) for HS256.
     */
    private String secretKey;

    /**
     * Token expiration time in milliseconds.
     * Default: 86400000 (24 hours).
     */
    private long expiration = 86_400_000;
}
```

**How `@ConfigurationProperties` works**: Spring Boot reads your YAML, finds everything under the `application.security.jwt` prefix, and maps each key to a field in this class by name. `secret-key` in YAML maps to `secretKey` in Java (Spring handles the kebab-case to camelCase conversion). The `@Component` annotation registers it as a Spring bean so other classes can inject it.

---

## 6. Implementation: Layer by Layer

### 6.1 UserT Entity Updates

Your `UserT` entity needs to implement Spring Security's `UserDetails` interface. This is how Spring Security understands your user — it expects a specific contract for retrieving username, password, authorities, and account status.

**Why implement `UserDetails` directly on the entity?** The alternative is creating a separate `CustomUserDetails` wrapper class. Both are valid, but implementing it directly is simpler for most projects and avoids an unnecessary layer of indirection. If your user model later becomes complex (multiple authentication sources, etc.), you can refactor to a wrapper.

```java
package org.viators.orderprocessingsystem.user;

import jakarta.persistence.*;
import lombok.*;
import lombok.experimental.SuperBuilder;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.userdetails.UserDetails;
import org.viators.orderprocessingsystem.common.BaseEntity;
import org.viators.orderprocessingsystem.common.enums.StatusEnum;
import org.viators.orderprocessingsystem.common.enums.UserRolesEnum;

import java.util.Collection;
import java.util.List;

@Entity
@Table(name = "user")
@Getter
@Setter
@ToString(callSuper = true)
@NoArgsConstructor
@AllArgsConstructor
@SuperBuilder
public class UserT extends BaseEntity implements UserDetails {

    @Column(name = "username", nullable = false, unique = true, length = 50)
    private String username;

    @Column(name = "email", nullable = false, unique = true)
    private String email;

    @Column(name = "firstname")
    private String firstName;

    @Column(name = "lastname")
    private String lastName;

    @Column(name = "password", nullable = false)
    private String password;

    @Column(name = "age")
    private Integer age;

    @Enumerated(EnumType.STRING)
    @Column(name = "user_role", nullable = false)
    @Builder.Default
    private UserRolesEnum userRole = UserRolesEnum.CUSTOMER;

    // ── UserDetails Implementation ────────────────────────────────

    /**
     * Returns the authorities (roles/permissions) granted to this user.
     *
     * <p>Spring Security uses this to check authorization (e.g., hasRole("ADMIN")).
     * We prefix with "ROLE_" because Spring Security's hasRole() method
     * automatically adds this prefix when checking. So hasRole("ADMIN")
     * actually checks for authority "ROLE_ADMIN".
     *
     * @return a singleton list containing the user's role as a GrantedAuthority
     */
    @Override
    public Collection<? extends GrantedAuthority> getAuthorities() {
        return List.of(new SimpleGrantedAuthority("ROLE_" + userRole.name()));
    }

    /**
     * Indicates whether the user's account is active (not soft-deleted).
     *
     * <p>We tie this to our existing StatusEnum. If the status is INACTIVE
     * (soft-deleted), Spring Security will reject authentication even if
     * the password is correct.
     *
     * @return true if the user's status is ACTIVE
     */
    @Override
    public boolean isEnabled() {
        return StatusEnum.ACTIVE.equals(getStatus());
    }

    // The following methods default to 'true' via the UserDetails interface
    // in Spring Security 7. They are shown here for documentation:
    //
    // isAccountNonExpired()  → true (we don't track account expiry)
    // isAccountNonLocked()   → true (we don't track account locking)
    // isCredentialsNonExpired() → true (we don't track password expiry)
    //
    // Override them if you add account locking or password expiry features later.

    // ── Helper Methods ────────────────────────────────────────────

    /** Checks if this user has the ADMIN role. */
    public boolean isAdminUser() {
        return UserRolesEnum.ADMIN.equals(this.userRole);
    }
}
```

**Key decisions explained**:

- **`getAuthorities()`**: Returns a list of `GrantedAuthority` objects. We have a single role per user, so we return a singleton list. The `"ROLE_"` prefix is a Spring Security convention — when you write `hasRole("ADMIN")` in your security config, Spring internally checks for `"ROLE_ADMIN"`.

- **`isEnabled()`**: We map this to your existing `StatusEnum`. This means soft-deleted users (`INACTIVE`) are automatically locked out of authentication without any additional code in your login logic.

- **Why not override `isAccountNonExpired()`, etc.?** In Spring Security 7, the `UserDetails` interface provides default implementations that return `true`. Since we don't currently need account locking or credential expiry, we rely on the defaults. Override them later if you add those features.

---

### 6.2 UserRepository Updates

Add a method to look up users by username, which is what Spring Security needs during authentication:

```java
package org.viators.orderprocessingsystem.user;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;
import org.viators.orderprocessingsystem.common.enums.StatusEnum;

import java.util.Optional;

@Repository
public interface UserRepository extends JpaRepository<UserT, Long> {

    Optional<UserT> findByUuidAndStatus(String userUuid, StatusEnum status);

    /**
     * Finds a user by their username.
     * Used by Spring Security's UserDetailsService during authentication.
     *
     * @param username the username to search for
     * @return the user if found, empty otherwise
     */
    Optional<UserT> findByUsername(String username);

    /**
     * Checks if a username is already taken.
     * Used during registration to provide a clear error message
     * instead of letting the database throw a constraint violation.
     *
     * @param username the username to check
     * @return true if the username already exists
     */
    boolean existsByUsername(String username);

    /**
     * Checks if an email is already registered.
     *
     * @param email the email to check
     * @return true if the email already exists
     */
    boolean existsByEmail(String email);
}
```

**Why both `existsBy` and `findBy` methods?** They serve different purposes. `findByUsername` loads the full entity for authentication. `existsByUsername`/`existsByEmail` are lightweight checks (they generate `SELECT count(*)` or `SELECT 1` queries) used during registration to give user-friendly error messages before attempting an insert.

---

### 6.3 UserDetailsService Implementation

This is the bridge between Spring Security and your database. When Spring Security needs to authenticate a user, it asks the `UserDetailsService`: "Give me the `UserDetails` for this username."

```java
package org.viators.orderprocessingsystem.auth;

import lombok.RequiredArgsConstructor;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.viators.orderprocessingsystem.user.UserRepository;

/**
 * Loads user-specific data for Spring Security authentication.
 *
 * <p>This service is automatically discovered by Spring Security because
 * it implements {@link UserDetailsService}. When there's exactly one
 * bean of this type in the application context, Spring Security uses it
 * as the default user lookup mechanism.
 *
 * <p>Because {@link org.viators.orderprocessingsystem.user.UserT} implements
 * {@link UserDetails} directly, we can return the entity as-is — no
 * mapping or wrapping needed.
 */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class CustomUserDetailsService implements UserDetailsService {

    private final UserRepository userRepository;

    /**
     * Locates the user based on the username.
     *
     * <p>Called by Spring Security's {@code AuthenticationManager} during
     * the authentication process (login). Also called by our
     * {@code JwtAuthenticationFilter} on every authenticated request
     * to reconstruct the user's security context.
     *
     * @param username the username identifying the user whose data is required
     * @return a fully populated UserDetails object (our UserT entity)
     * @throws UsernameNotFoundException if no user is found with the given username
     */
    @Override
    public UserDetails loadUserByUsername(String username) throws UsernameNotFoundException {
        return userRepository.findByUsername(username)
                .orElseThrow(() -> new UsernameNotFoundException(
                        "User not found with username: " + username
                ));
    }
}
```

**Why `UsernameNotFoundException`?** This is a Spring Security-specific exception. Spring Security's `AuthenticationManager` catches it and translates it to a `BadCredentialsException` (which then becomes a 401 response). You should NOT throw your custom `ResourceNotFoundException` here — Spring Security won't know what to do with it.

**Why `@Transactional(readOnly = true)`?** This tells Hibernate that no write operations will happen, which allows it to skip dirty-checking and flush operations. It's a performance optimization and a declaration of intent.

---

### 6.4 JwtService — Token Creation & Validation

This is the core of JWT handling. It's responsible for:
- **Creating tokens** (after successful authentication).
- **Validating tokens** (on every subsequent request).
- **Extracting claims** (pulling user information out of the token).

```java
package org.viators.orderprocessingsystem.auth;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.ExpiredJwtException;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.stereotype.Service;
import org.viators.orderprocessingsystem.config.JwtProperties;

import javax.crypto.SecretKey;
import java.util.Base64;
import java.util.Date;
import java.util.Map;

/**
 * Service for creating, parsing, and validating JWT tokens.
 *
 * <p>Uses the JJWT library (0.13.0) with HMAC-SHA256 signing.
 * The signing key is derived from a Base64-encoded secret configured
 * in application.yaml (sourced from an environment variable).
 *
 * <p><strong>Thread Safety:</strong> This service is stateless after
 * initialization and safe for concurrent use. The {@link SecretKey}
 * is immutable and the JJWT builders create new instances per call.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class JwtService {

    private final JwtProperties jwtProperties;

    /**
     * The cryptographic key used for signing and verifying tokens.
     * Initialized once at startup from the configured secret.
     */
    private SecretKey signingKey;

    /**
     * Initializes the signing key from the configured secret.
     *
     * <p>{@code @PostConstruct} runs once after dependency injection is complete.
     * We decode the Base64-encoded secret and create an HMAC-SHA key.
     * This avoids recreating the key on every token operation.
     *
     * <p><strong>Why Base64?</strong> The secret in application.yaml is stored
     * as a Base64 string (produced by {@code openssl rand -base64 64}).
     * We decode it here to get the raw bytes that HMAC needs.
     */
    @PostConstruct
    private void init() {
        byte[] keyBytes = Base64.getDecoder().decode(jwtProperties.getSecretKey());
        this.signingKey = Keys.hmacShaKeyFor(keyBytes);
    }

    /**
     * Generates a JWT token for an authenticated user.
     *
     * <p>The token contains:
     * <ul>
     *   <li>{@code sub} (subject): the username — used to identify the user</li>
     *   <li>{@code iat} (issued at): current timestamp</li>
     *   <li>{@code exp} (expiration): current time + configured expiration</li>
     *   <li>Any extra claims passed in the {@code extraClaims} map</li>
     * </ul>
     *
     * <p><strong>Why accept a {@code Map} for extra claims?</strong> This gives
     * flexibility to include role information, user UUID, or other metadata
     * in the token without modifying this method's signature.
     *
     * @param extraClaims additional claims to include (e.g., role, uuid)
     * @param userDetails the authenticated user
     * @return a signed JWT token string
     */
    public String generateToken(Map<String, Object> extraClaims, UserDetails userDetails) {
        long now = System.currentTimeMillis();

        return Jwts.builder()
                .claims(extraClaims)
                .subject(userDetails.getUsername())
                .issuedAt(new Date(now))
                .expiration(new Date(now + jwtProperties.getExpiration()))
                .signWith(signingKey)
                .compact();
    }

    /**
     * Generates a JWT token with no extra claims.
     *
     * <p>Convenience overload for the common case where you only need
     * the standard claims (subject, issued-at, expiration).
     *
     * @param userDetails the authenticated user
     * @return a signed JWT token string
     */
    public String generateToken(UserDetails userDetails) {
        return generateToken(Map.of(), userDetails);
    }

    /**
     * Extracts the username (subject claim) from a token.
     *
     * <p>This is used by the {@code JwtAuthenticationFilter} to identify
     * which user a request belongs to, so it can load their full
     * {@code UserDetails} from the database.
     *
     * @param token the JWT token string
     * @return the username stored in the token's subject claim
     * @throws JwtException if the token is invalid, expired, or tampered with
     */
    public String extractUsername(String token) {
        return extractAllClaims(token).getSubject();
    }

    /**
     * Validates a JWT token against a UserDetails object.
     *
     * <p>Validation checks two things:
     * <ol>
     *   <li>The username in the token matches the UserDetails username</li>
     *   <li>The token has not expired</li>
     * </ol>
     *
     * <p><strong>Why check the username match?</strong> This prevents a scenario
     * where User A's valid token is somehow used with User B's UserDetails.
     * It's a defense-in-depth measure.
     *
     * <p>Note: signature verification happens implicitly in {@code extractAllClaims()}
     * — JJWT throws a {@code SignatureException} if the signature doesn't match.
     *
     * @param token       the JWT token to validate
     * @param userDetails the user to validate against
     * @return true if the token is valid for the given user
     */
    public boolean isTokenValid(String token, UserDetails userDetails) {
        try {
            String username = extractUsername(token);
            return username.equals(userDetails.getUsername()) && !isTokenExpired(token);
        } catch (JwtException e) {
            log.debug("Token validation failed: {}", e.getMessage());
            return false;
        }
    }

    /**
     * Checks if a token has expired.
     *
     * @param token the JWT token
     * @return true if the expiration date is before the current time
     */
    private boolean isTokenExpired(String token) {
        return extractAllClaims(token)
                .getExpiration()
                .before(new Date());
    }

    /**
     * Parses the token and extracts all claims.
     *
     * <p>This is where the actual cryptographic verification happens.
     * JJWT will:
     * <ol>
     *   <li>Base64-decode the header, payload, and signature</li>
     *   <li>Recompute the signature using our {@code signingKey}</li>
     *   <li>Compare the computed signature with the token's signature</li>
     *   <li>Check that the token hasn't expired</li>
     * </ol>
     *
     * <p>If any check fails, JJWT throws a specific exception:
     * <ul>
     *   <li>{@link io.jsonwebtoken.security.SignatureException} — tampered token</li>
     *   <li>{@link ExpiredJwtException} — token past its expiration</li>
     *   <li>{@link io.jsonwebtoken.MalformedJwtException} — not a valid JWT format</li>
     * </ul>
     *
     * @param token the JWT token string
     * @return the parsed claims (payload data)
     * @throws JwtException if the token is invalid for any reason
     */
    private Claims extractAllClaims(String token) {
        return Jwts.parser()
                .verifyWith(signingKey)
                .build()
                .parseSignedClaims(token)
                .getPayload();
    }
}
```

**Key concepts to internalize**:

- **`Jwts.builder()`** creates a new token. It's a fluent API: set claims, sign, and compact into a string.
- **`Jwts.parser()`** validates and reads a token. The `.verifyWith(signingKey)` step is critical — it tells JJWT which key to use for signature verification.
- **`parseSignedClaims()`** is the JJWT 0.13.0 method (replaces the older `parseClaimsJws()`). It returns a `Jws<Claims>` object; calling `.getPayload()` gives you the claims map.
- **Why `@PostConstruct`?** The signing key only needs to be derived once. Computing HMAC keys is cheap, but there's no reason to redo it on every request. `@PostConstruct` runs after the constructor and dependency injection are complete — it's Spring's lifecycle hook for initialization logic.

---

### 6.5 JwtAuthenticationFilter — The Gatekeeper

This is the most important class in the entire security setup. It's a **servlet filter** that intercepts every HTTP request *before* it reaches your controllers. Its job: check if the request carries a valid JWT, and if so, tell Spring Security who the user is.

```java
package org.viators.orderprocessingsystem.auth;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.lang.NonNull;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.web.authentication.WebAuthenticationDetailsSource;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

/**
 * JWT authentication filter that intercepts every HTTP request.
 *
 * <p>Extends {@link OncePerRequestFilter} to guarantee single execution
 * per request, even if the request is forwarded internally (e.g., by
 * Spring's error handling mechanism or dispatcher servlet).
 *
 * <p><strong>Position in the filter chain:</strong> This filter is registered
 * BEFORE Spring Security's {@code UsernamePasswordAuthenticationFilter}.
 * This means JWT authentication is attempted first. If no token is present,
 * the request continues down the chain and Spring Security handles it
 * according to the configured rules (permit or reject).
 *
 * <p><strong>Flow per request:</strong>
 * <pre>
 * 1. Extract "Authorization" header
 * 2. If no header or doesn't start with "Bearer " → skip, pass to next filter
 * 3. Extract JWT token (everything after "Bearer ")
 * 4. Parse token to get username
 * 5. If username found AND no authentication exists yet in SecurityContext:
 *    a. Load UserDetails from database
 *    b. Validate token against UserDetails
 *    c. If valid → create Authentication object and set in SecurityContext
 * 6. Pass request to next filter in chain
 * </pre>
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class JwtAuthenticationFilter extends OncePerRequestFilter {

    private final JwtService jwtService;
    private final UserDetailsService userDetailsService;

    @Override
    protected void doFilterInternal(
            @NonNull HttpServletRequest request,
            @NonNull HttpServletResponse response,
            @NonNull FilterChain filterChain
    ) throws ServletException, IOException {

        // ── Step 1: Extract the Authorization header ──────────────
        String authHeader = request.getHeader("Authorization");

        // If there's no Authorization header, or it doesn't start with "Bearer ",
        // this request doesn't have JWT authentication. Pass it along.
        // It might be a public endpoint (like /api/v1/auth/login) that doesn't
        // need authentication, or it might get rejected by Spring Security later.
        if (authHeader == null || !authHeader.startsWith("Bearer ")) {
            filterChain.doFilter(request, response);
            return;
        }

        // ── Step 2: Extract the token ─────────────────────────────
        // "Bearer eyJhbGciOiJIUzI1NiJ9..." → "eyJhbGciOiJIUzI1NiJ9..."
        String jwt = authHeader.substring(7);

        try {
            // ── Step 3: Extract username from token ───────────────
            String username = jwtService.extractUsername(jwt);

            // ── Step 4: Authenticate if not already authenticated ─
            // SecurityContextHolder.getContext().getAuthentication() == null
            // ensures we don't re-authenticate if something else already did.
            // This is a guard against redundant work.
            if (username != null
                    && SecurityContextHolder.getContext().getAuthentication() == null) {

                // ── Step 5: Load full user details from database ──
                UserDetails userDetails = userDetailsService.loadUserByUsername(username);

                // ── Step 6: Validate token ────────────────────────
                if (jwtService.isTokenValid(jwt, userDetails)) {

                    // ── Step 7: Create authentication token ───────
                    // UsernamePasswordAuthenticationToken is Spring Security's
                    // standard Authentication implementation. The 3-argument
                    // constructor marks it as "authenticated".
                    //
                    // Arguments:
                    // - principal: the user (UserDetails object)
                    // - credentials: null (we already validated via JWT, no need
                    //                 to carry the password around)
                    // - authorities: the user's roles/permissions
                    var authToken = new UsernamePasswordAuthenticationToken(
                            userDetails,
                            null,
                            userDetails.getAuthorities()
                    );

                    // Attach request-specific details (remote IP, session ID, etc.)
                    // Used for auditing and logging purposes.
                    authToken.setDetails(
                            new WebAuthenticationDetailsSource().buildDetails(request)
                    );

                    // ── Step 8: Set authentication in SecurityContext ─
                    // This is THE critical line. After this, Spring Security
                    // considers this request authenticated. All downstream
                    // filters, @PreAuthorize annotations, and SecurityContext
                    // lookups will see this user as the authenticated principal.
                    SecurityContextHolder.getContext().setAuthentication(authToken);

                    log.debug("Authenticated user '{}' for request: {} {}",
                            username, request.getMethod(), request.getRequestURI());
                }
            }
        } catch (Exception e) {
            // Token parsing/validation failed. Don't throw — just log and continue.
            // Spring Security will handle the unauthenticated request downstream
            // (either permitting it for public endpoints or rejecting with 401).
            log.debug("JWT authentication failed: {}", e.getMessage());
        }

        // ── Always continue the filter chain ─────────────────────
        // Whether authentication succeeded or failed, the request must
        // continue through the remaining filters. Spring Security's
        // authorization filters will check the SecurityContext and decide
        // whether to allow or deny access.
        filterChain.doFilter(request, response);
    }
}
```

**Critical concepts**:

- **`OncePerRequestFilter`**: A Spring-provided base class that guarantees the filter runs exactly once per request. Without this, internal forwards (like error page routing) could trigger the filter again.

- **`SecurityContextHolder`**: Think of it as a thread-local storage for the current user's authentication. Spring Security stores and retrieves the `Authentication` object here. It's "thread-local" because each HTTP request is handled by a separate thread, so each thread has its own SecurityContext.

- **`UsernamePasswordAuthenticationToken`**: Despite the name, it's not only for username/password auth. It's Spring Security's general-purpose `Authentication` implementation. The 3-argument constructor (`principal, credentials, authorities`) creates a pre-authenticated token (`.isAuthenticated()` returns `true`).

- **Why `credentials: null`?** We already verified the user's identity via the JWT signature. Carrying the actual password around in memory would be a security risk with no benefit.

- **Why catch `Exception` here?** Normally we avoid catching generic exceptions. But in a filter, an uncaught exception would break the filter chain and potentially return an ugly server error. We want invalid tokens to be treated as "no authentication" — the request continues, and Spring Security decides whether the target endpoint allows anonymous access.

---

### 6.6 SecurityConfig — Wiring Everything Together

This is where you define the security rules for your entire application. Which endpoints are public? Which require authentication? Which require specific roles?

**Spring Security 7 Important Note**: The Lambda DSL is now **mandatory** (the old `.and()` chaining style has been removed). This is the modern, required syntax.

```java
package org.viators.orderprocessingsystem.config;

import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.AuthenticationProvider;
import org.springframework.security.authentication.dao.DaoAuthenticationProvider;
import org.springframework.security.config.annotation.authentication.configuration.AuthenticationConfiguration;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.viators.orderprocessingsystem.auth.JwtAuthenticationFilter;

/**
 * Central security configuration for the application.
 *
 * <p>Defines the {@link SecurityFilterChain}, authentication provider,
 * password encoder, and the position of the JWT filter in the chain.
 *
 * <p><strong>Spring Security 7 Notes:</strong>
 * <ul>
 *   <li>Lambda DSL is mandatory (the {@code .and()} chaining style is removed)</li>
 *   <li>{@code @EnableMethodSecurity} replaces the deprecated
 *       {@code @EnableGlobalMethodSecurity}</li>
 *   <li>{@code PathPatternRequestMatcher} is now the default
 *       (replaces AntPathRequestMatcher)</li>
 * </ul>
 */
@Configuration
@EnableWebSecurity
@EnableMethodSecurity
@RequiredArgsConstructor
public class SecurityConfig {

    private final JwtAuthenticationFilter jwtAuthenticationFilter;
    private final UserDetailsService userDetailsService;

    /**
     * Configures the HTTP security filter chain.
     *
     * <p>This is the most important bean in the security setup. It defines:
     * <ol>
     *   <li>Which endpoints are public vs. protected</li>
     *   <li>Session management policy (stateless for JWT)</li>
     *   <li>Where our JWT filter sits in the filter chain</li>
     *   <li>Which authentication provider to use</li>
     * </ol>
     *
     * <p><strong>How the filter chain works:</strong><br>
     * Every HTTP request passes through a chain of security filters.
     * Spring Security provides ~15 built-in filters (CSRF, CORS, logout,
     * session management, etc.). We insert our JWT filter before the
     * {@code UsernamePasswordAuthenticationFilter} so JWT tokens are
     * checked before Spring tries form-based authentication.
     *
     * @param http the HttpSecurity builder
     * @return the configured SecurityFilterChain
     */
    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
        http
                // ── CSRF Protection ───────────────────────────────
                // Disabled because we use JWT (stateless). CSRF attacks
                // exploit server-side sessions via cookies. Since we don't
                // use sessions or cookies for auth, CSRF is not a threat.
                // The token in the Authorization header provides equivalent
                // protection since it can't be automatically attached by
                // the browser (unlike cookies).
                .csrf(AbstractHttpConfigurer::disable)

                // ── Authorization Rules ───────────────────────────
                // Order matters! More specific rules must come first.
                // Spring Security evaluates rules top-to-bottom and uses
                // the first match.
                .authorizeHttpRequests(auth -> auth
                        // Public endpoints — no authentication required
                        .requestMatchers("/api/v1/auth/**").permitAll()

                        // Health check / actuator endpoints (if you add them later)
                        .requestMatchers("/actuator/health").permitAll()

                        // Admin-only endpoints
                        .requestMatchers("/api/v1/admin/**").hasRole("ADMIN")

                        // Everything else requires authentication
                        .anyRequest().authenticated()
                )

                // ── Session Management ────────────────────────────
                // STATELESS means Spring Security will NEVER create or
                // use an HttpSession. Each request must carry its own
                // authentication (via JWT). This is essential for REST APIs.
                .sessionManagement(session ->
                        session.sessionCreationPolicy(SessionCreationPolicy.STATELESS)
                )

                // ── Authentication Provider ───────────────────────
                // Tells Spring Security HOW to authenticate users
                // (using our UserDetailsService + BCrypt password encoder)
                .authenticationProvider(authenticationProvider())

                // ── JWT Filter Position ───────────────────────────
                // Insert our JWT filter BEFORE UsernamePasswordAuthenticationFilter.
                // This ensures JWT authentication is attempted first.
                .addFilterBefore(
                        jwtAuthenticationFilter,
                        UsernamePasswordAuthenticationFilter.class
                );

        return http.build();
    }

    /**
     * Configures the authentication provider that Spring Security uses
     * to verify credentials during login.
     *
     * <p>{@link DaoAuthenticationProvider} is Spring's built-in provider
     * that authenticates by loading a user from a data source (via
     * {@link UserDetailsService}) and comparing the submitted password
     * against the stored hash (via {@link PasswordEncoder}).
     *
     * <p><strong>Authentication flow during login:</strong>
     * <pre>
     * 1. User submits { username, password }
     * 2. AuthenticationManager delegates to this provider
     * 3. Provider calls userDetailsService.loadUserByUsername(username)
     * 4. Provider calls passwordEncoder.matches(submittedPwd, storedHash)
     * 5. If match → returns authenticated Authentication object
     * 6. If no match → throws BadCredentialsException
     * </pre>
     *
     * @return the configured DaoAuthenticationProvider
     */
    @Bean
    public AuthenticationProvider authenticationProvider() {
        var authProvider = new DaoAuthenticationProvider();
        authProvider.setUserDetailsService(userDetailsService);
        authProvider.setPasswordEncoder(passwordEncoder());
        return authProvider;
    }

    /**
     * Exposes the {@link AuthenticationManager} as a bean.
     *
     * <p>The AuthenticationManager is the entry point for authentication.
     * It delegates to the configured {@link AuthenticationProvider}(s).
     * We need it as a bean so we can inject it into our
     * {@code AuthenticationService} for the login endpoint.
     *
     * <p>Spring Boot auto-configures an AuthenticationManager, but to
     * inject it, we need to explicitly expose it via this method.
     *
     * @param config the AuthenticationConfiguration provided by Spring
     * @return the AuthenticationManager
     */
    @Bean
    public AuthenticationManager authenticationManager(AuthenticationConfiguration config) throws Exception {
        return config.getAuthenticationManager();
    }

    /**
     * Configures BCrypt as the password encoder.
     *
     * <p>BCrypt is a deliberately slow hashing algorithm designed for
     * passwords. It includes:
     * <ul>
     *   <li>A random salt per password (prevents rainbow table attacks)</li>
     *   <li>A configurable work factor (default: 10 = 2^10 = 1024 rounds)</li>
     *   <li>Future-proofing — you can increase the work factor as hardware improves</li>
     * </ul>
     *
     * <p>The default strength of 10 is appropriate for most applications.
     * Increasing to 12 or higher makes hashing slower (more secure but
     * impacts login response time).
     *
     * @return a BCryptPasswordEncoder with default strength
     */
    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }
}
```

**Concepts that need deep understanding**:

- **Why disable CSRF?** CSRF (Cross-Site Request Forgery) attacks work by tricking a browser into sending a request with automatically attached cookies. Since JWT tokens live in the `Authorization` header (not cookies), the browser can't automatically attach them. The Angular frontend must explicitly add the header to each request, which a CSRF attack can't do.

- **`SessionCreationPolicy.STATELESS`**: This is critical. Without this, Spring Security creates an `HttpSession` for each authenticated request, which defeats the purpose of JWT. STATELESS means "never create a session, never use a session."

- **`addFilterBefore`**: Positions our filter in the chain. The order is: our JwtFilter → UsernamePasswordAuthenticationFilter → ... → AuthorizationFilter → Controller. If JWT auth succeeds, the downstream filters see an authenticated SecurityContext.

- **`@EnableMethodSecurity`**: Enables `@PreAuthorize`, `@PostAuthorize`, and `@Secured` annotations on your service/controller methods. This gives you fine-grained authorization beyond URL patterns (e.g., `@PreAuthorize("hasRole('ADMIN') or #uuid == authentication.name")`).

---

### 6.7 Authentication DTOs

Request and response DTOs for the authentication endpoints. Using records for immutability:

```java
package org.viators.orderprocessingsystem.auth.dto.request;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * Request body for user registration.
 *
 * <p>Uses Bean Validation annotations to enforce constraints.
 * These are checked by Spring's {@code @Valid} annotation on
 * the controller parameter before the method body executes.
 */
public record RegisterRequest(

        @NotBlank(message = "Username is required")
        @Size(min = 3, max = 50, message = "Username must be between 3 and 50 characters")
        String username,

        @NotBlank(message = "Email is required")
        @Email(message = "Email must be a valid email address")
        String email,

        @NotBlank(message = "Password is required")
        @Size(min = 8, max = 100, message = "Password must be between 8 and 100 characters")
        String password,

        String firstName,

        String lastName,

        Integer age
) {}
```

```java
package org.viators.orderprocessingsystem.auth.dto.request;

import jakarta.validation.constraints.NotBlank;

/**
 * Request body for user login.
 */
public record LoginRequest(

        @NotBlank(message = "Username is required")
        String username,

        @NotBlank(message = "Password is required")
        String password
) {}
```

```java
package org.viators.orderprocessingsystem.auth.dto.response;

/**
 * Response returned after successful authentication (login or register).
 *
 * <p>Contains the JWT token that the client must include in subsequent
 * requests via the {@code Authorization: Bearer <token>} header.
 */
public record AuthenticationResponse(
        String token,
        String uuid,
        String username,
        String email,
        String role
) {}
```

---

### 6.8 AuthenticationService — Business Logic

This service handles the registration and login business logic:

```java
package org.viators.orderprocessingsystem.auth;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.viators.orderprocessingsystem.auth.dto.request.LoginRequest;
import org.viators.orderprocessingsystem.auth.dto.request.RegisterRequest;
import org.viators.orderprocessingsystem.auth.dto.response.AuthenticationResponse;
import org.viators.orderprocessingsystem.exceptions.DuplicateResourceException;
import org.viators.orderprocessingsystem.exceptions.InvalidCredentialsException;
import org.viators.orderprocessingsystem.user.UserRepository;
import org.viators.orderprocessingsystem.user.UserT;

import java.util.Map;

/**
 * Handles user registration and login business logic.
 *
 * <p>Orchestrates between the repository, password encoder,
 * authentication manager, and JWT service to provide a complete
 * authentication flow.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class AuthenticationService {

    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;
    private final JwtService jwtService;
    private final AuthenticationManager authenticationManager;

    /**
     * Registers a new user and returns a JWT token.
     *
     * <p><strong>Flow:</strong>
     * <ol>
     *   <li>Check for duplicate username and email (fail fast)</li>
     *   <li>Hash the password with BCrypt</li>
     *   <li>Save the user to the database</li>
     *   <li>Generate a JWT token for immediate login</li>
     * </ol>
     *
     * <p><strong>Why check duplicates before saving?</strong> The database
     * has unique constraints on username and email, so it would eventually
     * reject duplicates. But checking first lets us return a clear,
     * user-friendly error message instead of a generic constraint violation.
     *
     * @param request the registration request with user details
     * @return authentication response with JWT token
     * @throws DuplicateResourceException if username or email already exists
     */
    @Transactional
    public AuthenticationResponse register(RegisterRequest request) {
        // ── Duplicate checks ──────────────────────────────────────
        if (userRepository.existsByUsername(request.username())) {
            throw new DuplicateResourceException("User", "username", request.username());
        }
        if (userRepository.existsByEmail(request.email())) {
            throw new DuplicateResourceException("User", "email", request.email());
        }

        // ── Build and save user ───────────────────────────────────
        var user = UserT.builder()
                .username(request.username())
                .email(request.email())
                .password(passwordEncoder.encode(request.password()))
                .firstName(request.firstName())
                .lastName(request.lastName())
                .age(request.age())
                .build();

        UserT savedUser = userRepository.save(user);
        log.info("New user registered: {} (uuid: {})", savedUser.getUsername(), savedUser.getUuid());

        // ── Generate token with extra claims ──────────────────────
        String token = jwtService.generateToken(
                Map.of("role", savedUser.getUserRole().name()),
                savedUser
        );

        return buildAuthResponse(savedUser, token);
    }

    /**
     * Authenticates a user and returns a JWT token.
     *
     * <p><strong>Flow:</strong>
     * <ol>
     *   <li>Delegate credential verification to Spring Security's
     *       {@link AuthenticationManager}</li>
     *   <li>If credentials are valid, load the user</li>
     *   <li>Generate and return a JWT token</li>
     * </ol>
     *
     * <p><strong>Why use AuthenticationManager instead of manual checking?</strong>
     * The AuthenticationManager is Spring Security's standard entry point for
     * authentication. It delegates to our configured {@code DaoAuthenticationProvider},
     * which handles:
     * <ul>
     *   <li>Loading UserDetails via UserDetailsService</li>
     *   <li>Password comparison via PasswordEncoder</li>
     *   <li>Account status checks (isEnabled, isAccountNonLocked, etc.)</li>
     * </ul>
     * If we did this manually, we'd bypass these built-in checks and risk
     * missing edge cases (like disabled accounts).
     *
     * @param request the login request with credentials
     * @return authentication response with JWT token
     * @throws InvalidCredentialsException if authentication fails
     */
    @Transactional(readOnly = true)
    public AuthenticationResponse login(LoginRequest request) {
        try {
            // This single line does ALL the heavy lifting:
            // 1. Calls CustomUserDetailsService.loadUserByUsername()
            // 2. Calls BCryptPasswordEncoder.matches(raw, hashed)
            // 3. Checks isEnabled(), isAccountNonLocked(), etc.
            // 4. Throws BadCredentialsException if anything fails
            authenticationManager.authenticate(
                    new UsernamePasswordAuthenticationToken(
                            request.username(),
                            request.password()
                    )
            );
        } catch (BadCredentialsException e) {
            // Translate Spring Security's exception to our custom exception.
            // This lets our GlobalExceptionHandler handle it consistently.
            log.warn("Failed login attempt for username: {}", request.username());
            throw new InvalidCredentialsException();
        }

        // If we reach here, authentication was successful.
        // Load the full user to include in the response.
        UserT user = userRepository.findByUsername(request.username())
                .orElseThrow(InvalidCredentialsException::new);

        String token = jwtService.generateToken(
                Map.of("role", user.getUserRole().name()),
                user
        );

        log.info("User logged in: {}", user.getUsername());
        return buildAuthResponse(user, token);
    }

    /**
     * Builds a standardized authentication response.
     */
    private AuthenticationResponse buildAuthResponse(UserT user, String token) {
        return new AuthenticationResponse(
                token,
                user.getUuid(),
                user.getUsername(),
                user.getEmail(),
                user.getUserRole().name()
        );
    }
}
```

**Important design decisions**:

- **Why `@Transactional` on register but `@Transactional(readOnly = true)` on login?** Registration writes to the database (saves a new user), so it needs a read-write transaction. Login only reads data, so `readOnly = true` optimizes performance.

- **Why re-throw `BadCredentialsException` as `InvalidCredentialsException`?** This keeps your exception handling consistent. Your `GlobalExceptionHandler` already handles `InvalidCredentialsException` with a proper error response. Letting `BadCredentialsException` propagate would require a separate handler for Spring Security exceptions.

- **Security note on error messages**: The `InvalidCredentialsException` says "Invalid credentials provided" — it does NOT say "User not found" or "Wrong password". This is intentional. Telling an attacker whether a username exists helps them enumerate valid accounts.

---

### 6.9 AuthenticationController — REST Endpoints

```java
package org.viators.orderprocessingsystem.auth;

import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.viators.orderprocessingsystem.auth.dto.request.LoginRequest;
import org.viators.orderprocessingsystem.auth.dto.request.RegisterRequest;
import org.viators.orderprocessingsystem.auth.dto.response.AuthenticationResponse;

/**
 * REST controller for authentication endpoints.
 *
 * <p>All endpoints under {@code /api/v1/auth/**} are public
 * (configured in {@link org.viators.orderprocessingsystem.config.SecurityConfig}).
 * This makes sense because you can't require authentication to log in
 * — it would be a chicken-and-egg problem.
 */
@RestController
@RequestMapping("/api/v1/auth")
@RequiredArgsConstructor
public class AuthenticationController {

    private final AuthenticationService authenticationService;

    /**
     * Registers a new user account.
     *
     * <p>Returns 201 Created on success with a JWT token in the response body,
     * allowing the client to immediately use the token without a separate login call.
     *
     * @param request the registration details
     * @return the authentication response with JWT token
     */
    @PostMapping("/register")
    public ResponseEntity<AuthenticationResponse> register(
            @Valid @RequestBody RegisterRequest request) {

        AuthenticationResponse response = authenticationService.register(request);
        return ResponseEntity.status(HttpStatus.CREATED).body(response);
    }

    /**
     * Authenticates a user and issues a JWT token.
     *
     * <p>Returns 200 OK on success with the JWT token.
     * Returns 401 Unauthorized if credentials are invalid.
     *
     * @param request the login credentials
     * @return the authentication response with JWT token
     */
    @PostMapping("/login")
    public ResponseEntity<AuthenticationResponse> login(
            @Valid @RequestBody LoginRequest request) {

        AuthenticationResponse response = authenticationService.login(request);
        return ResponseEntity.ok(response);
    }
}
```

---

### 6.10 SecurityExceptionHandler — Clean Error Responses

Spring Security's default error handling returns HTML or minimal JSON that doesn't match your application's error format. We need custom exception handling for two specific scenarios that happen *before* your controllers are reached (and therefore *before* the `GlobalExceptionHandler` can catch them):

1. **401 Unauthorized** — no valid token (handled by `AuthenticationEntryPoint`)
2. **403 Forbidden** — valid token but insufficient permissions (handled by `AccessDeniedHandler`)

```java
package org.viators.orderprocessingsystem.auth;

import com.fasterxml.jackson.databind.ObjectMapper;
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

import java.io.IOException;

/**
 * Handles security exceptions that occur during the filter chain,
 * BEFORE the request reaches any controller.
 *
 * <p>Implements two interfaces:
 * <ul>
 *   <li>{@link AuthenticationEntryPoint} — handles 401 (missing or invalid token)</li>
 *   <li>{@link AccessDeniedHandler} — handles 403 (authenticated but not authorized)</li>
 * </ul>
 *
 * <p><strong>Why do we need this?</strong> Without it, Spring Security returns
 * its default HTML error page or a minimal JSON body that doesn't match our
 * application's {@link ErrorResponse} format. Our Angular frontend expects
 * consistent JSON error responses.
 *
 * <p><strong>When is this triggered?</strong>
 * <ul>
 *   <li>401: Request to a protected endpoint without a token, or with an
 *       expired/invalid token. The {@code JwtAuthenticationFilter} couldn't
 *       authenticate the request, and the endpoint is not public.</li>
 *   <li>403: Request with a valid token, but the user's role doesn't have
 *       permission. Example: a USER trying to access /api/v1/admin/**.</li>
 * </ul>
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class SecurityExceptionHandler implements AuthenticationEntryPoint, AccessDeniedHandler {

    private final ObjectMapper objectMapper;

    /**
     * Handles 401 Unauthorized — authentication required but not provided/valid.
     */
    @Override
    public void commence(HttpServletRequest request,
                         HttpServletResponse response,
                         AuthenticationException authException) throws IOException {

        log.debug("Authentication failed for {} {} - {}",
                request.getMethod(), request.getRequestURI(), authException.getMessage());

        writeErrorResponse(
                response,
                HttpStatus.UNAUTHORIZED,
                ErrorCodeEnum.INVALID_CREDENTIALS,
                "Authentication is required to access this resource",
                request.getRequestURI()
        );
    }

    /**
     * Handles 403 Forbidden — authenticated but insufficient permissions.
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
     * Writes a consistent JSON error response directly to the servlet response.
     *
     * <p><strong>Why write directly to the response?</strong> These exceptions
     * occur in the security filter chain, which runs before the DispatcherServlet.
     * Our {@code @RestControllerAdvice} (GlobalExceptionHandler) only catches
     * exceptions from controllers. At the filter level, we must write the
     * response ourselves using the raw HttpServletResponse.
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

Now register these handlers in your `SecurityConfig`. Update the `securityFilterChain` method:

```java
// Inject the new handler in SecurityConfig:
private final SecurityExceptionHandler securityExceptionHandler;

// Add this INSIDE securityFilterChain method, after .sessionManagement(...)
.exceptionHandling(exceptions -> exceptions
        .authenticationEntryPoint(securityExceptionHandler)
        .accessDeniedHandler(securityExceptionHandler)
)
```

The updated `securityFilterChain` method should have this order:

```java
@Bean
public SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
    http
            .csrf(AbstractHttpConfigurer::disable)
            .authorizeHttpRequests(auth -> auth
                    .requestMatchers("/api/v1/auth/**").permitAll()
                    .requestMatchers("/actuator/health").permitAll()
                    .requestMatchers("/api/v1/admin/**").hasRole("ADMIN")
                    .anyRequest().authenticated()
            )
            .sessionManagement(session ->
                    session.sessionCreationPolicy(SessionCreationPolicy.STATELESS)
            )
            .exceptionHandling(exceptions -> exceptions
                    .authenticationEntryPoint(securityExceptionHandler)
                    .accessDeniedHandler(securityExceptionHandler)
            )
            .authenticationProvider(authenticationProvider())
            .addFilterBefore(
                    jwtAuthenticationFilter,
                    UsernamePasswordAuthenticationFilter.class
            );

    return http.build();
}
```

---

### 6.11 AuditorAware — Completing the Audit Trail

Your `BaseEntity` has `@CreatedBy` and `@LastModifiedBy` fields. Spring Data JPA populates these automatically, but it needs to know *who* the current user is. That's what `AuditorAware` provides — it bridges Spring Security's `SecurityContext` with Spring Data's auditing system.

```java
package org.viators.orderprocessingsystem.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.domain.AuditorAware;
import org.springframework.data.jpa.repository.config.EnableJpaAuditing;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;

import java.util.Optional;

/**
 * Enables JPA auditing and provides the current user for
 * {@code @CreatedBy} and {@code @LastModifiedBy} fields.
 *
 * <p>When Spring Data JPA saves or updates an entity that extends
 * {@code BaseEntity}, it calls the {@link AuditorAware} bean to get
 * the current user's identity. This bean extracts the username from
 * Spring Security's {@code SecurityContext}.
 */
@Configuration
@EnableJpaAuditing(auditorAwareRef = "auditorAware")
public class JpaAuditingConfig {

    /**
     * Provides the current authenticated user's username for auditing.
     *
     * <p>Returns "SYSTEM" when no user is authenticated (e.g., during
     * registration, where the user is being created and thus can't
     * be the authenticated principal yet).
     *
     * @return an AuditorAware that resolves the current username
     */
    @Bean
    public AuditorAware<String> auditorAware() {
        return () -> {
            Authentication authentication = SecurityContextHolder
                    .getContext()
                    .getAuthentication();

            if (authentication == null || !authentication.isAuthenticated()) {
                return Optional.of("SYSTEM");
            }

            return Optional.of(authentication.getName());
        };
    }
}
```

**Why "SYSTEM" as fallback?** During user registration, the new user entity is saved *before* they're authenticated (they don't have a token yet). Without a fallback, `@CreatedBy` would be null, which violates your `nullable = false` constraint.

---

## 7. How Everything Connects: Request Lifecycle

Let's trace a complete authenticated request through the system to cement your understanding. The user calls `GET /api/v1/products` with a JWT token:

```
┌─────────────────────────────────────────────────────────────────────┐
│                         HTTP Request                                │
│  GET /api/v1/products                                               │
│  Authorization: Bearer eyJhbGciOiJIUzI1NiJ9.eyJzdWI...             │
└─────────────────────────────┬───────────────────────────────────────┘
                              │
                              ▼
┌─────────────────────────────────────────────────────────────────────┐
│                   JwtAuthenticationFilter                           │
│                                                                     │
│  1. Extracts "Bearer eyJ..." from Authorization header              │
│  2. Calls jwtService.extractUsername(token) → "panos"               │
│  3. Calls userDetailsService.loadUserByUsername("panos")            │
│     └→ Database query: SELECT * FROM user WHERE username = 'panos' │
│  4. Calls jwtService.isTokenValid(token, userDetails) → true       │
│  5. Creates UsernamePasswordAuthenticationToken with:               │
│     - principal: UserT{username="panos", role=USER}                 │
│     - authorities: [ROLE_USER]                                      │
│  6. Sets Authentication in SecurityContextHolder                    │
└─────────────────────────────┬───────────────────────────────────────┘
                              │
                              ▼
┌─────────────────────────────────────────────────────────────────────┐
│                  AuthorizationFilter                                │
│  (Built-in Spring Security filter)                                  │
│                                                                     │
│  Checks: Does the request match any authorization rule?             │
│  - /api/v1/products matches ".anyRequest().authenticated()"         │
│  - SecurityContext has an Authentication object → ✅ Authorized      │
└─────────────────────────────┬───────────────────────────────────────┘
                              │
                              ▼
┌─────────────────────────────────────────────────────────────────────┐
│                    DispatcherServlet                                 │
│  Routes to ProductController.getProducts()                          │
│  → ProductService processes business logic                          │
│  → Returns ResponseEntity<List<ProductSummaryResponse>>             │
└─────────────────────────────┬───────────────────────────────────────┘
                              │
                              ▼
┌─────────────────────────────────────────────────────────────────────┐
│                         HTTP Response                               │
│  200 OK                                                             │
│  Content-Type: application/json                                     │
│  [ { "uuid": "...", "name": "Widget", ... } ]                      │
└─────────────────────────────────────────────────────────────────────┘
```

---

## 8. Testing with cURL

### Register a New User
```bash
curl -X POST http://localhost:8080/api/v1/auth/register \
  -H "Content-Type: application/json" \
  -d '{
    "username": "panos",
    "email": "panos@example.com",
    "password": "SecurePass123!",
    "firstName": "Panos",
    "lastName": "Vasilopoulos",
    "age": 28
  }'
```

Expected response (201 Created):
```json
{
  "token": "eyJhbGciOiJIUzI1NiJ9...",
  "uuid": "a1b2c3d4-...",
  "username": "panos",
  "email": "panos@example.com",
  "role": "USER"
}
```

### Login
```bash
curl -X POST http://localhost:8080/api/v1/auth/login \
  -H "Content-Type: application/json" \
  -d '{
    "username": "panos",
    "password": "SecurePass123!"
  }'
```

### Access a Protected Endpoint
```bash
# Copy the token from the login response
curl -X GET http://localhost:8080/api/v1/products \
  -H "Authorization: Bearer eyJhbGciOiJIUzI1NiJ9..."
```

### Test Without Token (Should Fail with 401)
```bash
curl -X GET http://localhost:8080/api/v1/products
```

Expected response (401 Unauthorized):
```json
{
  "status": 401,
  "errorCode": "INVALID_CREDENTIALS",
  "message": "Authentication is required to access this resource",
  "path": "/api/v1/products",
  "timestamp": "2026-02-27T10:30:00Z"
}
```

---

## 9. Security Best Practices Checklist

| Practice | Status | Notes |
|----------|--------|-------|
| Secret key from environment variable | ✅ | Never hardcode in source code |
| BCrypt password hashing | ✅ | Salted, slow by design |
| Stateless sessions | ✅ | `SessionCreationPolicy.STATELESS` |
| CSRF disabled (appropriate for JWT) | ✅ | Token in header provides equivalent protection |
| Generic error messages | ✅ | "Invalid credentials" — never reveal which field failed |
| Token expiration | ✅ | 24h default, configurable |
| Roles in JWT claims | ✅ | Reduces DB lookups for role checks |
| Filter-level exception handling | ✅ | Consistent error format for 401/403 |
| No sensitive data in JWT payload | ✅ | Only username and role |
| `@Transactional(readOnly)` on reads | ✅ | Performance optimization |
| Input validation on DTOs | ✅ | `@NotBlank`, `@Size`, `@Email` |

---

## 10. Common Mistakes to Avoid

**1. Forgetting `SessionCreationPolicy.STATELESS`**
Without this, Spring creates server-side sessions alongside JWT, defeating the purpose. Your app will seem to work but won't be truly stateless.

**2. Hardcoding the JWT secret**
Every tutorial shows `secretKey = "my-secret"`. This ends up in Git, and now anyone with repo access can forge tokens. Always use environment variables.

**3. Using `@Autowired` field injection in filters**
Your `JwtAuthenticationFilter` uses constructor injection via `@RequiredArgsConstructor`. If you see `@Autowired` on a field, refactor it. Field injection makes testing harder and hides dependencies.

**4. Catching exceptions in the filter and returning early**
If token validation fails, don't return a 401 from the filter. Let the request continue — Spring Security's authorization layer will handle it correctly based on your rules. Some endpoints might be public.

**5. Not understanding the ROLE_ prefix**
If your user has role `ADMIN`, you must store the authority as `ROLE_ADMIN`. When checking with `hasRole("ADMIN")`, Spring automatically prepends `ROLE_`. If you use `hasAuthority("ADMIN")` instead, you'd need the authority stored as exactly `ADMIN` (no prefix). Pick one convention and stick with it.

**6. Returning entity objects from controllers**
Your `ProductController` correctly returns `ProductSummaryResponse` DTOs. Never return `UserT` from authentication endpoints — the password hash would be serialized into JSON.

**7. Using `httpBasic()` or `formLogin()` alongside JWT**
These enable session-based authentication mechanisms. For a pure JWT REST API, you don't need either.

---

## Project File Structure After Implementation

```
src/main/java/org/viators/orderprocessingsystem/
├── OrderProcessingSystemApplication.java
├── auth/                                          ← NEW PACKAGE
│   ├── AuthenticationController.java
│   ├── AuthenticationService.java
│   ├── CustomUserDetailsService.java
│   ├── JwtAuthenticationFilter.java
│   ├── JwtService.java
│   ├── SecurityExceptionHandler.java
│   └── dto/
│       ├── request/
│       │   ├── LoginRequest.java
│       │   └── RegisterRequest.java
│       └── response/
│           └── AuthenticationResponse.java
├── config/                                        ← NEW PACKAGE
│   ├── JpaAuditingConfig.java
│   ├── JwtProperties.java
│   └── SecurityConfig.java
├── common/
│   ├── BaseEntity.java
│   └── enums/
│       ├── CategoryEnum.java
│       ├── StatusEnum.java
│       └── UserRolesEnum.java
├── exceptions/
│   └── ... (existing)
├── product/
│   └── ... (existing)
└── user/
    ├── UserRepository.java                        ← MODIFIED
    ├── UserService.java
    └── UserT.java                                 ← MODIFIED
```
