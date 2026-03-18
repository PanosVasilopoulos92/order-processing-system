# Microservices Architecture Guide — Phase 4

## For the Order Processing System — Spring Boot 4 / Java 25

---

## Table of Contents

1. [What Microservices Actually Are](#1-what-microservices-actually-are)
2. [Why the Monolith Needs to Change](#2-why-the-monolith-needs-to-change)
3. [The Core Problem: Distributed State](#3-the-core-problem-distributed-state)
4. [Service Boundaries — How to Draw the Lines](#4-service-boundaries--how-to-draw-the-lines)
5. [How Services Communicate](#5-how-services-communicate)
6. [The API Gateway](#6-the-api-gateway)
7. [What Changes in Your Codebase](#7-what-changes-in-your-codebase)
8. [Implementation: Extracting the Inventory Service](#8-implementation-extracting-the-inventory-service)
9. [Service Discovery and Configuration](#9-service-discovery-and-configuration)
10. [Distributed Tracing and Observability](#10-distributed-tracing-and-observability)
11. [The Phase 4 Migration Strategy](#11-the-phase-4-migration-strategy)
12. [Common Mistakes to Avoid](#12-common-mistakes-to-avoid)

---

## 1. What Microservices Actually Are

Before anything else, understand what the term actually means — because it's one of the most overused and misunderstood words in software.

A microservice is not "a small service." It is **a service that owns exactly one business capability and the data that capability requires.** Size is irrelevant. What matters is the boundary.

In your monolith, all of this lives in one deployable JAR:

```
order-processing-system.jar
├── product/        ← Product catalog management
├── order/          ← Order lifecycle
├── payment/        ← Payment processing
├── user/           ← Customer management
├── notification/   ← Customer notifications
└── saga/           ← Orchestration infrastructure
```

Every module shares one database, one JVM process, and one deployment pipeline. Changing one module means redeploying all of them.

In a microservices architecture, each capability becomes its own deployable unit:

```
inventory-service     (owns product catalog + stock)
order-service         (owns order lifecycle)
payment-service       (owns payment processing)
customer-service      (owns user accounts)
notification-service  (owns notifications + email)
api-gateway           (single entry point, JWT validation)
```

Each service has its own database, its own JVM, its own deployment pipeline, and its own team ownership.

### The Key Principle: Data Isolation

The most important rule in microservices is **each service owns its own data.** No service is allowed to read or write another service's database directly. If Order Service needs product information, it asks Inventory Service through an API — it does not join across databases.

This sounds simple. Its implications are not. Almost every difficulty in microservices traces back to this one rule.

---

## 2. Why the Monolith Needs to Change

Your monolith is well-structured. Before drawing service boundaries, you need to understand *why* the monolith becomes a problem as a system grows — otherwise you'll extract services prematurely and create complexity without benefit.

### The Deployment Problem

When everything is one JAR, a one-line change to `ProductService` requires redeploying the entire application — including the payment module, the notification module, and everything else. If payment processing is business-critical and you're in the middle of high-traffic hours, that deployment window is risky.

With microservices, you deploy only the service that changed. Payment Service keeps running while Inventory Service redeploys.

### The Scaling Problem

Your order placement flow is CPU and DB-intensive. Your notification service is relatively lightweight. In a monolith, you scale both together — you spin up 5 instances of the entire JAR even though only the order processing needs the extra capacity.

With microservices, you scale Order Service to 10 instances while Notification Service stays at 2.

### The Team Problem

As teams grow, a single codebase becomes a coordination bottleneck. Merge conflicts increase. One team's release blocks another. Microservices give teams independent ownership — each team can release on their own schedule.

### The Technology Problem

A monolith commits you to one technology stack for everything. If you discover that Python's machine learning libraries would be ideal for a future fraud detection module, you can't use them in a Java monolith without significant friction. With microservices, each service can use the best tool for its job.

### When NOT to Use Microservices

Microservices add real complexity: network calls fail, distributed transactions are hard, debugging spans multiple services, and operational overhead multiplies. For a team of 1-3 people or a product in early discovery, a well-structured monolith is almost always the better choice.

Your project reaches microservices through a deliberate learning progression — not because the system needs them yet, but because understanding how to build them is the goal.

---

## 3. The Core Problem: Distributed State

When you split the monolith, the hardest problems all involve state — specifically, keeping data consistent across services that each own a piece of it.

### The Network Is Not Reliable

In your monolith, a method call never fails because the network was down. When Order Service calls Inventory Service over HTTP, that call can fail because:

- The network is temporarily unavailable
- Inventory Service is restarting
- The request times out under load
- The response is lost in transit

Your code now has to handle all of these scenarios explicitly, on every inter-service call.

### Transactions Don't Cross Service Boundaries

This is the most fundamental change from monolith thinking. In your monolith:

```java
@Transactional
public void doEverything() {
    productRepo.save(product);   // database A
    orderRepo.save(order);       // database A (same!)
    paymentRepo.save(payment);   // database A (same!)
    // If anything fails, ALL three roll back atomically
}
```

In microservices, these are different databases. There is no `@Transactional` that spans them. If stock is deducted in Inventory Service and then Order Service crashes before creating the order, you have inconsistent state that `@Transactional` cannot fix.

This is exactly why you built the Saga pattern in Phase 3. In Phase 4, the saga is no longer just a design exercise — it becomes the *only* correctness mechanism you have.

### The Saga Pattern in Distributed Context

Your Phase 3 saga had this structure:

```
Step 1: ValidateOrderItemsStep  → ProductService.validateAndLoad()  (JPA)
Step 2: ReserveStockStep        → ProductService.reduceStock()      (JPA)
Step 3: CreateOrderStep         → OrderService.createPendingOrder() (JPA)
```

In Phase 4, steps 1 and 2 become HTTP calls to Inventory Service:

```
Step 1: ValidateOrderItemsStep  → HTTP GET  inventory-service/api/v1/products/validate
Step 2: ReserveStockStep        → HTTP POST inventory-service/api/v1/stock/reserve
Step 3: CreateOrderStep         → JPA (Order Service owns its own database)
```

Compensation also becomes HTTP calls:

```
Compensation for Step 2: HTTP POST inventory-service/api/v1/stock/release
Compensation for Step 3: JPA (cancel the order in Order Service's own DB)
```

The `SagaStep` interface doesn't change. The `SagaOrchestrator` doesn't change. Only the implementation inside each step changes. This is why you built it the way you did.

---

## 4. Service Boundaries — How to Draw the Lines

Poor service boundaries are the most common mistake in microservices adoption. Services that are too fine-grained cause excessive network calls and tight coupling. Services that are too coarse-grained are just distributed monoliths with none of the benefits.

### Domain-Driven Design: The Right Mental Model

The correct way to draw boundaries is by **business capability**, not by technical layer. A common mistake is splitting by layer: "let's have a Data Service, a Business Logic Service, and a Presentation Service." This creates chatty services that need each other to do anything.

Instead, ask: "What business capability does this service own, end to end?" Each service should be able to do its job without synchronously depending on another service for the happy path.

### Bounded Contexts in Your System

Using Domain-Driven Design terminology, each bounded context maps naturally to a service:

**Inventory Context** — owns the product catalog and stock levels. Knows what products exist, what they cost, and how many are available. Does not know about orders or customers.

**Order Context** — owns the order lifecycle. Knows about order states, line items, shipping addresses, and payment status. Stores product snapshots (name, price at time of order) so it doesn't need to call Inventory Service to render historical orders.

**Payment Context** — owns payment attempts and refunds. Knows about payment methods, amounts, and states. Does not know about products.

**Customer Context** — owns user accounts, credentials, and profile data. Knows about roles and addresses.

**Notification Context** — owns notification records and email delivery. Receives events from other services and creates notifications. Has no upstream dependencies — it only consumes events.

### The Strangler Fig Pattern

When migrating a monolith, you don't rewrite everything at once. You use the Strangler Fig pattern: extract one service at a time, routing traffic to the new service while the monolith still handles everything else.

```
Phase 4a: Extract Inventory Service
Phase 4b: Extract Order Service
Phase 4c: Extract Payment Service
Phase 4d: Extract Customer Service
Phase 4e: Notification Service (already fairly decoupled, migrate last)
```

Each extraction is a sprint. The monolith shrinks gradually. At no point is the system non-functional.

---

## 5. How Services Communicate

Services need to communicate. There are two fundamentally different ways to do this, and choosing the wrong one for a given scenario is a common source of fragility.

### Synchronous Communication: REST / HTTP

One service calls another and waits for a response. This is appropriate when:

- You need the result immediately to complete the current operation (e.g., ValidateOrderItemsStep needs to know if products are orderable before proceeding)
- The operation is a query (reads are naturally synchronous)
- A failure should halt the current operation

In Spring Boot, you use `RestClient` (Spring 6.1+) or the older `RestTemplate`/`WebClient`. For microservices, Spring Cloud OpenFeign provides a declarative HTTP client that looks like a local interface call.

```java
// Feign client for Inventory Service — looks like a local service
@FeignClient(name = "inventory-service")
public interface InventoryServiceClient {

    @GetMapping("/api/v1/products/{uuid}")
    ProductDetailsDto getProduct(@PathVariable String uuid);

    @PostMapping("/api/v1/stock/reserve")
    ReservationConfirmation reserveStock(@RequestBody ReserveStockRequest request);
}
```

The risk of synchronous communication is **temporal coupling**: if Inventory Service is down, Order Service's ability to place orders is down too. This is sometimes acceptable (you can't place an order for a product that doesn't exist) and sometimes not (you don't want notification delivery failures to block order placement).

### Asynchronous Communication: Message Broker (RabbitMQ)

One service publishes an event and continues. Other services consume the event at their own pace. This is appropriate when:

- The caller doesn't need the result immediately
- You want temporal decoupling (the consumer can be down and catch up later)
- Multiple consumers might be interested in the same event
- The operation is a side effect (notifications, audit logs, analytics)

You already have this infrastructure in place from Phase 2. Events like `ORDER_PLACED`, `PAYMENT_SUCCESS`, and `PAYMENT_REFUNDED` already flow through RabbitMQ.

### The Rule of Thumb

Use synchronous HTTP when the calling service genuinely cannot proceed without the answer. Use asynchronous messaging for everything else.

In your saga:
- Validation and stock reservation: **synchronous** (you need the answer before proceeding)
- Notifications: **asynchronous** (fire and forget — order placement shouldn't care if the email is sent)

---

## 6. The API Gateway

In a microservices architecture, clients don't call individual services directly. All external traffic flows through a single entry point: the API Gateway.

```
Client (Angular / Mobile)
        │
        ▼
    API Gateway
    ├── JWT validation (moved here from each service)
    ├── Rate limiting
    ├── Request routing
    └── Load balancing
        │
        ├──► inventory-service:8081
        ├──► order-service:8082
        ├──► payment-service:8083
        ├──► customer-service:8084
        └──► notification-service:8085
```

### What the Gateway Does

**Authentication:** JWT tokens are validated once at the gateway. Downstream services receive a trusted `X-User-UUID` and `X-User-Role` header — they don't re-validate the token, they trust the gateway.

**Routing:** `GET /api/v1/products/**` routes to inventory-service. `POST /api/v1/orders` routes to order-service.

**Cross-cutting concerns:** Rate limiting, CORS headers, request logging, and circuit breaking can all live here rather than duplicated in every service.

### Spring Cloud Gateway

Spring Cloud Gateway is the standard Spring choice. It integrates naturally with your existing Spring Boot setup:

```yaml
spring:
  cloud:
    gateway:
      routes:
        - id: inventory-service
          uri: lb://inventory-service   # lb:// = load-balanced via service discovery
          predicates:
            - Path=/api/v1/products/**,/api/v1/stock/**
        - id: order-service
          uri: lb://order-service
          predicates:
            - Path=/api/v1/orders/**
```

---

## 7. What Changes in Your Codebase

Before diving into implementation, it's useful to have a clear map of everything that needs to change.

### What Stays the Same

- The `SagaStep` interface
- The `SagaOrchestrator` logic
- `OrderPlacementSaga` structure (step list, context)
- Domain entity classes (they move to their new service)
- Business rules (they move with their domain)
- RabbitMQ event infrastructure (already decoupled)
- `NotificationService` (already consumes events, no upstream dependencies)

### What Changes Per Service Extraction

**1. Each service gets its own Spring Boot application**

```
order-processing-system/           ← becomes a parent POM / mono-repo root
├── api-gateway/                   ← new Spring Cloud Gateway app
├── inventory-service/             ← extracted from product/ package
├── order-service/                 ← extracted from order/ + saga/ packages
├── payment-service/               ← extracted from payment/ package
├── customer-service/              ← extracted from user/ + auth/ packages
└── notification-service/          ← extracted from notifications/ package
```

**2. Each service gets its own database schema**

The shared MySQL database splits into separate schemas (or separate database instances in production):

```yaml
# inventory-service/application.yaml
spring.datasource.url: jdbc:mysql://localhost:3306/inventory_db

# order-service/application.yaml
spring.datasource.url: jdbc:mysql://localhost:3306/order_db
```

**3. `@Transactional` boundaries shrink to single-service scope**

`OrderService.placeOrder()` loses its `@Transactional` wrapper around the saga (see Phase 4 Technical Debt doc, item 1). Each step manages its own transaction independently.

**4. JWT validation moves to the gateway**

`JwtAuthenticationFilter` is removed from each service. Services instead read the `X-User-UUID` header injected by the gateway. The JWT secret only lives in one place.

**5. Inter-service calls replace direct method calls**

`productService.validateAndLoad()` inside `ValidateOrderItemsStep` becomes a Feign client call to `inventory-service`. Same for `productService.reduceStock()` and `restoreStock()` in `ReserveStockStep`.

**6. Saga state must be persisted**

The `SagaOrchestrator` needs a `SagaState` entity persisted after each step. On restart, incomplete sagas are detected and either resumed or compensated.

---

## 8. Implementation: Extracting the Inventory Service

This section walks through a complete extraction of the `product/` module into a standalone `inventory-service`. Everything that changes for subsequent services follows the same pattern.

### 8.1 Project Structure

Create a new Spring Boot module `inventory-service` inside the mono-repo:

```
inventory-service/
├── pom.xml
└── src/main/java/org/viators/inventoryservice/
    ├── InventoryServiceApplication.java
    ├── product/
    │   ├── ProductT.java              ← moved from monolith
    │   ├── ProductRepository.java     ← moved
    │   ├── ProductService.java        ← moved (minus saga methods)
    │   └── ProductController.java     ← moved
    ├── stock/
    │   ├── StockController.java       ← NEW: exposes reduceStock / restoreStock as HTTP endpoints
    │   └── StockService.java          ← wraps ProductService stock methods
    └── config/
        └── SecurityConfig.java        ← trusts gateway headers, no JWT validation
```

### 8.2 Maven Dependencies

```xml
<parent>
    <groupId>org.springframework.boot</groupId>
    <artifactId>spring-boot-starter-parent</artifactId>
    <version>4.0.3</version>
</parent>

<dependencies>
    <dependency>
        <groupId>org.springframework.boot</groupId>
        <artifactId>spring-boot-starter-web</artifactId>
    </dependency>
    <dependency>
        <groupId>org.springframework.boot</groupId>
        <artifactId>spring-boot-starter-data-jpa</artifactId>
    </dependency>
    <!-- Service Discovery -->
    <dependency>
        <groupId>org.springframework.cloud</groupId>
        <artifactId>spring-cloud-starter-netflix-eureka-client</artifactId>
    </dependency>
</dependencies>
```

### 8.3 Application Entry Point

```java
package org.viators.inventoryservice;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.cloud.client.discovery.EnableDiscoveryClient;

@SpringBootApplication
@EnableDiscoveryClient
public class InventoryServiceApplication {
    public static void main(String[] args) {
        SpringApplication.run(InventoryServiceApplication.class, args);
    }
}
```

### 8.4 Application Configuration

```yaml
# inventory-service/src/main/resources/application.yaml

spring:
  application:
    name: inventory-service
  datasource:
    url: jdbc:mysql://${MYSQL_HOST:localhost}:3306/inventory_db
    username: ${MYSQL_USER}
    password: ${MYSQL_PASSWORD}
  jpa:
    hibernate:
      ddl-auto: validate
    show-sql: false

server:
  port: 8081

eureka:
  client:
    service-url:
      defaultZone: http://${EUREKA_HOST:localhost}:8761/eureka/
```

### 8.5 Security Configuration — Trusting the Gateway

This is a critical difference from the monolith. Inventory Service does not validate JWTs. It trusts that the gateway has already validated the token and injected user identity as headers. An internal security filter extracts the user from those headers.

```java
package org.viators.inventoryservice.config;

import jakarta.servlet.FilterChain;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

import java.util.List;

/**
 * Trusts the API Gateway's identity headers instead of validating JWT.
 *
 * The gateway validates the JWT and injects:
 *   X-User-UUID: the authenticated user's UUID
 *   X-User-Role: the user's role (e.g., ROLE_USER, ROLE_ADMIN)
 *
 * This service must only be reachable through the gateway.
 * Direct external access would bypass authentication entirely.
 * Network-level controls (VPC, Kubernetes NetworkPolicy) enforce this.
 */
public class GatewayAuthFilter extends OncePerRequestFilter {

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain filterChain)
            throws jakarta.servlet.ServletException, java.io.IOException {

        String userUuid = request.getHeader("X-User-UUID");
        String userRole = request.getHeader("X-User-Role");

        if (userUuid != null && userRole != null) {
            var auth = new UsernamePasswordAuthenticationToken(
                userUuid,
                null,
                List.of(new SimpleGrantedAuthority(userRole))
            );
            SecurityContextHolder.getContext().setAuthentication(auth);
        }

        filterChain.doFilter(request, response);
    }
}
```

```java
package org.viators.inventoryservice.config;

import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;

@Configuration
@EnableWebSecurity
@RequiredArgsConstructor
public class SecurityConfig {

    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
        http
            .csrf(AbstractHttpConfigurer::disable)
            .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
            .authorizeHttpRequests(auth -> auth
                .requestMatchers("/actuator/health").permitAll()
                .anyRequest().authenticated()
            )
            .addFilterBefore(new GatewayAuthFilter(),
                             UsernamePasswordAuthenticationFilter.class);

        return http.build();
    }
}
```

### 8.6 Stock Controller — New HTTP Endpoints

The monolith's `ProductService.reduceStock()` and `restoreStock()` were local method calls. They need to become HTTP endpoints that the Order Service's saga steps can call.

```java
package org.viators.inventoryservice.stock;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/stock")
@RequiredArgsConstructor
public class StockController {

    private final StockService stockService;

    /**
     * Reduces stock for a single product.
     * Called by ReserveStockStep during order placement saga.
     */
    @PostMapping("/reserve")
    public ResponseEntity<ReservationConfirmation> reserveStock(
            @Valid @RequestBody ReserveStockRequest request) {
        long deducted = stockService.reserveStock(request.productUuid(), request.quantity());
        return ResponseEntity.ok(new ReservationConfirmation(request.productUuid(), deducted));
    }

    /**
     * Restores stock for a single product.
     * Called by ReserveStockStep.compensate() during saga rollback.
     */
    @PostMapping("/release")
    public ResponseEntity<Void> releaseStock(
            @Valid @RequestBody ReleaseStockRequest request) {
        stockService.releaseStock(request.productUuid(), request.quantity());
        return ResponseEntity.noContent().build();
    }
}

// DTOs — use records for immutability
record ReserveStockRequest(
    @NotBlank String productUuid,
    @Min(1) long quantity
) {}

record ReleaseStockRequest(
    @NotBlank String productUuid,
    @Min(1) long quantity
) {}

record ReservationConfirmation(
    String productUuid,
    long quantityReserved
) {}
```

```java
package org.viators.inventoryservice.stock;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.viators.inventoryservice.product.ProductRepository;
import org.viators.inventoryservice.product.ProductT;
import org.viators.orderprocessingsystem.exceptions.ResourceNotFoundException;
import org.viators.orderprocessingsystem.exceptions.BusinessValidationException;

@Service
@RequiredArgsConstructor
public class StockService {

    private final ProductRepository productRepository;

    @Transactional
    public long reserveStock(String productUuid, long quantity) {
        ProductT product = productRepository.findByUuid(productUuid)
            .orElseThrow(() -> new ResourceNotFoundException("Product", "uuid", productUuid));

        if (product.getStockQuantity() < quantity) {
            throw new BusinessValidationException(
                "Insufficient stock for product: " + productUuid +
                ". Requested: " + quantity +
                ", Available: " + product.getStockQuantity()
            );
        }

        product.setStockQuantity(product.getStockQuantity() - quantity);
        productRepository.save(product);
        return quantity;
    }

    @Transactional
    public void releaseStock(String productUuid, long quantity) {
        productRepository.findByUuid(productUuid).ifPresent(product -> {
            product.setStockQuantity(product.getStockQuantity() + quantity);
            productRepository.save(product);
        });
    }
}
```

### 8.7 Updating the Saga Steps in Order Service

Now that Inventory Service exposes HTTP endpoints, the saga steps in Order Service need to call them. First, create a Feign client interface:

```java
package org.viators.orderservice.client;

import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.*;

/**
 * Declarative HTTP client for Inventory Service.
 *
 * Spring Cloud OpenFeign generates the implementation at runtime.
 * The name "inventory-service" resolves via Eureka service discovery —
 * no hardcoded URLs.
 *
 * Fallback behaviour (circuit breaker) should be added in Phase 4b.
 */
@FeignClient(name = "inventory-service")
public interface InventoryServiceClient {

    @GetMapping("/api/v1/products/validate")
    ValidatedProductsDto validateAndLoad(@RequestBody ValidateItemsRequest request);

    @PostMapping("/api/v1/stock/reserve")
    ReservationConfirmationDto reserveStock(@RequestBody ReserveStockRequestDto request);

    @PostMapping("/api/v1/stock/release")
    void releaseStock(@RequestBody ReleaseStockRequestDto request);
}
```

Then update `ReserveStockStep` to call the Feign client instead of `ProductService`:

```java
package org.viators.orderservice.saga.order;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.viators.orderservice.client.InventoryServiceClient;
import org.viators.orderservice.order.dto.request.CreateOrderRequest;
import org.viators.orderservice.saga.SagaContext;
import org.viators.orderservice.saga.SagaStep;

import java.util.HashMap;
import java.util.Map;

/**
 * Saga step 2: Reserve stock via HTTP call to Inventory Service.
 *
 * The only change from Phase 3 is the mechanism — instead of calling
 * ProductService directly (JPA), we call InventoryServiceClient (HTTP).
 * The saga-specific concerns (compensation tracking, context) are unchanged.
 */
@RequiredArgsConstructor
@Slf4j
public class ReserveStockStep implements SagaStep {

    private final CreateOrderRequest request;
    private final InventoryServiceClient inventoryClient;  // ← replaces ProductService
    private final SagaContext context;

    private final Map<String, Long> deductedQuantities = new HashMap<>();

    @Override
    public void execute() throws Exception {
        for (var item : request.orderItemRequests()) {
            // HTTP call to inventory-service instead of local JPA call
            var confirmation = inventoryClient.reserveStock(
                new ReserveStockRequestDto(item.productUuid(), item.quantity())
            );
            deductedQuantities.put(item.productUuid(), confirmation.quantityReserved());
            log.debug("[ReserveStockStep] Reserved {} unit(s) of product {} via Inventory Service",
                confirmation.quantityReserved(), item.productUuid());
        }
        log.info("[ReserveStockStep] Stock reserved for {} product(s)", deductedQuantities.size());
    }

    @Override
    public void compensate() {
        log.info("[ReserveStockStep] Compensating — releasing stock for {} product(s)",
            deductedQuantities.size());

        for (var entry : deductedQuantities.entrySet()) {
            try {
                // HTTP call to inventory-service compensation endpoint
                inventoryClient.releaseStock(
                    new ReleaseStockRequestDto(entry.getKey(), entry.getValue())
                );
                log.info("[ReserveStockStep] Released {} unit(s) of product {}",
                    entry.getValue(), entry.getKey());
            } catch (Exception e) {
                log.error("[ReserveStockStep] Failed to release stock for product {}: {}",
                    entry.getKey(), e.getMessage(), e);
            }
        }
    }

    @Override
    public String name() {
        return "ReserveStockStep";
    }
}
```

Notice what did NOT change: the `deductedQuantities` tracking, the compensation logic structure, the logging pattern, and the `SagaStep` interface. The saga infrastructure you built in Phase 3 absorbs the Phase 4 change cleanly.

### 8.8 API Gateway Configuration

Add routing rules for the new Inventory Service:

```yaml
# api-gateway/src/main/resources/application.yaml

spring:
  application:
    name: api-gateway
  cloud:
    gateway:
      routes:
        - id: inventory-service
          uri: lb://inventory-service
          predicates:
            - Path=/api/v1/products/**,/api/v1/stock/**
          filters:
            - name: AuthenticationFilter   # validates JWT, injects X-User-UUID

        - id: order-service
          uri: lb://order-service
          predicates:
            - Path=/api/v1/orders/**
          filters:
            - name: AuthenticationFilter

        # Public routes — no authentication filter
        - id: auth-routes
          uri: lb://customer-service
          predicates:
            - Path=/api/v1/auth/**
```

### 8.9 Running Locally with Docker Compose

Add the new services to `docker-compose.yml`:

```yaml
services:
  # ── Service Discovery ──────────────────────────────────
  eureka-server:
    image: springcloud/eureka
    ports:
      - "8761:8761"

  # ── API Gateway ────────────────────────────────────────
  api-gateway:
    build: ./api-gateway
    ports:
      - "8080:8080"
    environment:
      - EUREKA_HOST=eureka-server
      - JWT_SECRET_KEY=${JWT_SECRET_KEY}
    depends_on:
      - eureka-server

  # ── Inventory Service ───────────────────────────────────
  inventory-service:
    build: ./inventory-service
    ports:
      - "8081:8081"
    environment:
      - MYSQL_HOST=mysql
      - MYSQL_USER=${MYSQL_USER}
      - MYSQL_PASSWORD=${MYSQL_PASSWORD}
      - EUREKA_HOST=eureka-server
    depends_on:
      - mysql
      - eureka-server

  # ── Order Service ───────────────────────────────────────
  order-service:
    build: ./order-service
    ports:
      - "8082:8082"
    environment:
      - MYSQL_HOST=mysql
      - MYSQL_USER=${MYSQL_USER}
      - MYSQL_PASSWORD=${MYSQL_PASSWORD}
      - EUREKA_HOST=eureka-server
      - RABBITMQ_HOST=rabbitmq
    depends_on:
      - mysql
      - eureka-server
      - rabbitmq
```

---

## 9. Service Discovery and Configuration

### Why Service Discovery?

In a monolith, services call each other via method calls — no addresses needed. In microservices, Service A needs to know where Service B is running. You could hardcode `http://localhost:8081` but that breaks immediately when you run multiple instances or when services restart on different ports.

**Eureka** (Netflix's service registry) solves this. Each service registers itself on startup:

```
inventory-service → registers as "inventory-service" at 192.168.1.5:8081
order-service     → registers as "order-service" at 192.168.1.6:8082
```

When Order Service needs to call Inventory Service, it asks Eureka: "Where is inventory-service?" Eureka returns the current address. The `lb://inventory-service` prefix in gateway routes means "load-balance across all registered instances of this service name."

### Centralized Configuration with Spring Cloud Config

As you add services, each one has its own `application.yaml`. Shared configuration (RabbitMQ credentials, common timeouts, feature flags) ends up duplicated across services. Spring Cloud Config Server centralises this:

```
config-server/
└── config-repo/
    ├── application.yaml          ← shared by all services
    ├── inventory-service.yaml    ← inventory-specific overrides
    └── order-service.yaml        ← order-specific overrides
```

Each service fetches its configuration from the config server on startup rather than bundling it in the JAR. This means configuration changes don't require redeployment.

---

## 10. Distributed Tracing and Observability

In a monolith, a single request is one log thread in one process. In microservices, a single user request spans multiple services. When something goes wrong, you need to trace what happened across all of them.

### The Problem

```
User places order → 5 services are involved → error in step 3
Which service? Which instance? What was the state?
```

Without tooling, you'd grep logs across 5 different processes, correlate timestamps, and piece together what happened. This is painful.

### Spring Cloud Sleuth + Zipkin

Spring Cloud Sleuth adds a **trace ID** to every request. All log lines within one user request share the same trace ID, across all services. Zipkin visualises the traces as a timeline.

```java
// Every log line automatically gets decorated:
// [order-service,traceid=abc123,spanid=def456] Executing step: ValidateOrderItemsStep
// [inventory-service,traceid=abc123,spanid=ghi789] Validating product: prod-uuid-1
```

The `traceid=abc123` is the same across both log lines — you can filter by trace ID to see the complete request journey across all services.

Add to each service's `pom.xml`:

```xml
<dependency>
    <groupId>org.springframework.cloud</groupId>
    <artifactId>spring-cloud-starter-sleuth</artifactId>
</dependency>
<dependency>
    <groupId>org.springframework.cloud</groupId>
    <artifactId>spring-cloud-sleuth-zipkin</artifactId>
</dependency>
```

---

## 11. The Phase 4 Migration Strategy

Extract services in this order. Each step is independently deployable.

### Step 1: Add Eureka Server + API Gateway (Sprint 7)

Before extracting any service, put the scaffolding in place. The monolith runs unchanged behind the gateway. Clients now call `localhost:8080` (gateway) instead of `localhost:8080` (monolith directly). Nothing else changes.

This validates the gateway routing before any service extraction happens.

### Step 2: Extract Inventory Service (Sprint 8)

Move the `product/` package to a standalone service. Update the saga steps to use Feign clients instead of direct `ProductService` calls. This is the most impactful extraction because it breaks the shared-database assumption for the first time.

This is what section 8 of this guide covers in detail.

### Step 3: Extract Customer Service (Sprint 9)

Move the `user/` and `auth/` packages. JWT validation moves from each service to the gateway. This is the second most impactful extraction.

### Step 4: Extract Order Service (Sprint 10)

Move the `order/` and `saga/` packages. The saga orchestration now lives here. Remove `@Transactional` from `placeOrder()` as documented in the technical debt doc.

### Step 5: Extract Payment Service (Sprint 11)

Move the `payment/` package. At this point, the monolith contains only shared infrastructure code (exceptions, base entities, common enums).

### Step 6: Extract Notification Service (Sprint 12)

Move the `notifications/` package. This is the simplest extraction because the notification service already only consumes events — it has no upstream HTTP dependencies.

### Step 7: Decommission the Monolith (Sprint 13)

By this point, the monolith is empty. Delete it.

---

## 12. Common Mistakes to Avoid

**1. Sharing a database between services**

If two services read and write the same database table, they are not microservices — they are a distributed monolith. You get all the complexity of distributed systems with none of the independence benefits. Draw the boundary before writing the first line of code.

**2. Making everything synchronous**

Reaching for HTTP whenever services need to communicate leads to tight coupling and cascading failures. If Inventory Service is slow, it shouldn't make the entire order placement slow — but it will if every step in the saga is synchronous and sequential. Prefer events for side effects.

**3. Fine-grained services too early**

"Let's have a separate service for each table" is a common overreaction. A service that manages one database table and has two endpoints is not a microservice — it's overhead. Start with coarse-grained services (Inventory, Order, Payment) and split only when a genuine reason to split emerges.

**4. Calling services synchronously in a chain**

```
Order Service → Payment Service → Fraud Service → Risk Service
```

If any service in the chain is slow or down, the entire chain stalls. If you find yourself building chains of synchronous calls, reconsider whether some of those calls should be asynchronous or whether your service boundaries are wrong.

**5. Not testing failure scenarios**

Every HTTP call can fail. Every message can be lost. If your tests never test what happens when Inventory Service returns a 503, you will discover the answer in production. Write tests for network failures, timeouts, and partial failures explicitly.

**6. Skipping the outbox pattern**

Publishing events directly after a DB commit without the outbox pattern means events can be lost on crash. The technical debt doc covers this. Implement it before going to production.

**7. Building distributed transactions instead of sagas**

When data needs to be consistent across services, the answer is not a distributed transaction (two-phase commit) — it's a saga with compensating transactions. Distributed transactions are notoriously fragile and defeat the independence that microservices provide. Your Saga pattern from Phase 3 is exactly the right foundation for Phase 4.

---

## Appendix: Phase 4 Architecture Diagram

```
External Clients
       │
       ▼
┌─────────────────────────────────────┐
│           API Gateway :8080         │
│  - JWT validation                   │
│  - Rate limiting                    │
│  - Routing                          │
└──────┬──────┬──────┬──────┬─────────┘
       │      │      │      │
       ▼      ▼      ▼      ▼
 inventory  order  payment customer
 :8081     :8082   :8083   :8084
    │         │       │       │
    │         └───────┴───────┘
    │               │
    └───────────────┤
                    ▼
              notification
              :8085
                    ▲
                    │
             ┌──────┴──────┐
             │   RabbitMQ  │
             └─────────────┘
                    ▲
       Events published by all services

 ┌─────────────────────────────────────┐
 │          Eureka Server :8761        │
 │  All services register here         │
 └─────────────────────────────────────┘

 ┌─────────────────────────────────────┐
 │        Config Server :8888          │
 │  Centralised configuration          │
 └─────────────────────────────────────┘
```
