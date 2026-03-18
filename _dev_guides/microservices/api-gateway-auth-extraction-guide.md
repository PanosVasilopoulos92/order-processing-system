# API Gateway + Auth Service Extraction Guide

## For the Order Processing System — Spring Boot 4 / Spring Cloud 2025.1.0 / Java 25

---

## Table of Contents

1. [Understanding the Big Picture](#1-understanding-the-big-picture)
2. [The Security Re-Architecture](#2-the-security-re-architecture)
3. [Architecture Overview](#3-architecture-overview)
4. [Module Inventory: What Gets Created, What Changes](#4-module-inventory-what-gets-created-what-changes)
5. [Version Compatibility](#5-version-compatibility)
6. [Implementation: Step by Step](#6-implementation-step-by-step)
   - 6.1 [Update the Parent POM](#61-update-the-parent-pom)
   - 6.2 [Create ops-common (Shared Infrastructure)](#62-create-ops-common-shared-infrastructure)
   - 6.3 [Create ops-auth (Authentication Microservice)](#63-create-ops-auth-authentication-microservice)
   - 6.4 [Create api-gateway (Gateway Microservice)](#64-create-api-gateway-gateway-microservice)
   - 6.5 [Refactor ops-monolith (Strip Auth, Trust Headers)](#65-refactor-ops-monolith-strip-auth-trust-headers)
   - 6.6 [Update docker-compose.yml](#66-update-docker-composeyml)
7. [How Everything Connects: Request Lifecycle](#7-how-everything-connects-request-lifecycle)
8. [What Changes for Existing cURL Tests](#8-what-changes-for-existing-curl-tests)
9. [Configuration Reference](#9-configuration-reference)
10. [Common Mistakes to Avoid](#10-common-mistakes-to-avoid)
11. [Final Project Structure](#11-final-project-structure)

---

## 1. Understanding the Big Picture

### What We're Building

This guide implements three interconnected changes in a single coordinated effort:

1. **API Gateway** — a Spring Cloud Gateway (WebFlux) application that sits in front of all services, validates JWT tokens, and injects user identity as HTTP headers.

2. **Auth Service Extraction** — the authentication logic (login, register, token creation) moves from the monolith into its own standalone microservice.

3. **Shared Infrastructure Module** — a non-runnable library containing base classes, enums, and exception types that both the monolith and auth-service need.

### Why These Three Must Happen Together

You can't add a gateway that validates JWTs without knowing who creates the tokens. If auth stays in the monolith, the gateway routes auth requests to the monolith — but then the monolith still holds the JWT secret, which partially defeats the purpose. By extracting auth simultaneously, we achieve a clean separation:

- **ops-auth** — the ONLY service that CREATES tokens (holds JWT secret for signing)
- **api-gateway** — the ONLY service that VALIDATES tokens (holds JWT secret for verification)
- **ops-monolith** — ZERO JWT knowledge; trusts gateway headers exclusively (BR-042)

### Before vs After

```
BEFORE (current):

  Client ──► Monolith:8888
              ├── POST /api/v1/auth/login    (creates JWT)
              ├── JWT validation per request  (validates JWT)
              └── Business logic              (uses JWT principal)


AFTER (this guide):

  Client ──► Gateway:8080
              │
              ├── /api/v1/auth/** ──► Auth Service:8070
              │                       └── Creates JWTs (has secret)
              │
              └── /** ──────────────► Monolith:8888
                  (validates JWT)      └── Reads X-User-* headers
                  (injects headers)    └── Zero JWT knowledge
                  (strips JWT)
```

---

## 2. The Security Re-Architecture

### The `@AuthenticationPrincipal` Challenge

Your codebase uses `@AuthenticationPrincipal` in two flavors:

**Flavor 1 — Full UserT entity (NotificationController only):**
```java
public ResponseEntity<NotificationResponse> getNotification(
        @AuthenticationPrincipal UserT principal, ...) {
    // Uses principal.isAdminUser(), principal.getUuid()
}
```

**Flavor 2 — SpEL expression extracting UUID (OrderController, PaymentController):**
```java
public ResponseEntity<OrderDetailsResponse> create(
        @AuthenticationPrincipal(expression = "uuid") String customerUuid, ...) {
}
```

Both depend on the SecurityContext principal being a `UserT` loaded from the database. With the gateway handling JWT, the monolith no longer has the token, so it can't do that DB lookup.

### The Solution: GatewayPrincipal

A lightweight object that carries uuid, role, and username — everything the gateway extracts from the JWT and passes via headers. It implements `UserDetails` so all Spring Security machinery (`@PreAuthorize`, `hasRole()`, `@AuthenticationPrincipal`) keeps working.

The critical point is that `@AuthenticationPrincipal(expression = "uuid")` calls `getUuid()` on whatever object is the principal. `GatewayPrincipal` has a `getUuid()` method, so the SpEL expression works unchanged. `NotificationController` changes its parameter type from `UserT` to `GatewayPrincipal`, and `NotificationService` follows.

---

## 3. Architecture Overview

```
┌──────────────────────────────────────────────────────────────────┐
│                    Docker Compose Network                         │
│                                                                  │
│  ┌──────────────────┐                                            │
│  │  eureka-server    │◄── registers ──┬──────────┬───────────┐   │
│  │  (Port 8761)      │               │          │           │   │
│  └──────────────────┘                │          │           │   │
│                                      │          │           │   │
│  ┌───────────────────┐   ┌───────────┴──┐  ┌────┴──────┐       │
│  │  api-gateway       │   │  ops-auth     │  │ ops-mono  │       │
│  │  (Port 8080)       │──►│  (Port 8070)  │  │ (Port     │       │
│  │                    │   │              │  │  8888)    │       │
│  │  Validates JWT     │──►│  Login       │  │           │       │
│  │  Injects headers   │   │  Register    │  │ Business  │       │
│  │  Routes requests   │   │  Token sign  │  │ logic     │       │
│  │                    │   │              │  │ Trusts    │       │
│  │  JWT secret (verify)│  │ JWT secret   │  │ headers   │       │
│  │  No DB, no biz     │   │ (sign)       │  │ No JWT    │       │
│  │  logic             │   │ MySQL access │  │ MySQL     │       │
│  └───────────────────┘   └──────────────┘  └───────────┘       │
│       ▲                                                          │
│  Client (Angular, cURL)                                          │
│  All traffic → port 8080                                         │
│                                                                  │
│  ┌──────────┐  ┌──────────┐  ┌──────────┐                       │
│  │  MySQL   │  │ RabbitMQ │  │ MailHog  │                       │
│  │  3306    │  │ 5672     │  │ 1025     │                       │
│  └──────────┘  └──────────┘  └──────────┘                       │
└──────────────────────────────────────────────────────────────────┘
```

---

## 4. Module Inventory: What Gets Created, What Changes

| Module | Type | Changes |
|--------|------|---------|
| `ops-common` | NEW — shared library (JAR, not runnable) | BaseEntity, StatusEnum, UserRolesEnum, ErrorCodeEnum, exception hierarchy, error DTOs |
| `ops-auth` | NEW — Spring Boot app (port 8070) | AuthService, AuthenticationController, JwtService, JwtProperties, CustomUserDetailsService, SecurityConfig, SecurityExceptionHandler, auth DTOs, its own UserT + UserRepository |
| `api-gateway` | NEW — Spring Boot app (port 8080, WebFlux/Netty) | JwtConfig, JwtAuthenticationGlobalFilter, routing config |
| `ops-monolith` | MODIFIED — strips auth package | Removes: AuthService, AuthenticationController, JwtService, JwtProperties, JwtAuthenticationFilter, CustomUserDetailsService, auth DTOs. Adds: GatewayPrincipal, GatewayAuthenticationFilter. Updates: SecurityConfig, UserSecurity, NotificationController, NotificationService |
| `pom.xml` (root) | MODIFIED | + 3 new modules, JJWT in dependencyManagement |
| `docker-compose.yml` | MODIFIED | + api-gateway and ops-auth services |

---

## 5. Version Compatibility

| Component | Version | Notes |
|-----------|---------|-------|
| Spring Boot | 4.0.3 | Parent POM (unchanged) |
| Spring Cloud BOM | 2025.1.0 | Parent POM (unchanged) |
| Gateway starter | `spring-cloud-starter-gateway-server-webflux` | New artifact name in 2025.1.0 (old name removed) |
| Spring Cloud Netflix | 5.0.0 | Managed by BOM |
| JJWT | 0.13.0 | Used by ops-auth (signing) and api-gateway (verification) |

---

## 6. Implementation: Step by Step

### 6.1 Update the Parent POM

**File: `pom.xml` (root)**

Three changes: add new modules, add JJWT to `<dependencyManagement>`, and add `ops-common` as a managed dependency so children can reference it.

```xml
<!-- ── CHANGE 1: Add new modules ─────────────────────────── -->
<modules>
    <module>ops-common</module>     <!-- Must be first — others depend on it -->
    <module>ops-monolith</module>
    <module>ops-auth</module>
    <module>eureka-server</module>
    <module>api-gateway</module>
</modules>

<!-- ── CHANGE 2: Add to <dependencyManagement> ───────────── -->
<dependencyManagement>
    <dependencies>
        <!-- Spring Cloud BOM (already present) -->
        <dependency>
            <groupId>org.springframework.cloud</groupId>
            <artifactId>spring-cloud-dependencies</artifactId>
            <version>${spring-cloud.version}</version>
            <type>pom</type>
            <scope>import</scope>
        </dependency>

        <!-- ops-common: our shared library. Declaring it here lets
             child modules reference it without specifying a version. -->
        <dependency>
            <groupId>org.viators</groupId>
            <artifactId>ops-common</artifactId>
            <version>${project.version}</version>
        </dependency>

        <!-- JJWT: shared between ops-auth (signs) and api-gateway (verifies).
             Centralizing the version prevents subtle version mismatches. -->
        <dependency>
            <groupId>io.jsonwebtoken</groupId>
            <artifactId>jjwt-api</artifactId>
            <version>0.13.0</version>
        </dependency>
        <dependency>
            <groupId>io.jsonwebtoken</groupId>
            <artifactId>jjwt-impl</artifactId>
            <version>0.13.0</version>
        </dependency>
        <dependency>
            <groupId>io.jsonwebtoken</groupId>
            <artifactId>jjwt-jackson</artifactId>
            <version>0.13.0</version>
        </dependency>
    </dependencies>
</dependencyManagement>
```

After this change, remove the `<version>0.13.0</version>` tags from JJWT dependencies in the monolith's `pom.xml` — they're now inherited from the parent.

---

### 6.2 Create ops-common (Shared Infrastructure)

This module is a plain JAR (not a Spring Boot app). It contains types that change rarely and carry no business logic — the "platform primitives."

```bash
mkdir -p ops-common/src/main/java/org/viators/common/enums
mkdir -p ops-common/src/main/java/org/viators/common/entity
mkdir -p ops-common/src/main/java/org/viators/common/exception/dto
mkdir -p ops-common/src/main/java/org/viators/common/exception/handler
```

#### 6.2.1 ops-common POM

**File: `ops-common/pom.xml`**

```xml
<?xml version="1.0" encoding="UTF-8"?>
<project xmlns="http://maven.apache.org/POM/4.0.0"
         xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
         xsi:schemaLocation="http://maven.apache.org/POM/4.0.0
         https://maven.apache.org/xsd/maven-4.0.0.xsd">
    <modelVersion>4.0.0</modelVersion>

    <!-- Shared infrastructure library for the OPS platform.
         This is a plain JAR — NOT a Spring Boot app. No main class,
         no spring-boot-maven-plugin. It provides base classes, enums,
         and exception types that all services need. -->
    <parent>
        <groupId>org.viators</groupId>
        <artifactId>order-processing-system</artifactId>
        <version>0.0.1-SNAPSHOT</version>
    </parent>

    <artifactId>ops-common</artifactId>
    <n>OPS Common</n>
    <description>Shared infrastructure types for the OPS platform</description>

    <!-- No spring-boot-maven-plugin here — this is a library, not an app. -->

    <dependencies>
        <!-- JPA annotations for BaseEntity (@Entity, @Id, @Version, etc.) -->
        <dependency>
            <groupId>org.springframework.boot</groupId>
            <artifactId>spring-boot-starter-data-jpa</artifactId>
        </dependency>

        <!-- Servlet API for GlobalExceptionHandler (HttpServletRequest) -->
        <dependency>
            <groupId>jakarta.servlet</groupId>
            <artifactId>jakarta.servlet-api</artifactId>
            <scope>provided</scope>
        </dependency>

        <!-- Validation annotations for error DTOs -->
        <dependency>
            <groupId>org.springframework.boot</groupId>
            <artifactId>spring-boot-starter-validation</artifactId>
        </dependency>

        <!-- Spring Web for @RestControllerAdvice, ResponseEntity -->
        <dependency>
            <groupId>org.springframework.boot</groupId>
            <artifactId>spring-boot-starter-webmvc</artifactId>
        </dependency>

        <!-- Spring Security for AccessDeniedException handler -->
        <dependency>
            <groupId>org.springframework.boot</groupId>
            <artifactId>spring-boot-starter-security</artifactId>
        </dependency>
    </dependencies>
</project>
```

#### 6.2.2 Classes that Move to ops-common

Every file below moves **from** `ops-monolith` **to** `ops-common`, with package names updated from `org.viators.orderprocessingsystem.*` to `org.viators.common.*`.

**Enums** — move to `org.viators.common.enums`:
- `StatusEnum.java`
- `UserRolesEnum.java`
- `ErrorCodeEnum.java`

**Base Entity** — move to `org.viators.common.entity`:

**File: `ops-common/src/main/java/org/viators/common/entity/BaseEntity.java`**

```java
package org.viators.common.entity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.ToString;
import lombok.experimental.SuperBuilder;
import org.springframework.data.annotation.CreatedBy;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.annotation.LastModifiedBy;
import org.springframework.data.annotation.LastModifiedDate;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;
import org.viators.common.enums.StatusEnum;

import java.time.Instant;
import java.util.UUID;

/**
 * Base class for all JPA entities in the OPS platform.
 * Provides audit fields (createdAt, updatedAt, createdBy, updatedBy),
 * soft delete support via StatusEnum, optimistic locking via @Version,
 * and automatic UUID generation.
 */
@MappedSuperclass
@Getter
@Setter
@ToString
@SuperBuilder
@NoArgsConstructor
@EntityListeners(AuditingEntityListener.class)
public class BaseEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "uuid", unique = true, nullable = false, updatable = false)
    private String uuid;

    @Version
    @Column(name = "version", nullable = false)
    private Integer version;

    @CreatedBy
    @Column(name = "created_by", nullable = false, updatable = false)
    private String createdBy;

    @CreatedDate
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @LastModifiedBy
    @Column(name = "updated_by", nullable = false)
    private String updatedBy;

    @LastModifiedDate
    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false)
    private StatusEnum status;

    @PrePersist
    private void onCreate() {
        if (this.uuid == null) {
            this.uuid = UUID.randomUUID().toString();
        }
        this.status = StatusEnum.ACTIVE;
    }
}
```

**Exceptions** — move to `org.viators.common.exception`:
- `BaseException.java`
- `ResourceNotFoundException.java`
- `DuplicateResourceException.java`
- `InvalidCredentialsException.java`
- `AccessDeniedException.java`
- `BusinessValidationException.java`
- `InvalidStateException.java`

**Exception DTOs** — move to `org.viators.common.exception.dto`:
- `ErrorResponse.java`
- `FieldError.java`
- `ValidationErrorResponse.java`

**Exception Handler** — move to `org.viators.common.exception.handler`:
- `GlobalExceptionHandler.java`

All these classes stay exactly the same in logic. The ONLY change is the package declaration — for example `BaseException` becomes:

```java
package org.viators.common.exception;
// ... rest unchanged
```

And `ErrorResponse` becomes:

```java
package org.viators.common.exception.dto;

import org.viators.common.enums.ErrorCodeEnum;
// ... rest unchanged
```

> **Import update needed everywhere**: After this move, every class in ops-monolith and ops-auth that references these types must update its imports from `org.viators.orderprocessingsystem.common.*` / `org.viators.orderprocessingsystem.exceptions.*` to `org.viators.common.*`. This is a bulk find-and-replace operation in your IDE.

---

### 6.3 Create ops-auth (Authentication Microservice)

```bash
mkdir -p ops-auth/src/main/java/org/viators/auth/config
mkdir -p ops-auth/src/main/java/org/viators/auth/dto/request
mkdir -p ops-auth/src/main/java/org/viators/auth/dto/response
mkdir -p ops-auth/src/main/java/org/viators/auth/user
mkdir -p ops-auth/src/main/resources
mkdir -p ops-auth/src/test/java/org/viators/auth
```

#### 6.3.1 ops-auth POM

**File: `ops-auth/pom.xml`**

```xml
<?xml version="1.0" encoding="UTF-8"?>
<project xmlns="http://maven.apache.org/POM/4.0.0"
         xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
         xsi:schemaLocation="http://maven.apache.org/POM/4.0.0
         https://maven.apache.org/xsd/maven-4.0.0.xsd">
    <modelVersion>4.0.0</modelVersion>

    <!-- Authentication Microservice.
         Sole responsibility: user identity management — register, login, issue JWTs.
         This is the ONLY service that CREATES tokens (holds JWT secret for signing). -->
    <parent>
        <groupId>org.viators</groupId>
        <artifactId>order-processing-system</artifactId>
        <version>0.0.1-SNAPSHOT</version>
    </parent>

    <artifactId>ops-auth</artifactId>
    <n>OPS Auth Service</n>
    <description>Authentication microservice — login, registration, JWT issuance</description>

    <dependencies>
        <!-- ops-common: BaseEntity, enums, exception hierarchy -->
        <dependency>
            <groupId>org.viators</groupId>
            <artifactId>ops-common</artifactId>
        </dependency>

        <!-- Spring Web MVC (not WebFlux — this is a normal REST service) -->
        <dependency>
            <groupId>org.springframework.boot</groupId>
            <artifactId>spring-boot-starter-webmvc</artifactId>
        </dependency>

        <!-- Spring Security (for AuthenticationManager, PasswordEncoder, UserDetails) -->
        <dependency>
            <groupId>org.springframework.boot</groupId>
            <artifactId>spring-boot-starter-security</artifactId>
        </dependency>

        <!-- JPA (for UserT entity and UserRepository) -->
        <dependency>
            <groupId>org.springframework.boot</groupId>
            <artifactId>spring-boot-starter-data-jpa</artifactId>
        </dependency>

        <!-- Bean Validation (for @NotBlank, @Email on DTOs) -->
        <dependency>
            <groupId>org.springframework.boot</groupId>
            <artifactId>spring-boot-starter-validation</artifactId>
        </dependency>

        <!-- Eureka Client (registers with service registry) -->
        <dependency>
            <groupId>org.springframework.cloud</groupId>
            <artifactId>spring-cloud-starter-netflix-eureka-client</artifactId>
        </dependency>

        <!-- JWT (token creation and signing) -->
        <dependency>
            <groupId>io.jsonwebtoken</groupId>
            <artifactId>jjwt-api</artifactId>
        </dependency>
        <dependency>
            <groupId>io.jsonwebtoken</groupId>
            <artifactId>jjwt-impl</artifactId>
            <scope>runtime</scope>
        </dependency>
        <dependency>
            <groupId>io.jsonwebtoken</groupId>
            <artifactId>jjwt-jackson</artifactId>
            <scope>runtime</scope>
        </dependency>

        <!-- MySQL (shared database with monolith — Phase 1) -->
        <dependency>
            <groupId>com.mysql</groupId>
            <artifactId>mysql-connector-j</artifactId>
            <scope>runtime</scope>
        </dependency>

        <!-- Testing -->
        <dependency>
            <groupId>org.springframework.boot</groupId>
            <artifactId>spring-boot-starter-test</artifactId>
            <scope>test</scope>
        </dependency>
    </dependencies>

    <build>
        <plugins>
            <plugin>
                <groupId>org.springframework.boot</groupId>
                <artifactId>spring-boot-maven-plugin</artifactId>
            </plugin>
        </plugins>
    </build>
</project>
```

#### 6.3.2 Application Class

**File: `ops-auth/src/main/java/org/viators/auth/AuthServiceApplication.java`**

```java
package org.viators.auth;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.autoconfigure.domain.EntityScan;
import org.springframework.cloud.client.discovery.EnableDiscoveryClient;

/**
 * Entry point for the Authentication Service.
 *
 * EntityScan points to this module's user package where UserT lives.
 * The BaseEntity class comes from ops-common (org.viators.common.entity)
 * and is discovered through JPA's @MappedSuperclass mechanism.
 */
@SpringBootApplication
@EnableDiscoveryClient
@EntityScan(basePackages = {"org.viators.auth.user", "org.viators.common.entity"})
public class AuthServiceApplication {

    public static void main(String[] args) {
        SpringApplication.run(AuthServiceApplication.class, args);
    }
}
```

#### 6.3.3 UserT (Auth-Service's Own Copy)

This is the auth-focused copy of UserT. It implements `UserDetails` for Spring Security integration, owns the password field, and maps to the SAME `users` table as the monolith's UserT (shared database, Phase 1).

**File: `ops-auth/src/main/java/org/viators/auth/user/UserT.java`**

```java
package org.viators.auth.user;

import jakarta.persistence.*;
import lombok.*;
import lombok.experimental.SuperBuilder;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.userdetails.UserDetails;
import org.viators.common.entity.BaseEntity;
import org.viators.common.enums.StatusEnum;
import org.viators.common.enums.UserRolesEnum;

import java.util.Collection;
import java.util.List;

/**
 * User entity for the auth service. Maps to the same "users" table
 * as the monolith's UserT (shared database — Phase 1 of migration).
 *
 * This copy is auth-focused: it implements UserDetails for Spring Security
 * and owns the password field. The monolith's copy will diverge over time
 * as it focuses on customer/business concerns.
 */
@Entity
@Table(name = "users")
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

    @Column(name = "phone_number")
    private String phoneNumber;

    @Column(name = "shipping_address")
    private String shippingAddress;

    @Enumerated(EnumType.STRING)
    @Column(name = "user_role", nullable = false)
    @Builder.Default
    private UserRolesEnum userRole = UserRolesEnum.CUSTOMER;

    @Override
    public Collection<? extends GrantedAuthority> getAuthorities() {
        return List.of(new SimpleGrantedAuthority("ROLE_".concat(userRole.name())));
    }

    @Override
    public boolean isEnabled() {
        return StatusEnum.ACTIVE.equals(getStatus());
    }
}
```

#### 6.3.4 UserRepository

**File: `ops-auth/src/main/java/org/viators/auth/user/UserRepository.java`**

```java
package org.viators.auth.user;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;

/**
 * Repository for user lookups needed during authentication.
 * Only includes the query methods auth requires — no business queries.
 */
@Repository
public interface UserRepository extends JpaRepository<UserT, Long> {

    Optional<UserT> findByUsername(String username);

    boolean existsByUsername(String username);

    boolean existsByEmail(String email);
}
```

#### 6.3.5 JwtService (Relocated from Monolith)

**File: `ops-auth/src/main/java/org/viators/auth/JwtService.java`**

This is the monolith's existing `JwtService` with updated package and one change: the `generateToken` method now always includes the `uuid` claim (the gateway needs it).

```java
package org.viators.auth;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.stereotype.Service;
import org.viators.auth.config.JwtProperties;

import javax.crypto.SecretKey;
import java.util.Base64;
import java.util.Date;
import java.util.Map;

/**
 * Service for creating, parsing, and validating JWT tokens.
 *
 * Uses JJWT 0.13.0 with HMAC-SHA256 signing. The signing key is
 * derived from a Base64-encoded secret configured in application.yaml.
 *
 * This service lives ONLY in ops-auth. The api-gateway has a separate,
 * read-only JWT parser — it never creates tokens.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class JwtService {

    private final JwtProperties jwtProperties;
    private SecretKey signingKey;

    @PostConstruct
    private void init() {
        byte[] keyBytes = Base64.getDecoder().decode(jwtProperties.getSecretKey());
        this.signingKey = Keys.hmacShaKeyFor(keyBytes);
    }

    /**
     * Generates a JWT token for an authenticated user.
     * The token includes the username as subject, plus any extra claims
     * (role, uuid) passed in the map.
     */
    public String generateToken(Map<String, Object> extraClaims, UserDetails userDetails) {
        long now = System.currentTimeMillis();

        return Jwts.builder()
                .claims(extraClaims)
                .subject(userDetails.getUsername())
                .issuedAt(new Date())
                .expiration(new Date(now + jwtProperties.getExpiration()))
                .signWith(signingKey)
                .compact();
    }

    public String generateToken(UserDetails userDetails) {
        return generateToken(Map.of(), userDetails);
    }

    public String extractUsername(String token) {
        return extractAllClaims(token).getSubject();
    }

    public boolean isTokenValid(String token, UserDetails userDetails) {
        String username = extractUsername(token);
        return username.equals(userDetails.getUsername()) && !isTokenExpired(token);
    }

    private boolean isTokenExpired(String token) {
        return extractAllClaims(token).getExpiration().before(new Date());
    }

    private Claims extractAllClaims(String token) {
        return Jwts.parser()
                .verifyWith(signingKey)
                .build()
                .parseSignedClaims(token)
                .getPayload();
    }
}
```

#### 6.3.6 JwtProperties

**File: `ops-auth/src/main/java/org/viators/auth/config/JwtProperties.java`**

```java
package org.viators.auth.config;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * Binds JWT properties from application.yaml.
 * Prefix: application.security.jwt
 */
@Getter
@Setter
@Component
@ConfigurationProperties(prefix = "application.security.jwt")
public class JwtProperties {

    private String secretKey;
    private long expiration;
}
```

#### 6.3.7 AuthService (Relocated, Updated)

**File: `ops-auth/src/main/java/org/viators/auth/AuthService.java`**

The key change from the monolith's version: `uuid` is now always included in the JWT claims so the gateway can extract it.

```java
package org.viators.auth;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.viators.auth.dto.request.LoginRequest;
import org.viators.auth.dto.request.RegisterUserRequest;
import org.viators.auth.dto.response.AuthenticationResponse;
import org.viators.auth.user.UserRepository;
import org.viators.auth.user.UserT;
import org.viators.common.exception.DuplicateResourceException;
import org.viators.common.exception.InvalidCredentialsException;

import java.util.Map;

@Service
@RequiredArgsConstructor
@Slf4j
@Transactional(readOnly = true)
public class AuthService {

    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;
    private final JwtService jwtService;
    private final AuthenticationManager authenticationManager;

    @Transactional
    public AuthenticationResponse registerUser(RegisterUserRequest request) {
        if (userRepository.existsByUsername(request.username())) {
            throw new DuplicateResourceException("User", "username", request.username());
        }

        if (userRepository.existsByEmail(request.email())) {
            throw new DuplicateResourceException("User", "email", request.email());
        }

        UserT user = request.toEntity();
        user.setPassword(passwordEncoder.encode(request.password()));

        user = userRepository.save(user);
        log.info("New user registered: {} (uuid: {})", user.getUsername(), user.getUuid());

        // uuid claim is REQUIRED — the gateway extracts it to build
        // the X-User-UUID header for downstream services.
        String token = jwtService.generateToken(
                Map.of(
                        "role", user.getUserRole().name(),
                        "uuid", user.getUuid()
                ),
                user
        );

        return buildAuthResponse(user, token);
    }

    public AuthenticationResponse login(LoginRequest request) {
        try {
            authenticationManager.authenticate(
                    new UsernamePasswordAuthenticationToken(
                            request.username(),
                            request.password()
                    )
            );
        } catch (BadCredentialsException e) {
            throw new InvalidCredentialsException();
        }

        UserT user = userRepository.findByUsername(request.username())
                .orElseThrow(InvalidCredentialsException::new);

        String token = jwtService.generateToken(
                Map.of(
                        "role", user.getUserRole().name(),
                        "uuid", user.getUuid()
                ),
                user
        );

        log.info("User logged in: {}", user.getUsername());
        return buildAuthResponse(user, token);
    }

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

#### 6.3.8 AuthenticationController, DTOs, CustomUserDetailsService

These relocate from the monolith with only package changes.

**File: `ops-auth/src/main/java/org/viators/auth/AuthenticationController.java`**

```java
package org.viators.auth;

import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.support.ServletUriComponentsBuilder;
import org.viators.auth.dto.request.LoginRequest;
import org.viators.auth.dto.request.RegisterUserRequest;
import org.viators.auth.dto.response.AuthenticationResponse;

import java.net.URI;

@RestController
@RequestMapping("/api/v1/auth")
@RequiredArgsConstructor
public class AuthenticationController {

    private final AuthService authService;

    @PostMapping("/register")
    public ResponseEntity<AuthenticationResponse> register(
            @Valid @RequestBody RegisterUserRequest request) {

        AuthenticationResponse response = authService.registerUser(request);
        URI location = ServletUriComponentsBuilder
                .fromCurrentRequest()
                .path("/{uuid}")
                .buildAndExpand(response.uuid())
                .toUri();

        return ResponseEntity.created(location).body(response);
    }

    @PostMapping("/login")
    public ResponseEntity<AuthenticationResponse> login(
            @Valid @RequestBody LoginRequest request) {

        AuthenticationResponse response = authService.login(request);
        return ResponseEntity.ok(response);
    }
}
```

**File: `ops-auth/src/main/java/org/viators/auth/dto/request/LoginRequest.java`**

```java
package org.viators.auth.dto.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record LoginRequest(
        @NotBlank(message = "Username is required")
        @Size(min = 3, max = 50, message = "Username must be between 3-50 characters long")
        String username,

        @NotBlank(message = "Password is required")
        @Size(min = 8, max = 100, message = "Password can be between 8-100 characters long")
        String password
) {
}
```

**File: `ops-auth/src/main/java/org/viators/auth/dto/request/RegisterUserRequest.java`**

```java
package org.viators.auth.dto.request;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.viators.auth.user.UserT;

public record RegisterUserRequest(
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
) {

    public UserT toEntity() {
        return UserT.builder()
                .username(username)
                .email(email)
                .password(password)
                .firstName(firstName)
                .lastName(lastName)
                .age(age)
                .build();
    }
}
```

**File: `ops-auth/src/main/java/org/viators/auth/dto/response/AuthenticationResponse.java`**

```java
package org.viators.auth.dto.response;

public record AuthenticationResponse(
        String token,
        String uuid,
        String username,
        String email,
        String role
) {
}
```

**File: `ops-auth/src/main/java/org/viators/auth/CustomUserDetailsService.java`**

```java
package org.viators.auth;

import lombok.RequiredArgsConstructor;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.viators.auth.user.UserRepository;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class CustomUserDetailsService implements UserDetailsService {

    private final UserRepository userRepository;

    @Override
    public UserDetails loadUserByUsername(String username) throws UsernameNotFoundException {
        return userRepository.findByUsername(username)
                .orElseThrow(() -> new UsernameNotFoundException(
                        "User not found with username: %s".formatted(username)));
    }
}
```

#### 6.3.9 Auth Service SecurityConfig

Much simpler than the monolith's — all endpoints are public (the gateway handles authentication for protected routes, and the auth service only exposes public auth endpoints).

**File: `ops-auth/src/main/java/org/viators/auth/config/SecurityConfig.java`**

```java
package org.viators.auth.config;

import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.AuthenticationProvider;
import org.springframework.security.authentication.dao.DaoAuthenticationProvider;
import org.springframework.security.config.annotation.authentication.configuration.AuthenticationConfiguration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;

/**
 * Security configuration for the auth service.
 *
 * All endpoints in this service are public (/api/v1/auth/**) because
 * they're accessed by unauthenticated users (login, register).
 * The gateway already handles JWT validation for protected routes —
 * this service is only reached for auth operations.
 *
 * We still need Spring Security for:
 * - AuthenticationManager (used by AuthService.login() to verify credentials)
 * - PasswordEncoder (used for hashing passwords during registration)
 * - DaoAuthenticationProvider (bridges UserDetailsService + PasswordEncoder)
 */
@Configuration
@EnableWebSecurity
@RequiredArgsConstructor
public class SecurityConfig {

    private final UserDetailsService userDetailsService;

    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http) {
        http
                .csrf(AbstractHttpConfigurer::disable)
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers("/api/v1/auth/**").permitAll()
                        .requestMatchers("/actuator/health").permitAll()
                        .anyRequest().denyAll()
                )
                .sessionManagement(session ->
                        session.sessionCreationPolicy(SessionCreationPolicy.STATELESS)
                )
                .authenticationProvider(authenticationProvider());

        return http.build();
    }

    @Bean
    public AuthenticationProvider authenticationProvider() {
        var authProvider = new DaoAuthenticationProvider(userDetailsService);
        authProvider.setPasswordEncoder(passwordEncoder());
        return authProvider;
    }

    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }

    @Bean
    public AuthenticationManager authenticationManager(AuthenticationConfiguration config) {
        return config.getAuthenticationManager();
    }
}
```

#### 6.3.10 Auth Service application.yaml

**File: `ops-auth/src/main/resources/application.yaml`**

```yaml
server:
  port: 8070

spring:
  application:
    name: auth-service

  # Shared MySQL database (Phase 1 — same instance as monolith).
  # Both services read/write the same "users" table.
  # In Phase 2, auth-service would own the user data exclusively.
  datasource:
    url: jdbc:mysql://localhost:3306/${MYSQL_DATABASE}?useSSL=false&serverTimezone=UTC&allowPublicKeyRetrieval=true
    username: ${MYSQL_USER}
    password: ${MYSQL_PASSWORD}
    driver-class-name: com.mysql.cj.jdbc.Driver
  jpa:
    hibernate:
      # "validate" instead of "update" — the monolith owns schema evolution.
      # The auth-service should not create or alter tables.
      ddl-auto: validate
    properties:
      hibernate:
        dialect: org.hibernate.dialect.MySQLDialect
        format_sql: true
    show-sql: false

# JWT configuration — this service SIGNS tokens.
# The same secret must exist in the api-gateway for VERIFICATION.
application:
  security:
    jwt:
      secret-key: ${JWT_SECRET_KEY}
      expiration: 86400000

# Eureka registration
eureka:
  client:
    service-url:
      defaultZone: ${EUREKA_URI:http://localhost:8761/eureka}
    registry-fetch-interval-seconds: 5
  instance:
    prefer-ip-address: true
    lease-renewal-interval-in-seconds: 5
    lease-expiration-duration-in-seconds: 10

logging:
  file:
    name: logs/auth-service-logs.log
```

> **Why `ddl-auto: validate`?** The monolith uses `ddl-auto: update` to create/alter tables. Having two services both trying to modify the schema creates race conditions. The auth-service should validate that the schema matches its entity expectations, but never modify it.

#### 6.3.11 Auth Service Dockerfile

**File: `ops-auth/Dockerfile`**

```dockerfile
# Multi-stage build for the Auth Service.
# Same pattern as eureka-server and api-gateway.

FROM eclipse-temurin:25-jdk AS builder

RUN apt-get update && \
    apt-get install -y --no-install-recommends maven && \
    rm -rf /var/lib/apt/lists/*

WORKDIR /build

# Copy parent POM, ops-common POM (dependency), and this module's POM.
COPY pom.xml ./pom.xml
COPY ops-common/pom.xml ./ops-common/pom.xml
COPY ops-auth/pom.xml ./ops-auth/pom.xml

# Build ops-common first (ops-auth depends on it).
COPY ops-common/src ./ops-common/src
RUN --mount=type=cache,target=/root/.m2 \
    mvn install -pl ops-common -B -DskipTests

# Download ops-auth dependencies then build.
WORKDIR /build/ops-auth
RUN --mount=type=cache,target=/root/.m2 \
    mvn dependency:go-offline -B

COPY ops-auth/src ./src
RUN --mount=type=cache,target=/root/.m2 \
    mvn package -DskipTests -B

# Runtime
FROM eclipse-temurin:25-jdk

WORKDIR /app
COPY --from=builder /build/ops-auth/target/*.jar app.jar

EXPOSE 8070

ENTRYPOINT ["java", "-XX:+UseContainerSupport", "-XX:MaxRAMPercentage=75", "-jar", "app.jar"]
```

---

### 6.4 Create api-gateway (Gateway Microservice)

```bash
mkdir -p api-gateway/src/main/java/org/viators/apigateway/config
mkdir -p api-gateway/src/main/java/org/viators/apigateway/filter
mkdir -p api-gateway/src/main/resources
```

#### 6.4.1 api-gateway POM

**File: `api-gateway/pom.xml`**

```xml
<?xml version="1.0" encoding="UTF-8"?>
<project xmlns="http://maven.apache.org/POM/4.0.0"
         xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
         xsi:schemaLocation="http://maven.apache.org/POM/4.0.0
         https://maven.apache.org/xsd/maven-4.0.0.xsd">
    <modelVersion>4.0.0</modelVersion>

    <!-- API Gateway — single entry point for all client traffic.
         Validates JWT tokens and routes to downstream services via Eureka.
         Runs on WebFlux (Netty), NOT MVC (Tomcat). -->
    <parent>
        <groupId>org.viators</groupId>
        <artifactId>order-processing-system</artifactId>
        <version>0.0.1-SNAPSHOT</version>
    </parent>

    <artifactId>api-gateway</artifactId>
    <n>API Gateway</n>
    <description>Spring Cloud Gateway — single entry point for the OPS platform</description>

    <dependencies>
        <!-- Spring Cloud Gateway (WebFlux variant).
             New artifact name in 2025.1.0 — the old "spring-cloud-starter-gateway" was removed.
             Includes spring-boot-starter-webflux and Spring Cloud LoadBalancer.
             Do NOT add spring-boot-starter-webmvc — they conflict. -->
        <dependency>
            <groupId>org.springframework.cloud</groupId>
            <artifactId>spring-cloud-starter-gateway-server-webflux</artifactId>
        </dependency>

        <!-- Eureka Client (resolves lb:// URIs via service discovery) -->
        <dependency>
            <groupId>org.springframework.cloud</groupId>
            <artifactId>spring-cloud-starter-netflix-eureka-client</artifactId>
        </dependency>

        <!-- JWT (token parsing and verification — no creation) -->
        <dependency>
            <groupId>io.jsonwebtoken</groupId>
            <artifactId>jjwt-api</artifactId>
        </dependency>
        <dependency>
            <groupId>io.jsonwebtoken</groupId>
            <artifactId>jjwt-impl</artifactId>
            <scope>runtime</scope>
        </dependency>
        <dependency>
            <groupId>io.jsonwebtoken</groupId>
            <artifactId>jjwt-jackson</artifactId>
            <scope>runtime</scope>
        </dependency>

        <dependency>
            <groupId>org.springframework.boot</groupId>
            <artifactId>spring-boot-starter-test</artifactId>
            <scope>test</scope>
        </dependency>
    </dependencies>

    <build>
        <plugins>
            <plugin>
                <groupId>org.springframework.boot</groupId>
                <artifactId>spring-boot-maven-plugin</artifactId>
            </plugin>
        </plugins>
    </build>
</project>
```

> Note: the gateway does NOT depend on `ops-common`. It has no JPA, no exception hierarchy, no BaseEntity. It's pure routing and JWT parsing.

#### 6.4.2 Gateway Application Class

**File: `api-gateway/src/main/java/org/viators/apigateway/ApiGatewayApplication.java`**

```java
package org.viators.apigateway;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.cloud.client.discovery.EnableDiscoveryClient;

/**
 * Entry point for the API Gateway.
 * Runs on Netty (not Tomcat) because Spring Cloud Gateway uses WebFlux.
 * Registers with Eureka to resolve lb:// route URIs.
 */
@SpringBootApplication
@EnableDiscoveryClient
public class ApiGatewayApplication {

    public static void main(String[] args) {
        SpringApplication.run(ApiGatewayApplication.class, args);
    }
}
```

#### 6.4.3 Gateway JwtConfig

**File: `api-gateway/src/main/java/org/viators/apigateway/config/JwtConfig.java`**

```java
package org.viators.apigateway.config;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import jakarta.annotation.PostConstruct;
import lombok.Getter;
import lombok.Setter;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import javax.crypto.SecretKey;
import java.util.Base64;
import java.util.Date;

/**
 * JWT configuration and parsing for the API Gateway.
 *
 * This class binds the JWT secret key from application.yaml and
 * provides methods to parse and validate tokens. Unlike the auth
 * service's JwtService, this class does NOT generate tokens —
 * it only verifies signatures and extracts claims.
 *
 * Why duplicate JWT parsing instead of sharing a library?
 * The gateway runs on WebFlux/Netty and has zero JPA or servlet
 * dependencies. Pulling in ops-common (which needs JPA and servlet)
 * just for 3 JWT methods would bloat the gateway's classpath.
 * For this small surface area, duplication is cleaner than coupling.
 */
@Component
@ConfigurationProperties(prefix = "application.security.jwt")
@Getter
@Setter
@Slf4j
public class JwtConfig {

    private String secretKey;
    private SecretKey signingKey;

    @PostConstruct
    private void init() {
        byte[] keyBytes = Base64.getDecoder().decode(secretKey);
        this.signingKey = Keys.hmacShaKeyFor(keyBytes);
        log.info("JWT signing key initialized for gateway token validation");
    }

    /**
     * Parses and cryptographically verifies a JWT token.
     * Throws JwtException subtypes if the token is invalid, expired, or tampered.
     */
    public Claims extractAllClaims(String token) {
        return Jwts.parser()
                .verifyWith(signingKey)
                .build()
                .parseSignedClaims(token)
                .getPayload();
    }

    public boolean isTokenExpired(Claims claims) {
        return claims.getExpiration().before(new Date());
    }
}
```

#### 6.4.4 JwtAuthenticationGlobalFilter

**File: `api-gateway/src/main/java/org/viators/apigateway/filter/JwtAuthenticationGlobalFilter.java`**

```java
package org.viators.apigateway.filter;

import io.jsonwebtoken.Claims;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.cloud.gateway.filter.GlobalFilter;
import org.springframework.core.Ordered;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.http.server.reactive.ServerHttpResponse;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import org.viators.apigateway.config.JwtConfig;
import reactor.core.publisher.Mono;

import java.util.List;

/**
 * Global filter that validates JWT tokens on every routed request.
 *
 * Implements GlobalFilter (reactive WebFlux API), which runs on every
 * request that matches a configured route. This is the gateway's
 * equivalent of the monolith's old JwtAuthenticationFilter.
 *
 * Flow:
 *   1. Check if path is public (auth, health) — skip if so
 *   2. Extract "Authorization: Bearer ..." header
 *   3. Parse and validate the JWT token
 *   4. Extract uuid, role, username from claims
 *   5. Inject as X-User-* headers on the downstream request
 *   6. Strip the Authorization header (BR-042)
 *
 * The downstream service never sees the raw JWT — only the
 * identity headers this filter injects.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class JwtAuthenticationGlobalFilter implements GlobalFilter, Ordered {

    private final JwtConfig jwtConfig;

    private static final List<String> PUBLIC_PATHS = List.of(
            "/api/v1/auth",
            "/actuator/health",
            "/eureka"
    );

    private static final String HEADER_USER_UUID = "X-User-UUID";
    private static final String HEADER_USER_ROLE = "X-User-Role";
    private static final String HEADER_USER_USERNAME = "X-User-Username";

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
        ServerHttpRequest request = exchange.getRequest();
        String path = request.getURI().getPath();

        // Step 1: Skip public paths.
        if (isPublicPath(path)) {
            log.debug("Public path accessed: {} — skipping JWT validation", path);
            return chain.filter(exchange);
        }

        // Step 2: Extract Authorization header.
        String authHeader = request.getHeaders().getFirst(HttpHeaders.AUTHORIZATION);

        if (authHeader == null || !authHeader.startsWith("Bearer ")) {
            log.debug("Missing or invalid Authorization header for: {} {}", request.getMethod(), path);
            return onUnauthorized(exchange);
        }

        // Step 3: Parse and validate the JWT.
        String token = authHeader.substring(7);

        try {
            Claims claims = jwtConfig.extractAllClaims(token);

            if (jwtConfig.isTokenExpired(claims)) {
                log.debug("Expired JWT for: {} {}", request.getMethod(), path);
                return onUnauthorized(exchange);
            }

            // Step 4: Extract user identity from claims.
            String username = claims.getSubject();
            String role = claims.get("role", String.class);
            String uuid = claims.get("uuid", String.class);

            if (username == null || role == null || uuid == null) {
                log.warn("JWT missing required claims for: {} {}", request.getMethod(), path);
                return onUnauthorized(exchange);
            }

            // Step 5: Inject identity headers and strip the JWT.
            ServerHttpRequest mutatedRequest = request.mutate()
                    .header(HEADER_USER_UUID, uuid)
                    .header(HEADER_USER_ROLE, role)
                    .header(HEADER_USER_USERNAME, username)
                    .headers(headers -> headers.remove(HttpHeaders.AUTHORIZATION))
                    .build();

            log.debug("JWT validated for user '{}' (uuid: {}, role: {})", username, uuid, role);
            return chain.filter(exchange.mutate().request(mutatedRequest).build());

        } catch (Exception ex) {
            log.debug("JWT validation failed for {} {}: {}", request.getMethod(), path, ex.getMessage());
            return onUnauthorized(exchange);
        }
    }

    @Override
    public int getOrder() {
        // Negative = run early, before routing filters.
        return -1;
    }

    private boolean isPublicPath(String path) {
        return PUBLIC_PATHS.stream().anyMatch(path::startsWith);
    }

    private Mono<Void> onUnauthorized(ServerWebExchange exchange) {
        ServerHttpResponse response = exchange.getResponse();
        response.setStatusCode(HttpStatus.UNAUTHORIZED);
        return response.setComplete();
    }
}
```

#### 6.4.5 Gateway application.yaml

**File: `api-gateway/src/main/resources/application.yaml`**

```yaml
server:
  port: 8080

spring:
  application:
    name: api-gateway

  cloud:
    gateway:
      server:
        webflux:
          # Route order matters — more specific routes FIRST.
          # Spring Cloud Gateway evaluates routes top-to-bottom
          # and uses the first match.
          routes:
            # Auth routes → ops-auth service.
            # Must come before the catch-all or they'd be
            # swallowed by Path=/**
            - id: auth-service-route
              uri: lb://auth-service
              predicates:
                - Path=/api/v1/auth/**

            # Everything else → monolith.
            # As you extract more microservices, add specific routes
            # ABOVE this catch-all:
            #   - /api/v1/notifications/** → lb://notification-service
            #   - /api/v1/payments/**      → lb://payment-service
            - id: monolith-route
              uri: lb://order-processing-system
              predicates:
                - Path=/**

# JWT secret — same key ops-auth uses to SIGN tokens.
# The gateway uses it to VERIFY signatures.
application:
  security:
    jwt:
      secret-key: ${JWT_SECRET_KEY}

# Eureka
eureka:
  client:
    service-url:
      defaultZone: ${EUREKA_URI:http://localhost:8761/eureka}
    registry-fetch-interval-seconds: 5
  instance:
    prefer-ip-address: true
    lease-renewal-interval-in-seconds: 5
    lease-expiration-duration-in-seconds: 10

logging:
  level:
    org.springframework.cloud.gateway: DEBUG
    com.netflix.eureka: WARN
    com.netflix.discovery: WARN
  file:
    name: logs/gateway-logs.log
```

#### 6.4.6 Gateway Dockerfile

**File: `api-gateway/Dockerfile`**

```dockerfile
FROM eclipse-temurin:25-jdk AS builder

RUN apt-get update && \
    apt-get install -y --no-install-recommends maven && \
    rm -rf /var/lib/apt/lists/*

WORKDIR /build

COPY pom.xml ./pom.xml
COPY api-gateway/pom.xml ./api-gateway/pom.xml

WORKDIR /build/api-gateway
RUN --mount=type=cache,target=/root/.m2 \
    mvn dependency:go-offline -B

COPY api-gateway/src ./src
RUN --mount=type=cache,target=/root/.m2 \
    mvn package -DskipTests -B

FROM eclipse-temurin:25-jdk

WORKDIR /app
COPY --from=builder /build/api-gateway/target/*.jar app.jar

EXPOSE 8080

ENTRYPOINT ["java", "-XX:+UseContainerSupport", "-XX:MaxRAMPercentage=75", "-jar", "app.jar"]
```

---

### 6.5 Refactor ops-monolith (Strip Auth, Trust Headers)

#### 6.5.1 Update ops-monolith POM

**Changes:**
- Add dependency on `ops-common`
- Remove JJWT dependencies entirely (the monolith no longer touches JWTs)
- Remove `<version>` tags from JJWT if you haven't already

```xml
<!-- ADD: ops-common dependency -->
<dependency>
    <groupId>org.viators</groupId>
    <artifactId>ops-common</artifactId>
</dependency>

<!-- REMOVE these three JJWT dependencies entirely:
    io.jsonwebtoken:jjwt-api
    io.jsonwebtoken:jjwt-impl
    io.jsonwebtoken:jjwt-jackson
-->
```

#### 6.5.2 Delete These Files from ops-monolith

These all moved to `ops-auth`:
- `auth/AuthService.java`
- `auth/AuthenticationController.java`
- `auth/CustomUserDetailsService.java`
- `auth/JwtAuthenticationFilter.java`
- `auth/JwtService.java`
- `auth/SecurityExceptionHandler.java`
- `auth/dto/request/LoginRequest.java`
- `auth/dto/request/RegisterUserRequest.java`
- `auth/dto/response/AuthenticationResponse.java`
- `config/JwtProperties.java`

These moved to `ops-common` (delete from monolith after updating imports):
- `common/BaseEntity.java`
- `common/enums/StatusEnum.java`
- `common/enums/UserRolesEnum.java`
- `exceptions/` (entire directory — all exception classes, DTOs, handler)

#### 6.5.3 Create GatewayPrincipal

**File: `ops-monolith/src/main/java/org/viators/orderprocessingsystem/auth/GatewayPrincipal.java`**

```java
package org.viators.orderprocessingsystem.auth;

import lombok.Getter;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.userdetails.UserDetails;
import org.viators.common.enums.UserRolesEnum;

import java.util.Collection;
import java.util.List;

/**
 * Lightweight principal constructed from gateway-injected headers.
 *
 * When a request arrives through the API Gateway, the gateway has already
 * validated the JWT and injected X-User-UUID, X-User-Role, and
 * X-User-Username headers. This class is built from those headers
 * and placed into the SecurityContext.
 *
 * Why not load UserT from the database?
 * That would require a DB round-trip per request just for authentication.
 * The gateway already verified the user's identity — we trust those headers.
 *
 * Compatibility with existing code:
 *   - @AuthenticationPrincipal GatewayPrincipal p → works
 *   - @AuthenticationPrincipal(expression = "uuid") String uuid → works
 *   - @PreAuthorize("hasRole('ADMIN')") → works
 *   - principal.isAdminUser() → works
 */
@Getter
@RequiredArgsConstructor
public class GatewayPrincipal implements UserDetails {

    private final String uuid;
    private final UserRolesEnum userRole;
    private final String username;

    @Override
    public Collection<? extends GrantedAuthority> getAuthorities() {
        return List.of(new SimpleGrantedAuthority("ROLE_".concat(userRole.name())));
    }

    /** Not used — the gateway already validated the JWT. */
    @Override
    public String getPassword() {
        return "";
    }

    /** Matches UserT.isAdminUser() so existing service code works. */
    public boolean isAdminUser() {
        return UserRolesEnum.ADMIN.equals(this.userRole);
    }
}
```

#### 6.5.4 Create GatewayAuthenticationFilter

**File: `ops-monolith/src/main/java/org/viators/orderprocessingsystem/auth/GatewayAuthenticationFilter.java`**

```java
package org.viators.orderprocessingsystem.auth;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.authentication.WebAuthenticationDetailsSource;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;
import org.viators.common.enums.UserRolesEnum;

import java.io.IOException;

/**
 * Authentication filter that constructs the SecurityContext from
 * gateway-injected headers instead of JWT tokens.
 *
 * This replaces JwtAuthenticationFilter. The old filter parsed JWT tokens;
 * this one reads HTTP headers that the API Gateway injected after
 * validating the JWT.
 *
 * Trust model: this filter trusts X-User-* headers unconditionally.
 * This is safe because the monolith's port (8888) is internal —
 * only the gateway routes traffic to it via the Docker network.
 *
 * BR-043: requests without X-User-UUID and X-User-Role headers
 * are not authenticated. SecurityConfig's .anyRequest().authenticated()
 * will reject them with 401.
 *
 * Flow:
 *   1. Read X-User-UUID, X-User-Role, X-User-Username from headers
 *   2. If missing → skip (SecurityConfig handles the 401)
 *   3. Build GatewayPrincipal from header values
 *   4. Create Authentication token and set in SecurityContext
 *   5. Continue the filter chain
 */
@Component
@Slf4j
public class GatewayAuthenticationFilter extends OncePerRequestFilter {

    private static final String HEADER_USER_UUID = "X-User-UUID";
    private static final String HEADER_USER_ROLE = "X-User-Role";
    private static final String HEADER_USER_USERNAME = "X-User-Username";

    @Override
    protected void doFilterInternal(
            HttpServletRequest request,
            HttpServletResponse response,
            FilterChain filterChain) throws ServletException, IOException {

        String uuid = request.getHeader(HEADER_USER_UUID);
        String role = request.getHeader(HEADER_USER_ROLE);
        String username = request.getHeader(HEADER_USER_USERNAME);

        if (uuid == null || role == null || username == null) {
            filterChain.doFilter(request, response);
            return;
        }

        if (SecurityContextHolder.getContext().getAuthentication() == null) {
            try {
                UserRolesEnum userRole = UserRolesEnum.valueOf(role);
                GatewayPrincipal principal = new GatewayPrincipal(uuid, userRole, username);

                var authToken = new UsernamePasswordAuthenticationToken(
                        principal,
                        null,
                        principal.getAuthorities()
                );

                authToken.setDetails(
                        new WebAuthenticationDetailsSource().buildDetails(request)
                );

                SecurityContextHolder.getContext().setAuthentication(authToken);

                log.debug("Authenticated user '{}' (uuid: {}, role: {}) from gateway headers",
                        username, uuid, role);

            } catch (IllegalArgumentException ex) {
                log.warn("Invalid role '{}' in gateway header — rejecting", role);
            }
        }

        filterChain.doFilter(request, response);
    }
}
```

#### 6.5.5 Update SecurityConfig

The monolith's `SecurityConfig` becomes much simpler — no JWT filter, no AuthenticationManager, no PasswordEncoder, no DaoAuthenticationProvider.

**File: `ops-monolith/.../config/SecurityConfig.java` — rewritten:**

```java
package org.viators.orderprocessingsystem.config;

import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.viators.orderprocessingsystem.auth.GatewayAuthenticationFilter;

/**
 * Security configuration for the monolith after auth extraction.
 *
 * Dramatically simplified from the pre-gateway version:
 * - No JWT validation (gateway handles this)
 * - No AuthenticationManager (moved to ops-auth)
 * - No PasswordEncoder (moved to ops-auth)
 * - No DaoAuthenticationProvider (moved to ops-auth)
 *
 * The only filter is GatewayAuthenticationFilter, which reads
 * the X-User-* headers the gateway injected and builds the
 * SecurityContext. Role-based authorization (@PreAuthorize, hasRole)
 * still works because GatewayPrincipal carries the user's role.
 */
@Configuration
@EnableWebSecurity
@EnableMethodSecurity
@RequiredArgsConstructor
public class SecurityConfig {

    private final GatewayAuthenticationFilter gatewayAuthenticationFilter;

    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http) {
        http
                .csrf(AbstractHttpConfigurer::disable)
                .authorizeHttpRequests(auth -> auth
                        // Auth endpoints no longer exist in the monolith —
                        // they're in ops-auth. But we keep health open.
                        .requestMatchers("/actuator/health").permitAll()
                        .requestMatchers("/api/v1/admins/**").hasRole("ADMIN")
                        .anyRequest().authenticated()
                )
                .sessionManagement(session ->
                        session.sessionCreationPolicy(SessionCreationPolicy.STATELESS)
                )
                .addFilterBefore(
                        gatewayAuthenticationFilter,
                        UsernamePasswordAuthenticationFilter.class
                );

        return http.build();
    }
}
```

#### 6.5.6 Update UserSecurity

**File: `ops-monolith/.../auth/UserSecurity.java`**

```java
package org.viators.orderprocessingsystem.auth;

import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;

/**
 * Security utility for ownership checks in @PreAuthorize expressions.
 * Updated to work with GatewayPrincipal (from gateway headers).
 */
@Component(value = "userSecurity")
public class UserSecurity {

    public boolean isSelf(String userUuid) {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();

        if (authentication == null || !authentication.isAuthenticated()) {
            return false;
        }

        Object principal = authentication.getPrincipal();

        if (principal instanceof GatewayPrincipal gp) {
            return gp.getUuid().equals(userUuid);
        }

        return false;
    }
}
```

#### 6.5.7 Update NotificationController and NotificationService

Change `@AuthenticationPrincipal UserT principal` to `@AuthenticationPrincipal GatewayPrincipal principal` in both the controller and service. The method calls (`principal.isAdminUser()`, `principal.getUuid()`) work unchanged because `GatewayPrincipal` has the same methods.

**NotificationController changes:**
```java
// Replace import:
// OLD: import org.viators.orderprocessingsystem.user.UserT;
// NEW:
import org.viators.orderprocessingsystem.auth.GatewayPrincipal;

// Replace all parameter types:
// OLD: @AuthenticationPrincipal UserT principal
// NEW: @AuthenticationPrincipal GatewayPrincipal principal
```

**NotificationService changes:**
```java
// Replace method signatures:
// OLD: public NotificationResponse getNotification(UserT principal, ...)
// NEW: public NotificationResponse getNotification(GatewayPrincipal principal, ...)

// OLD: public void markAsRead(UserT principal, ...)
// NEW: public void markAsRead(GatewayPrincipal principal, ...)
```

#### 6.5.8 Update All Imports

Bulk find-and-replace across the entire `ops-monolith` module:

| Old Import | New Import |
|-----------|-----------|
| `org.viators.orderprocessingsystem.common.BaseEntity` | `org.viators.common.entity.BaseEntity` |
| `org.viators.orderprocessingsystem.common.enums.StatusEnum` | `org.viators.common.enums.StatusEnum` |
| `org.viators.orderprocessingsystem.common.enums.UserRolesEnum` | `org.viators.common.enums.UserRolesEnum` |
| `org.viators.orderprocessingsystem.exceptions.*` | `org.viators.common.exception.*` |
| `org.viators.orderprocessingsystem.exceptions.dto.*` | `org.viators.common.exception.dto.*` |
| `org.viators.orderprocessingsystem.exceptions.handler.*` | `org.viators.common.exception.handler.*` |
| `org.viators.orderprocessingsystem.exceptions.ErrorCodeEnum` | `org.viators.common.enums.ErrorCodeEnum` |

---

### 6.6 Update docker-compose.yml

Add both new services:

```yaml
  # ── API Gateway ──────────────────────────────────────────────
  api-gateway:
    build:
      context: .
      dockerfile: api-gateway/Dockerfile
    container_name: ops-gateway
    ports:
      # 8080 is now the ONLY port clients connect to.
      - "8080:8080"
    environment:
      JWT_SECRET_KEY: ${JWT_SECRET_KEY:?error}
      EUREKA_URI: http://ops-eureka:8761/eureka
    depends_on:
      eureka-server:
        condition: service_healthy
    healthcheck:
      test: ["CMD", "wget", "--spider", "-q", "http://localhost:8080/actuator/health"]
      interval: 10s
      timeout: 5s
      retries: 5
      start_period: 30s
    networks:
      - ops-network

  # ── Auth Service ─────────────────────────────────────────────
  ops-auth:
    build:
      context: .
      dockerfile: ops-auth/Dockerfile
    container_name: ops-auth
    ports:
      # Internal port — not client-facing. Exposed for debugging.
      - "8070:8070"
    environment:
      JWT_SECRET_KEY: ${JWT_SECRET_KEY:?error}
      MYSQL_DATABASE: ${MYSQL_DATABASE:?error}
      MYSQL_USER: ${MYSQL_USER:?error}
      MYSQL_PASSWORD: ${MYSQL_PASSWORD:?error}
      EUREKA_URI: http://ops-eureka:8761/eureka
    depends_on:
      eureka-server:
        condition: service_healthy
      mysql:
        condition: service_healthy
    healthcheck:
      test: ["CMD", "wget", "--spider", "-q", "http://localhost:8070/actuator/health"]
      interval: 10s
      timeout: 5s
      retries: 5
      start_period: 30s
    networks:
      - ops-network
```

---

## 7. How Everything Connects: Request Lifecycle

### Login Flow (Public — No JWT Yet)

```
Client: POST http://localhost:8080/api/v1/auth/login
        {"username":"panos", "password":"SecurePass123!"}

  ┌─ api-gateway (:8080) ──────────────────────────────────┐
  │  GlobalFilter: "/api/v1/auth/login" is public → SKIP   │
  │  Route match: /api/v1/auth/** → lb://auth-service       │
  │  Eureka lookup → ops-auth at 172.18.0.6:8070            │
  │  Forward request unchanged                              │
  └─────────────────────────────────────────────────────────┘
                            │
                            ▼
  ┌─ ops-auth (:8070) ─────────────────────────────────────┐
  │  AuthenticationController.login()                       │
  │  → AuthService validates credentials via AuthManager    │
  │  → JwtService generates token with claims:              │
  │      sub: "panos", role: "CUSTOMER", uuid: "abc-123"    │
  │  → Returns: { token, uuid, username, email, role }      │
  └─────────────────────────────────────────────────────────┘

Response: 200 OK
{ "token": "eyJhbGci...", "uuid": "abc-123", ... }
```

### Authenticated Request

```
Client: GET http://localhost:8080/api/v1/products
        Authorization: Bearer eyJhbGci...

  ┌─ api-gateway (:8080) ──────────────────────────────────┐
  │  GlobalFilter:                                          │
  │    1. "/api/v1/products" is NOT public                  │
  │    2. Parse JWT → {sub:"panos", role:"CUSTOMER",        │
  │                     uuid:"abc-123"}                     │
  │    3. Token valid ✅                                    │
  │    4. Mutate request:                                   │
  │       + X-User-UUID: abc-123                            │
  │       + X-User-Role: CUSTOMER                           │
  │       + X-User-Username: panos                          │
  │       - Authorization: (stripped)                        │
  │  Route match: /** → lb://order-processing-system        │
  └─────────────────────────────────────────────────────────┘
                            │
                            ▼
  ┌─ ops-monolith (:8888) ─────────────────────────────────┐
  │  GatewayAuthenticationFilter:                           │
  │    → Reads X-User-UUID, X-User-Role, X-User-Username    │
  │    → Creates GatewayPrincipal                           │
  │    → Sets SecurityContext ✅                            │
  │  AuthorizationFilter: authenticated() → passes ✅       │
  │  ProductController → ProductService → returns data      │
  └─────────────────────────────────────────────────────────┘

Response: 200 OK
[{ "uuid": "...", "name": "Widget", ... }]
```

---

## 8. What Changes for Existing cURL Tests

The ONLY change is the port: `8888` → `8080`. Everything else works identically.

```bash
# Login (through gateway → routed to auth-service)
TOKEN=$(curl -s -X POST http://localhost:8080/api/v1/auth/login \
  -H "Content-Type: application/json" \
  -d '{"username": "panos", "password": "SecurePass123!"}' | jq -r '.token')

# Protected endpoint (through gateway → routed to monolith)
curl -X GET http://localhost:8080/api/v1/products \
  -H "Authorization: Bearer $TOKEN"

# No token → 401 from gateway
curl -X GET http://localhost:8080/api/v1/products

# Invalid token → 401 from gateway
curl -X GET http://localhost:8080/api/v1/products \
  -H "Authorization: Bearer invalid.token.here"
```

---

## 9. Configuration Reference

### JWT Secret Distribution

| Service | Has JWT Secret | Purpose |
|---------|---------------|---------|
| ops-auth | Yes | Signs tokens during login/register |
| api-gateway | Yes | Verifies token signatures |
| ops-monolith | **No** | Trusts gateway headers (BR-042) |

### Port Map

| Service | Port | Exposed to Clients? |
|---------|------|-------------------|
| api-gateway | 8080 | Yes — the ONLY client-facing port |
| ops-auth | 8070 | No — internal (exposed in dev for debugging) |
| ops-monolith | 8888 | No — internal |
| eureka-server | 8761 | Dashboard only |

### Header Contract (Gateway → Downstream)

| Header | Value | JWT Claim Source |
|--------|-------|-----------------|
| `X-User-UUID` | User's public UUID | `uuid` claim |
| `X-User-Role` | CUSTOMER, ADMIN | `role` claim |
| `X-User-Username` | Username | `sub` claim |
| `Authorization` | **STRIPPED** | Removed before forwarding |

---

## 10. Common Mistakes to Avoid

**1. Including spring-boot-starter-webmvc in the gateway**
The gateway uses WebFlux. These two starters conflict — Spring Boot fails on startup. The gateway runs on Netty, not Tomcat.

**2. Using the old `spring-cloud-starter-gateway` artifact**
Removed in Spring Cloud 2025.1.0. Use `spring-cloud-starter-gateway-server-webflux`.

**3. Using the old route prefix `spring.cloud.gateway.routes`**
The new prefix is `spring.cloud.gateway.server.webflux.routes`. The old prefix silently does nothing — routes won't register.

**4. Forgetting to add `uuid` to JWT claims in AuthService**
The gateway extracts uuid from the token claims. If it's missing, the filter returns 401. Both `registerUser()` and `login()` must include `"uuid", user.getUuid()` in the claims map.

**5. Leaving JwtAuthenticationFilter active in the monolith**
After replacing with GatewayAuthenticationFilter, delete the old filter. If both exist as @Component beans, Spring will register both, causing confusion.

**6. Not updating NotificationController/NotificationService**
These are the ONLY files that use `@AuthenticationPrincipal UserT`. If you miss them, you'll get ClassCastException at runtime.

**7. Using `ddl-auto: update` in ops-auth**
Two services both trying to alter the schema creates race conditions. The monolith owns schema evolution. The auth-service should use `validate`.

**8. Forgetting the ops-common import updates**
After moving BaseEntity, enums, and exceptions to ops-common, every file in the monolith that references them needs updated imports. Use your IDE's bulk find-and-replace.

**9. Putting auth-route AFTER the catch-all route**
`Path=/**` matches everything. If the monolith route comes first, auth requests go to the monolith (which no longer has auth endpoints) and return 404. Auth route must be first.

**10. Not exposing /actuator/health in ops-auth's SecurityConfig**
The docker-compose healthcheck calls this endpoint. If Spring Security blocks it, the container never becomes healthy, and the gateway (which depends on it) never starts.

---

## 11. Final Project Structure

```
order-processing-system/
├── pom.xml                                    ← MODIFIED: + 3 modules, JJWT management
├── docker-compose.yml                         ← MODIFIED: + api-gateway, ops-auth
├── dev_guides/
│   └── microservices/
│       └── api-gateway-guide.md               ← THIS GUIDE
│
├── ops-common/                                ← NEW MODULE (shared library)
│   ├── pom.xml
│   └── src/main/java/org/viators/common/
│       ├── entity/
│       │   └── BaseEntity.java
│       ├── enums/
│       │   ├── StatusEnum.java
│       │   ├── UserRolesEnum.java
│       │   └── ErrorCodeEnum.java
│       └── exception/
│           ├── BaseException.java
│           ├── ResourceNotFoundException.java
│           ├── DuplicateResourceException.java
│           ├── InvalidCredentialsException.java
│           ├── AccessDeniedException.java
│           ├── BusinessValidationException.java
│           ├── InvalidStateException.java
│           ├── dto/
│           │   ├── ErrorResponse.java
│           │   ├── FieldError.java
│           │   └── ValidationErrorResponse.java
│           └── handler/
│               └── GlobalExceptionHandler.java
│
├── ops-auth/                                  ← NEW MODULE (auth microservice)
│   ├── pom.xml
│   ├── Dockerfile
│   └── src/main/java/org/viators/auth/
│       ├── AuthServiceApplication.java
│       ├── AuthService.java
│       ├── AuthenticationController.java
│       ├── CustomUserDetailsService.java
│       ├── JwtService.java
│       ├── config/
│       │   ├── JwtProperties.java
│       │   └── SecurityConfig.java
│       ├── dto/
│       │   ├── request/
│       │   │   ├── LoginRequest.java
│       │   │   └── RegisterUserRequest.java
│       │   └── response/
│       │       └── AuthenticationResponse.java
│       └── user/
│           ├── UserT.java                     ← Auth-focused copy
│           └── UserRepository.java            ← Auth queries only
│
├── api-gateway/                               ← NEW MODULE (gateway)
│   ├── pom.xml
│   ├── Dockerfile
│   └── src/main/java/org/viators/apigateway/
│       ├── ApiGatewayApplication.java
│       ├── config/
│       │   └── JwtConfig.java
│       └── filter/
│           └── JwtAuthenticationGlobalFilter.java
│
├── eureka-server/                             ← UNCHANGED
│
└── ops-monolith/                              ← MODIFIED
    ├── pom.xml                                ← + ops-common dep, - JJWT deps
    └── src/main/java/org/viators/orderprocessingsystem/
        ├── auth/
        │   ├── GatewayPrincipal.java          ← NEW
        │   ├── GatewayAuthenticationFilter.java ← NEW
        │   └── UserSecurity.java              ← MODIFIED
        ├── config/
        │   └── SecurityConfig.java            ← REWRITTEN (much simpler)
        ├── notifications/
        │   ├── NotificationController.java    ← MODIFIED (GatewayPrincipal)
        │   └── NotificationService.java       ← MODIFIED (GatewayPrincipal)
        └── ... (all other packages unchanged)
```

### Summary of All Changes

| File | Action | What Changed |
|------|--------|-------------|
| `pom.xml` (root) | MODIFIED | + 3 modules, JJWT + ops-common in dependencyManagement |
| `docker-compose.yml` | MODIFIED | + api-gateway and ops-auth services |
| `ops-common/pom.xml` | NEW | Shared library dependencies |
| `ops-common/...` (13 files) | NEW (moved) | BaseEntity, enums, all exceptions, handler, DTOs |
| `ops-auth/pom.xml` | NEW | Auth service dependencies |
| `ops-auth/...` (12 files) | NEW (moved/adapted) | AuthService, Controller, JwtService, UserT, etc. |
| `api-gateway/pom.xml` | NEW | Gateway dependencies (WebFlux, Eureka, JJWT) |
| `api-gateway/...` (4 files) | NEW | Application, JwtConfig, GlobalFilter, config |
| `ops-monolith/pom.xml` | MODIFIED | + ops-common, - JJWT |
| `GatewayPrincipal.java` | NEW | Lightweight UserDetails from headers |
| `GatewayAuthenticationFilter.java` | NEW | Reads headers → SecurityContext |
| `SecurityConfig.java` (monolith) | REWRITTEN | Stripped to header-based auth only |
| `UserSecurity.java` | MODIFIED | Uses GatewayPrincipal |
| `NotificationController.java` | MODIFIED | GatewayPrincipal instead of UserT |
| `NotificationService.java` | MODIFIED | GatewayPrincipal instead of UserT |
| 10 files DELETED from monolith | DELETED | Auth classes moved to ops-auth |
| All monolith imports | MODIFIED | ops-common package paths |

---

## Acceptance Criteria Checklist

| Criterion | Status | Evidence |
|-----------|--------|----------|
| Standalone `api-gateway` runs on port 8080 | ✅ | server.port: 8080 in gateway yaml |
| All monolith routes reachable through gateway | ✅ | Path=/** catch-all route → lb://order-processing-system |
| JWT validation at the gateway | ✅ | JwtAuthenticationGlobalFilter validates before routing |
| X-User-UUID and X-User-Role injected | ✅ | GlobalFilter mutates request with identity headers |
| Public routes bypass auth filter | ✅ | PUBLIC_PATHS list with startsWith() check |
| api-gateway added to docker-compose.yml | ✅ | New service with healthcheck and Eureka dependency |
| All existing cURL tests still pass | ✅ | Only change: port 8888 → 8080 |
| BR-042: JWT secret not in monolith | ✅ | Monolith has zero JJWT dependencies |
| BR-043: Downstream rejects requests without headers | ✅ | GatewayAuthenticationFilter skips → SecurityConfig rejects |
