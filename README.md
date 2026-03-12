# Order Processing System

An e-commerce backend that handles the full order lifecycle: customers browse a product catalog, place orders, pay, and receive confirmations. Behind the scenes, the system coordinates payment processing, inventory management, and notifications — first as a modular monolith, then progressively extracted into event-driven services.

## Learning Goal

This project follows a deliberate **monolith-first** progression. The domain is built and understood as a single deployable unit before any distributed complexity is introduced. Each phase adds a new architectural layer on top of a working foundation:

| Phase | Architecture | Status |
|-------|-------------|--------|
| **Phase 1** | Modular monolith | ✅ Complete |
| **Phase 2** | Introduce messaging (RabbitMQ) | ✅ Complete|
| **Phase 3** | Saga pattern | 🔄 In Progress |
| **Phase 4** | Service extraction + API Gateway | ⏳ Not started |
| **Phase 5** | CQRS / Event Sourcing | ⏳ Not started |

## Tech Stack

| Layer | Technology |
|-------|------------|
| Language | Java 25 |
| Framework | Spring Boot 4.0.3 |
| Security | Spring Security + JWT (JJWT 0.13) |
| Persistence | Spring Data JPA / Hibernate |
| Database | MySQL 8 (primary), PostgreSQL (supported) |
| Messaging | RabbitMQ (Spring AMQP) |
| Containerization | Docker Compose |

## Features

- **User Management** — Registration, authentication, profile updates, password changes, soft deactivation
- **Product Catalog** — Full CRUD with soft-delete, reactivation, and dynamic filtering via JPA Specifications
- **Order Placement** — Atomic order creation with line items and total amount calculation
- **Order Lifecycle** — State machine enforcement: `PENDING → CONFIRMED → SHIPPED → DELIVERED`, with customer-initiated cancellation
- **Payment Processing** — Payment submission per order, full payment history, automatic refunds on cancellation
- **Event Publishing** — Domain events published to RabbitMQ on every order and payment state change

## Getting Started

### Prerequisites

- Java 25
- Maven 3.9+
- Docker and Docker Compose
- IDE with Lombok support

### Run

```bash
# Clone and navigate
git clone https://github.com/PanosVasilopoulos92/order-processing-system.git
cd order-processing-system

# Create a .env file with the required variables (see below), then:
docker compose up -d
mvn spring-boot:run
```

**Required `.env` variables:**

```env
MYSQL_ROOT_PASSWORD=
MYSQL_DATABASE=
MYSQL_USER=
MYSQL_PASSWORD=
RABBITMQ_USER=
RABBITMQ_PASSWORD=
JWT_SECRET_KEY=
```

`docker compose up -d` starts MySQL on port `3306`, RabbitMQ on ports `5672` / `15672`, and MailHog on ports `1025` / `8025`.

The API is available at: `http://localhost:8888/api/v1`

## Project Structure

```
src/main/java/org/viators/orderprocessingsystem/
├── auth/            # Login, registration, JWT issuance
├── user/            # User CRUD and lifecycle management
├── product/         # Product catalog with search and soft-delete
├── order/           # Order placement, state machine, history
├── orderitem/       # Line items attached to orders
├── payment/         # Payment submission and history
├── messaging/       # RabbitMQ events, publishers, listeners
├── common/          # BaseEntity, enums, shared services
├── config/          # Security, JPA auditing, RabbitMQ configuration
└── exceptions/      # Custom exceptions and GlobalExceptionHandler
```

## Architecture

Feature-based (vertical slice) package structure — each domain contains its own entity, repository, service, controller, and DTOs.

Key patterns:
- Constructor injection throughout (no field `@Autowired`)
- DTOs for all API contracts (entities never exposed directly)
- `BaseEntity` with audit fields (`createdAt`, `updatedAt`, `createdBy`, `updatedBy`)
- Optimistic locking via `@Version` on every entity
- Soft deletes via a `status` flag — no hard deletes
- JPA Specifications for dynamic, composable product filtering
- Global exception handling with consistent structured error responses
- Ownership enforcement via `UserSecurity` and `@PreAuthorize` expressions
- Non-blocking event publishing — RabbitMQ failures are logged, never propagated


## License

MIT
