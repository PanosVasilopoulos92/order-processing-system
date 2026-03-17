# The Saga Pattern — Distributed Transaction Management

## For the Order Processing System — Spring Boot 4 / Java 25

---

## Table of Contents

1. [Understanding the Big Picture](#1-understanding-the-big-picture)
2. [Why ACID Transactions Break Across Services](#2-why-acid-transactions-break-across-services)
3. [The Saga Pattern: Core Concept](#3-the-saga-pattern-core-concept)
4. [Choreography vs. Orchestration — Choosing Your Approach](#4-choreography-vs-orchestration--choosing-your-approach)
5. [Compensation: The Art of Undoing](#5-compensation-the-art-of-undoing)
6. [Implementation: Layer by Layer](#6-implementation-layer-by-layer)
   - 6.1 [SagaStep — The Unit of Work](#61-sagastep--the-unit-of-work)
   - 6.2 [SagaOrchestrator — The Conductor](#62-sagaorchestrator--the-conductor)
   - 6.3 [Step 1 — ValidateOrderItemsStep](#63-step-1--validateorderitemsstep)
   - 6.4 [Step 2 — ReserveStockStep](#64-step-2--reservestockstep)
   - 6.5 [Step 3 — CreateOrderStep](#65-step-3--createorderstep)
   - 6.6 [SagaContext — Passing State Between Steps](#66-sagacontext--passing-state-between-steps)
   - 6.7 [OrderPlacementSaga — Wiring the Steps](#67-orderplacementsaga--wiring-the-steps)
   - 6.8 [Updating OrderService — The Entry Point](#68-updating-orderservice--the-entry-point)
7. [How Everything Connects: The Full Execution Flow](#7-how-everything-connects-the-full-execution-flow)
8. [Failure Scenarios: What Actually Happens](#8-failure-scenarios-what-actually-happens)
9. [The Bigger Picture: Sagas in Phase 4 (Service Extraction)](#9-the-bigger-picture-sagas-in-phase-4-service-extraction)
10. [Pattern Checklist and Common Mistakes](#10-pattern-checklist-and-common-mistakes)

---

## 1. Understanding the Big Picture

Before writing any code, you need to understand **what problem the Saga pattern actually solves** — because if you don't feel the pain first, the solution will seem like unnecessary complexity.

### Where You Are Right Now

Your order placement (`OrderService.placeOrder()`) currently works like this:

```java
@Transactional
public OrderDetailsResponse placeOrder(CreateOrderRequest request, String customerUuid) {
    // 1. Validate products
    // 2. Reduce stock for each product
    // 3. Create the order
    // 4. Create order items with price snapshots
    // ... all inside a single @Transactional boundary
}
```

This is clean, safe, and completely correct — **for a monolith**. The `@Transactional` annotation wraps everything in a single ACID database transaction. If step 3 fails, step 2 is automatically rolled back. The database handles all of this for you via its transaction manager.

So why change it?

### The Phase 4 Problem

Look at the architecture roadmap. Phase 4 involves extracting services: Inventory becomes its own service with its own database. Orders becomes its own service with its own database. Payment becomes its own service with its own database.

When that happens, this call:

```java
// These two lines will hit DIFFERENT databases in Phase 4:
inventoryRepository.save(updatedProduct);  // → Inventory DB
orderRepository.save(newOrder);           // → Orders DB
```

You can no longer wrap both in `@Transactional`. There is no transaction manager that spans two separate databases owned by two separate services. If `orderRepository.save()` fails after `inventoryRepository.save()` succeeds, stock is permanently deducted but no order exists. The customer is debited stock they never received an order for.

**This is the distributed transaction problem.** It's one of the hardest problems in distributed systems, and the Saga pattern is the standard industry solution for it.

### The Insight Behind Sagas

The key insight is: **you cannot prevent partial failures in a distributed system, but you can design your system to recover from them.**

Instead of asking "how do I make all of this atomic?", a Saga asks: "how do I undo what I've already done if something goes wrong later?"

Every step that has a side effect also has a **compensating action** — a defined undo operation. The saga orchestrator executes steps forward until success, or executes compensating actions backward on failure.

Think of it like a construction crew:

- **Forward:** Pour foundation → Frame walls → Install roof → Wire electricity
- **Compensation (if electricity fails):** Undo electricity → Take off the roof → Remove the frame → Fill the foundation

You can't magically make four separate crews work as one atomic unit. But you can define what "undoing" each step looks like, and if something fails, work backwards.

---

## 2. Why ACID Transactions Break Across Services

Before understanding the solution, let's understand what you lose when you cross a service boundary.

### What ACID Gives You (and Why It's Precious)

| Property | What it means | How the DB provides it |
|---|---|---|
| **Atomicity** | All operations succeed, or none do | Rollback on failure |
| **Consistency** | Data moves between valid states | Constraints + triggers |
| **Isolation** | Concurrent transactions don't see each other's partial state | Locking / MVCC |
| **Durability** | Committed data survives crashes | Write-ahead log |

When you call `@Transactional` in Spring, Hibernate opens a connection, starts a transaction, runs your SQL, and either commits or rolls back — all through a single database connection. The database engine enforces all four ACID properties.

### What Breaks at a Service Boundary

```
Service A                         Service B
(Orders DB)                       (Inventory DB)
    |                                   |
    |-- INSERT INTO orders ...          |
    |                                   |-- UPDATE products SET stock = stock - 1 ...
    |                                   |
    |-- [ FAILS HERE ]                  |-- [ Already committed! ]
    |                                   |
    |-- Rollback orders table           |-- No rollback. Stock is gone.
```

There is no shared transaction coordinator between two separate databases that can guarantee atomicity. Two-Phase Commit (2PC) protocols exist, but they are slow, complex, and a single point of failure — which is why the industry has moved away from them.

### The CAP Theorem Framing

In distributed systems, you cannot simultaneously have Consistency, Availability, and Partition tolerance. You must choose two. The Saga pattern embraces **eventual consistency** instead of strong consistency — the system will arrive at a consistent state, but not instantly and not via a single atomic operation.

This is an important mental model shift: **Saga-based systems are eventually consistent, not immediately consistent.** A customer who places an order exists in a "saga in progress" state until all steps complete. This is fine for most business scenarios and is how the real world actually works (a real warehouse picks items and packs boxes over time — it's not atomic either).

---

## 3. The Saga Pattern: Core Concept

A Saga is a sequence of local transactions. Each local transaction:
1. Updates data in a single service/database.
2. Publishes an event or message to trigger the next step.
3. Has a corresponding **compensating transaction** that logically undoes its effects.

### The Formal Model

Given a Saga with steps T1, T2, T3, ... Tn:

```
Success path:   T1 → T2 → T3 → ... → Tn  (all succeed)
Failure at T3:  T1 → T2 → T3 (FAIL) → C2 → C1  (compensate in reverse)
```

Where C2 is the compensation for T2, and C1 is the compensation for T1. T3 itself failed, so we don't need to compensate it (it didn't commit). We compensate the steps that *did* successfully commit before the failure.

### Your Order Placement Saga

Concretely for the Order Processing System, the saga steps are:

```
T1: Validate order items        C1: None (read-only, nothing to undo)
T2: Reserve stock               C2: Restore stock for all reserved products
T3: Create order & line items   C3: Cancel order (set status CANCELLED)
```

If T3 fails:
```
T1 ✅ → T2 ✅ → T3 ❌ → C2 (restore stock) → C1 (no-op)
```

If T2 fails (e.g. out of stock):
```
T1 ✅ → T2 ❌ → C1 (no-op)
```

### What "Local Transaction" Means in Your Monolith

Right now you're still in a monolith — all steps hit the same database. So why bother with the Saga pattern now, when `@Transactional` still works?

Two reasons:

1. **Design for future extraction.** When you extract Inventory into its own service in Phase 4, the Saga structure is already there. You only change the *implementation* of each step (HTTP call instead of JPA call), not the orchestration logic.

2. **Explicit compensation logic.** Even in a monolith, making compensation explicit is valuable. Today, stock restoration on order cancellation lives in `OrderService`. With a Saga, the compensation for "reserve stock" is defined right next to the forward operation — they're a matched pair. This makes the system's failure modes explicit and testable.

---

## 4. Choreography vs. Orchestration — Choosing Your Approach

There are two fundamentally different ways to implement a Saga, and you must understand both to appreciate why you're using one over the other.

### Choreography — "React to Events"

In choreography, there is no central coordinator. Each service listens for events and reacts to them independently.

```
OrderService publishes: OrderInitiated
    ↓
InventoryService listens, reserves stock, publishes: StockReserved
    ↓
PaymentService listens, charges card, publishes: PaymentProcessed
    ↓
OrderService listens, confirms order, publishes: OrderConfirmed
```

Compensation works in reverse: if PaymentService publishes `PaymentFailed`, InventoryService listens and restores stock, then publishes `StockRestored`, etc.

**Pros:** No single point of failure. Services are decoupled. Easy to add new participants (just subscribe to the relevant event).

**Cons:** The business flow is implicit — it lives in the events and subscriptions, not in one place. Debugging a failed saga requires tracing events across multiple services. You get what's called "spaghetti choreography" as the system grows.

### Orchestration — "Follow the Conductor"

In orchestration, a central Saga Orchestrator knows the entire sequence. It calls each participant directly and coordinates the flow.

```
SagaOrchestrator:
    → calls InventoryService.reserveStock()
    → calls OrderService.createOrder()
    → calls PaymentService.chargeCard()

On failure at PaymentService:
    → calls OrderService.cancelOrder()  (compensation)
    → calls InventoryService.restoreStock()  (compensation)
```

**Pros:** The business flow is explicit and readable in one place. Easy to debug. Easy to add timeouts and retries. State is centralised.

**Cons:** The orchestrator becomes a coordination hub — it needs to know about all participants. In a distributed system, the orchestrator itself can be a bottleneck or point of failure (mitigated by making it stateless or persisting saga state).

### Which One You're Building

For the Order Processing System, you're building an **orchestration-based saga**. The reasons are:

- **You're in a monolith.** Choreography's main benefit (decoupled services reacting to events) doesn't pay off yet. You already have RabbitMQ for external notifications, but the saga itself needs to be synchronous and readable.
- **The flow is linear.** Validate → Reserve Stock → Create Order. There are no parallel branches or complex conditions that would benefit from choreography.
- **Debuggability matters for learning.** Orchestration puts the entire flow in one readable class. When something goes wrong, you read one file and understand exactly what happened.
- **Phase 4 migration is simpler.** When you extract services, you update each step's implementation (swap JPA calls for HTTP/event calls), but the orchestrator doesn't change.

---

## 5. Compensation: The Art of Undoing

Compensation is the hardest part of the Saga pattern to think about correctly. Let's invest time here because misunderstanding it leads to buggy, inconsistent systems.

### Compensation ≠ Rollback

A database rollback is instant and leaves no trace — as if the operation never happened. Compensation is different: it is a **new business operation that logically reverses a previous one**. Both operations are persisted.

Example: if step 2 "reserved stock" by setting `product.stockQuantity = 50 - 3 = 47`, the compensation doesn't reach into the database and flip it back. The compensation *adds 3 back*: `product.stockQuantity = 47 + 3 = 50`. Both the deduction and the restoration are permanent records of what happened.

This distinction matters because:

1. **Compensation can fail.** A rollback cannot fail (the database guarantees it). A compensation is business logic — it can throw an exception, and you need to handle that.

2. **Compensation is visible.** If you add an audit log, you'll see "stock deducted at 14:00:00" and "stock restored at 14:00:01". This is actually desirable — it gives you a complete history.

3. **Compensation must be idempotent.** If the system crashes between a step and its compensation, and the compensation is retried, running it twice must not cause a double-restoration. This is critical for reliability.

### The "No Compensation Needed" Cases

Not every step needs a compensation. Read-only operations have no side effects to undo:

- **Validate order items** — just reads the database. Nothing was changed. Compensation is a no-op.

### Compensation Ordering: Always Reverse

If T1, T2, T3 succeed and T4 fails, you must run C3, C2, C1 — not C1, C2, C3. Why?

Consider building a house: if electricity installation (T4) fails after the roof is on (T3), you take the roof off first (C3) to access the wiring, then remove the framing (C2), then the foundation (C1). Trying to undo the foundation (C1) while the roof is still on is physically impossible.

In software: T3 (create order) created order items that reference products from T2 (reserve stock). You must delete the order items (C3) before restoring stock (C2), because if you restore stock first, you might break a constraint if there's a foreign key check.

### What Happens When Compensation Fails?

This is the hard question every engineer must face when implementing sagas.

The Saga pattern cannot guarantee 100% consistency in all scenarios. If a compensation step itself fails (e.g., the database is unavailable while you're trying to restore stock), you have an **inconsistency**. This is sometimes called a "saga stuck in a partially compensated state."

The standard handling strategies are:

1. **Retry with exponential backoff.** Most compensation failures are transient (network blip, DB overload). Retry the compensation several times before giving up.
2. **Dead letter queue / manual intervention.** If all retries fail, put the saga in a failed state and alert ops. A human investigates and manually restores consistency.
3. **Idempotency keys.** Design each compensation to be safe to run multiple times.

For the Order Processing System in Phase 3 (monolith), retries and logging are sufficient. The all-same-database property means a compensation failure is likely a bug, not a transient network issue.

Your `SagaOrchestrator` will implement the minimal-but-correct version: **log every compensation failure, but continue compensating remaining steps**. Stopping compensation early because one step failed would leave more state inconsistent, not less.

---

## 6. Implementation: Layer by Layer

Now you understand the theory. Let's build it.

The saga infrastructure lives in a new `saga/` package. The business logic stays in the existing domain packages — the steps delegate to service classes, not repositories directly. This is the key principle: **saga steps are thin coordinators, service classes are the workers.**

```
src/main/java/org/viators/orderprocessingsystem/
├── saga/                              ← infrastructure: orchestration only
│   ├── SagaStep.java
│   ├── SagaOrchestrator.java
│   ├── SagaContext.java
│   └── order/
│       ├── OrderPlacementSaga.java
│       ├── ValidateOrderItemsStep.java
│       ├── ReserveStockStep.java
│       └── CreateOrderStep.java
├── product/
│   ├── ProductService.java            ← NEW methods: validateAndLoad(), reduceStock(), restoreStock()
│   └── ...
└── order/
    ├── OrderService.java              ← NEW method: createPendingOrder()
    └── ...
```

---

### 6.1 SagaStep — The Unit of Work

Every step in a saga must define two operations: what to do forward, and what to do to undo it. This maps perfectly to a Java functional interface.

```java
package org.viators.orderprocessingsystem.saga;

/**
 * Represents a single step within a saga.
 *
 * A step is the fundamental unit of a saga. It defines:
 *   - execute()    — the forward operation (the thing we're trying to do)
 *   - compensate() — the undo operation (what to do if a later step fails)
 *
 * Design decision — why a functional interface?
 * We declare this as @FunctionalInterface because each step has exactly one
 * primary abstract method (execute). This allows steps to be written as lambdas
 * when their logic is simple, which you'll see in OrderPlacementSaga.
 * For complex steps, we use full classes that implement this interface.
 *
 * Why does compensate() have a default implementation?
 * Not every step has a meaningful compensation. Validation steps (read-only
 * operations) have nothing to undo. Providing a no-op default means validation
 * steps don't need to override it — they just implement execute().
 *
 * Why no parameters on execute() or compensate()?
 * Steps receive the data they need at construction time, stored as fields.
 * This keeps the SagaOrchestrator generic — it doesn't need to know what data
 * any step operates on. See SagaContext for how steps share state with each other.
 */
@FunctionalInterface
public interface SagaStep {

    /**
     * Executes the forward operation.
     * Throws Exception if the step fails — the orchestrator catches this and triggers compensation.
     */
    void execute() throws Exception;

    /**
     * Compensates (undoes) the effects of a successful execute().
     *
     * This is only called if this step's execute() succeeded AND a later step failed.
     * If this step's execute() itself failed, this method is NOT called.
     *
     * Implementations must be:
     *   - Idempotent: safe to call multiple times without causing additional side effects.
     *     If the system retries compensation, the result must be the same as calling it once.
     *   - Non-throwing: compensation failures should be caught and logged inside the
     *     implementation. Never propagate an exception from compensate() — the orchestrator
     *     will continue compensating other steps regardless, but your logs need the error.
     *
     * The default implementation is a no-op, for read-only steps that have no side effects to undo.
     */
    default void compensate() {
        // No-op by default — override in steps that have side effects
    }

    /**
     * A human-readable name for this step, used in logging.
     * Override this to give your step a meaningful name that appears in saga execution logs.
     * Helps enormously when debugging failed sagas.
     */
    default String name() {
        return this.getClass().getSimpleName();
    }
}
```

**Key decisions explained:**

`@FunctionalInterface` means the interface has exactly one abstract method. Java uses this annotation to enforce the single-method contract at compile time. It also enables lambda syntax, which you'll see later when simple steps don't deserve a full class.

The `compensate()` default no-op solves a real ergonomic problem. Without it, every read-only step would need to write `public void compensate() {}` — boilerplate that adds noise. The default makes the contract clear: "if you have something to undo, override this."

The `name()` method is pure quality-of-life for debugging. When you read a log that says `"Executing step: ReserveStockStep"` vs `"Executing step: org.viators...saga.order.ReserveStockStep@7f3d4e2a"`, you'll appreciate this.

---

### 6.2 SagaOrchestrator — The Conductor

This is the most important class in the pattern. It knows nothing about orders, inventory, or payments — it only knows how to execute a list of steps and compensate them on failure.

```java
package org.viators.orderprocessingsystem.saga;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * Generic saga orchestrator.
 *
 * Executes a list of SagaSteps sequentially. If any step fails, all previously
 * completed steps are compensated in reverse order.
 *
 * Why @Component?
 * The orchestrator is stateless — it holds no instance fields beyond what's passed
 * into execute(). This makes it safe to be a singleton Spring bean. Multiple threads
 * can call execute() concurrently without interference because all state is in the
 * method's local variables.
 *
 * Thread safety:
 * This class is stateless and therefore inherently thread-safe. Each call to execute()
 * creates its own completedSteps list, which lives on the stack of the calling thread.
 */
@Component
@Slf4j
public class SagaOrchestrator {

    /**
     * Executes a saga defined by an ordered list of steps.
     *
     * Algorithm:
     *   1. Iterate through each step and call execute().
     *   2. Track each successfully completed step in completedSteps.
     *   3. If a step throws, immediately begin compensation: iterate completedSteps
     *      in reverse, calling compensate() on each.
     *   4. If a compensation step itself throws, log the error and continue compensating
     *      remaining steps — never abort compensation early.
     *   5. After all compensations are attempted, re-throw the original exception so the
     *      caller knows the saga failed and can return an appropriate error response.
     *
     * Why re-throw the original exception?
     * The caller needs to know the saga failed and why. The original exception carries
     * the business-meaningful message (e.g., "Insufficient stock for product X").
     * Swallowing it and throwing a generic "Saga failed" loses context that's valuable
     * for the error response.
     *
     * @param steps the ordered list of steps to execute
     * @throws Exception the original exception from the failed step, after compensation
     */
    public void execute(List<SagaStep> steps) throws Exception {
        // Tracks steps that have successfully executed, so we know what to compensate.
        // We add to this list AFTER a step succeeds — never before.
        List<SagaStep> completedSteps = new ArrayList<>();

        for (SagaStep step : steps) {
            try {
                log.info("[Saga] Executing step: {}", step.name());
                step.execute();
                completedSteps.add(step);
                log.info("[Saga] Step completed: {}", step.name());

            } catch (Exception executionException) {
                log.error("[Saga] Step failed: {} — {}", step.name(), executionException.getMessage());
                log.info("[Saga] Beginning compensation for {} completed step(s)", completedSteps.size());

                // Compensate in reverse order
                compensate(completedSteps);

                // Re-throw the original exception so the caller gets a meaningful error
                throw executionException;
            }
        }

        log.info("[Saga] All {} step(s) completed successfully", steps.size());
    }

    /**
     * Runs compensation in reverse order of execution.
     *
     * Compensation MUST be resilient. Even if one compensation step fails, we continue
     * compensating the remaining steps. Stopping early leaves more state inconsistent, not less.
     *
     * This is why each compensation failure is caught and logged rather than propagated.
     * If compensation itself fails, the system is in an inconsistent state that requires
     * manual intervention — but we log enough information to allow that intervention.
     *
     * @param completedSteps the steps to compensate, in forward-execution order.
     *                       This method reverses the order internally.
     */
    private void compensate(List<SagaStep> completedSteps) {
        // Iterate in reverse — last completed step compensates first
        for (int i = completedSteps.size() - 1; i >= 0; i--) {
            SagaStep step = completedSteps.get(i);
            try {
                log.info("[Saga] Compensating step: {}", step.name());
                step.compensate();
                log.info("[Saga] Compensation complete: {}", step.name());
            } catch (Exception compensationException) {
                // Log and CONTINUE — never abort compensation
                log.error("[Saga] COMPENSATION FAILED for step: {} — {}. " +
                          "Manual intervention may be required.",
                          step.name(), compensationException.getMessage(), compensationException);
            }
        }
    }
}
```

**Let's trace through what `completedSteps` does, because it's subtle.**

Suppose you have steps [T1, T2, T3] and T3 fails:

```
Loop iteration 1: execute T1 → success → completedSteps = [T1]
Loop iteration 2: execute T2 → success → completedSteps = [T1, T2]
Loop iteration 3: execute T3 → FAIL
  → enter catch block
  → compensate [T1, T2] in reverse: compensate(T2), compensate(T1)
  → re-throw the T3 exception
```

Notice T3 is *not* in `completedSteps`. That's intentional. T3 failed — its `execute()` never completed. We don't compensate steps that didn't succeed. The `completedSteps.add(step)` line happens *after* `step.execute()` returns normally, so a failing step is never added to the list.

This is the elegance of the pattern: the bookkeeping is trivially correct.

---

### 6.3 Step 1 — ValidateOrderItemsStep

The first step validates that all requested products exist, are active, and have sufficient stock, with no duplicate product UUIDs. It is purely a read operation — if it fails, no state has been touched.

The business logic lives in `ProductService.validateAndLoad()`. The step's only saga-specific responsibility is storing the returned entities in the `SagaContext` so steps 2 and 3 can reuse them without hitting the database again.

**ProductService — the new service method:**

```java
// In ProductService

/**
 * Validates all requested order items and returns the loaded product entities.
 *
 * This method owns the business rules for what makes a product orderable:
 * existence, active status, sufficient stock, and no duplicates. It returns
 * the loaded entities so the caller (saga step) can pass them to downstream
 * steps without redundant DB queries.
 *
 * Returning Map<String, ProductT> rather than void is intentional — the loaded
 * entities are needed by ReserveStockStep and CreateOrderStep. The service
 * method does the work and hands back what it loaded. The step decides where
 * that result goes (into the SagaContext).
 */
@Transactional(readOnly = true)
public Map<String, ProductT> validateAndLoad(List<CreateOrderItemRequest> items) {
    Set<String> seen = new HashSet<>();
    Map<String, ProductT> result = new HashMap<>();

    for (CreateOrderItemRequest item : items) {
        // BR-019: no duplicate products in a single order
        if (!seen.add(item.productUuid())) {
            throw new BusinessValidationException(
                "Duplicate product in order: " + item.productUuid()
            );
        }

        ProductT product = productRepository.findByUuid(item.productUuid())
            .orElseThrow(() -> new ResourceNotFoundException(
                "Product", "uuid", item.productUuid()
            ));

        // BR-021: cannot order deactivated products
        if (!product.isActive()) {
            throw new BusinessValidationException(
                "Product is not available for ordering: " + item.productUuid()
            );
        }

        // BR-020: if any product is out of stock, reject the entire order
        if (product.getStockQuantity() < item.quantity()) {
            throw new BusinessValidationException(
                "Insufficient stock for product: " + item.productUuid() +
                ". Requested: " + item.quantity() +
                ", Available: " + product.getStockQuantity()
            );
        }

        result.put(item.productUuid(), product);
    }

    return result;
}
```

**ValidateOrderItemsStep — the thin saga step:**

```java
package org.viators.orderprocessingsystem.saga.order;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.viators.orderprocessingsystem.order.dto.request.CreateOrderRequest;
import org.viators.orderprocessingsystem.product.ProductService;
import org.viators.orderprocessingsystem.product.ProductT;
import org.viators.orderprocessingsystem.saga.SagaContext;
import org.viators.orderprocessingsystem.saga.SagaStep;

import java.util.Map;

/**
 * Saga step 1: Validate that all order items are eligible for ordering.
 *
 * All business rules (existence, active status, stock sufficiency, no duplicates)
 * are enforced by ProductService.validateAndLoad(). This step's only saga-specific
 * responsibility is storing the returned entities in the SagaContext so steps 2
 * and 3 can reuse them without redundant DB queries.
 *
 * No compensation needed — this step is read-only and has no side effects to undo.
 */
@RequiredArgsConstructor
@Slf4j
public class ValidateOrderItemsStep implements SagaStep {

    private final CreateOrderRequest request;
    private final ProductService productService;
    private final SagaContext context;

    @Override
    public void execute() throws Exception {
        // All validation logic lives in the service — the step just calls it
        Map<String, ProductT> validatedProducts =
            productService.validateAndLoad(request.items());

        // Saga infrastructure concern: store for downstream steps
        context.setValidatedProducts(validatedProducts);

        log.debug("[ValidateOrderItemsStep] {} product(s) validated and loaded into context",
            validatedProducts.size());
    }

    // compensate() intentionally not overridden — default no-op is correct.
    // This step is read-only and has no side effects to undo.

    @Override
    public String name() {
        return "ValidateOrderItemsStep";
    }
}
```

**Notice how thin `execute()` is now.** Two lines of business work: call the service, store the result. If tomorrow a rule is added — "customers can't order more than 10 units of a single product" — it goes into `ProductService.validateAndLoad()` and the saga picks it up automatically. The step never needs to change.

The split of responsibility is now explicit: `ProductService` owns *what* validation means, `ValidateOrderItemsStep` owns *when* it runs and *where the result goes*.

---

### 6.4 Step 2 — ReserveStockStep

This is the first step with a real side effect: it deducts stock from each product. Therefore it has a real compensation: restore the stock.

The mutation logic lives in `ProductService.reduceStock()` and `ProductService.restoreStock()`. The step owns the compensation tracking — recording exactly what was deducted so it can restore precisely that, no more.

**ProductService — the two new service methods:**

```java
// In ProductService

/**
 * Reduces stock for a single product by the given quantity.
 *
 * This method owns the stock mutation rule. The step calls this once per item
 * and records the returned quantity for compensation tracking.
 *
 * Why return the quantity deducted?
 * The step needs to track exactly what was deducted per product for its compensation
 * map. Returning it from the service method avoids the step having to re-read the
 * entity it just updated.
 */
@Transactional
public long reduceStock(String productUuid, long quantity) {
    ProductT product = productRepository.findByUuid(productUuid)
        .orElseThrow(() -> new ResourceNotFoundException("Product", "uuid", productUuid));

    product.setStockQuantity(product.getStockQuantity() - quantity);
    productRepository.save(product);
    return quantity;
}

/**
 * Restores stock for a single product by the given quantity.
 *
 * Called by ReserveStockStep.compensate(). The service owns what "restore stock"
 * means — today it's an addition, but if stock management rules change (e.g.,
 * max stock cap, restock notifications), this is the single place to update.
 */
@Transactional
public void restoreStock(String productUuid, long quantity) {
    productRepository.findByUuid(productUuid).ifPresent(product -> {
        product.setStockQuantity(product.getStockQuantity() + quantity);
        productRepository.save(product);
    });
}
```

**ReserveStockStep — the thin saga step:**

```java
package org.viators.orderprocessingsystem.saga.order;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.viators.orderprocessingsystem.order.dto.request.CreateOrderRequest;
import org.viators.orderprocessingsystem.product.ProductService;
import org.viators.orderprocessingsystem.saga.SagaContext;
import org.viators.orderprocessingsystem.saga.SagaStep;

import java.util.HashMap;
import java.util.Map;

/**
 * Saga step 2: Reserve stock by deducting requested quantities.
 *
 * The mutation logic (how stock is reduced and restored) lives in ProductService.
 * This step's saga-specific responsibility is tracking what was actually deducted
 * so compensation can restore precisely that amount — no more, no less.
 *
 * Why track deductedQuantities here and not in the service?
 * The service has no concept of "this execution deducted X." It just applies a
 * mutation. The tracking of what happened within this saga execution is a saga
 * concern — it belongs in the step, not the domain service.
 */
@RequiredArgsConstructor
@Slf4j
public class ReserveStockStep implements SagaStep {

    private final CreateOrderRequest request;
    private final ProductService productService;
    private final SagaContext context;

    /**
     * Tracks how much was actually deducted per product UUID.
     * Used by compensate() to restore exactly what this execution changed.
     *
     * Why not use the request quantities directly in compensate()?
     * If deduction partially succeeded (e.g., 2 of 3 products deducted before
     * a failure), the request still lists all 3. This map records only what
     * actually completed, making compensation precisely correct.
     */
    private final Map<String, Long> deductedQuantities = new HashMap<>();

    @Override
    public void execute() throws Exception {
        for (var item : request.items()) {
            long deducted = productService.reduceStock(item.productUuid(), item.quantity());
            deductedQuantities.put(item.productUuid(), deducted);
            log.debug("[ReserveStockStep] Reserved {} unit(s) of product {}",
                deducted, item.productUuid());
        }

        log.info("[ReserveStockStep] Stock reserved for {} product(s)", deductedQuantities.size());
    }

    /**
     * Restores all stock deducted by this step's execute() call.
     * Only runs if a subsequent step failed after this one succeeded.
     */
    @Override
    public void compensate() {
        log.info("[ReserveStockStep] Compensating — restoring stock for {} product(s)",
            deductedQuantities.size());

        for (Map.Entry<String, Long> entry : deductedQuantities.entrySet()) {
            try {
                productService.restoreStock(entry.getKey(), entry.getValue());
                log.info("[ReserveStockStep] Restored {} unit(s) of product {}",
                    entry.getValue(), entry.getKey());
            } catch (Exception e) {
                log.error("[ReserveStockStep] Failed to restore stock for product {}: {}",
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

**The `deductedQuantities` map is worth understanding deeply.**

Why not just use the original request's quantities in `compensate()`? Because of this scenario:

```
items = [productA: qty=3, productB: qty=2, productC: qty=5]

execute() runs:
  → deduct productA: 3  ✅  deductedQuantities = {productA: 3}
  → deduct productB: 2  ✅  deductedQuantities = {productA: 3, productB: 2}
  → deduct productC: 5  ❌  (some DB error mid-deduction)
```

If `compensate()` used the request quantities, it would try to restore productC even though productC was never deducted. The `deductedQuantities` map records only what *actually succeeded*, making the compensation precisely correct.

---

### 6.5 Step 3 — CreateOrderStep

This step creates the order and its line items. All the construction logic — price snapshotting, address snapshotting, total calculation — lives in `OrderService.createPendingOrder()`. The step's only saga concerns are calling that method, storing the result in the context, and knowing how to compensate via `OrderService.cancelOrder()`.

**OrderService — the new service method:**

```java
// In OrderService

/**
 * Creates and persists a pending order from pre-validated inputs.
 *
 * This method owns the construction rules for an order: what gets snapshotted,
 * how the total is calculated, what the initial state is. It expects the products
 * to already be validated and stock already reserved — those are the saga's concerns,
 * not this method's.
 *
 * Why accept Map<String, ProductT> and not re-load products?
 * By the time this method is called, the products have been loaded, validated, and
 * their stock deducted. Re-loading from the DB would be a redundant query and
 * could theoretically return stale data within the same transaction window.
 * The saga passes what it already has.
 *
 * Why is SagaContext not a parameter here?
 * The service has no knowledge of sagas. It receives plain inputs and returns a
 * plain result. The step decides where the result goes (into the context).
 */
@Transactional
public OrderT createPendingOrder(CreateOrderRequest request,
                                 String customerUuid,
                                 Map<String, ProductT> validatedProducts) {
    UserT customer = userRepository.findByUuidAndStatus(customerUuid, StatusEnum.ACTIVE)
        .orElseThrow(() -> new ResourceNotFoundException("User", "uuid", customerUuid));

    List<OrderItemT> orderItems = new ArrayList<>();
    BigDecimal total = BigDecimal.ZERO;

    for (var itemRequest : request.items()) {
        ProductT product = validatedProducts.get(itemRequest.productUuid());

        // Snapshot: capture price and name at time of order (BR-019)
        BigDecimal itemTotal = product.getPrice()
            .multiply(BigDecimal.valueOf(itemRequest.quantity()));

        OrderItemT orderItem = OrderItemT.builder()
            .productUuid(product.getUuid())
            .productName(product.getName())          // snapshot
            .priceAtPurchase(product.getPrice())     // snapshot
            .quantity(itemRequest.quantity())
            .build();

        orderItems.add(orderItem);
        total = total.add(itemTotal);
    }

    OrderT order = OrderT.builder()
        .customerUuid(customer.getUuid())
        .shippingAddress(customer.getShippingAddress())  // snapshot (BR-023)
        .orderState(OrderStateEnum.PENDING)
        .totalAmount(total)
        .isPaid(false)
        .orderItems(orderItems)
        .build();

    return orderRepository.save(order);
}

/**
 * Marks an order as CANCELLED.
 *
 * Used by CreateOrderStep.compensate() when a later-phase saga step fails.
 * Soft cancellation (not deletion) preserves the audit trail — ops staff can
 * see the order existed and when it was cancelled.
 */
@Transactional
public void cancelOrder(String orderUuid) {
    orderRepository.findByUuid(orderUuid).ifPresent(order -> {
        order.setOrderState(OrderStateEnum.CANCELLED);
        orderRepository.save(order);
    });
}
```

**CreateOrderStep — the thin saga step:**

```java
package org.viators.orderprocessingsystem.saga.order;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.viators.orderprocessingsystem.order.OrderService;
import org.viators.orderprocessingsystem.order.OrderT;
import org.viators.orderprocessingsystem.order.dto.request.CreateOrderRequest;
import org.viators.orderprocessingsystem.saga.SagaContext;
import org.viators.orderprocessingsystem.saga.SagaStep;

/**
 * Saga step 3: Create the order and line items.
 *
 * All construction logic (snapshotting, total calculation, initial state) lives
 * in OrderService.createPendingOrder(). This step's saga-specific responsibilities
 * are: calling that method, storing the result in SagaContext, and knowing how
 * to compensate via OrderService.cancelOrder().
 *
 * Compensation: marks the created order as CANCELLED (not deleted).
 * Soft cancellation preserves the audit trail and is consistent with the order
 * lifecycle defined in Story 3.2.
 */
@RequiredArgsConstructor
@Slf4j
public class CreateOrderStep implements SagaStep {

    private final CreateOrderRequest request;
    private final String customerUuid;
    private final OrderService orderService;
    private final SagaContext context;

    @Override
    public void execute() throws Exception {
        OrderT savedOrder = orderService.createPendingOrder(
            request,
            customerUuid,
            context.getValidatedProducts()
        );

        // Saga infrastructure concern: store for compensation and for the caller's response
        context.setCreatedOrder(savedOrder);

        log.info("[CreateOrderStep] Order created: {} (total: {})",
            savedOrder.getUuid(), savedOrder.getTotalAmount());
    }

    /**
     * Marks the created order as CANCELLED.
     * If execute() never succeeded, context.getCreatedOrder() is null — nothing to cancel.
     */
    @Override
    public void compensate() {
        OrderT order = context.getCreatedOrder();

        if (order == null) {
            log.debug("[CreateOrderStep] No order to compensate — execute() did not succeed");
            return;
        }

        try {
            orderService.cancelOrder(order.getUuid());
            log.info("[CreateOrderStep] Order {} marked as CANCELLED during compensation",
                order.getUuid());
        } catch (Exception e) {
            log.error("[CreateOrderStep] Failed to cancel order {} during compensation: {}",
                order.getUuid(), e.getMessage(), e);
        }
    }

    @Override
    public String name() {
        return "CreateOrderStep";
    }
}
```

**Why does compensation cancel rather than delete?**

Compensating transactions are new business operations, not rollbacks. Deleting the row would erase the record as if it never existed. Cancelling it says "this order was created and then reversed" — which is true and valuable. Operations staff can see the order in a CANCELLED state, with timestamps, and investigate what failed. It also stays consistent with how the rest of the system handles the order lifecycle: PENDING → CANCELLED is a valid transition defined in Story 3.2.



---

### 6.6 SagaContext — Passing State Between Steps

Steps need to share data. The `SagaOrchestrator` can't do this (it's generic). The `SagaStep` interface parameters are typed as `void`. The solution is a **context object** that all steps in a single saga execution share.

```java
package org.viators.orderprocessingsystem.saga;

import lombok.Getter;
import lombok.Setter;
import org.viators.orderprocessingsystem.order.OrderT;
import org.viators.orderprocessingsystem.product.ProductT;

import java.util.Map;

/**
 * Carries shared state across saga steps within a single saga execution.
 *
 * Why does this exist?
 * The SagaOrchestrator is generic — it calls execute() with no parameters and no
 * return value. Steps cannot pass data to each other through the orchestrator.
 * Instead, all steps that participate in the same saga execution share a single
 * SagaContext instance, injected at construction.
 *
 * Lifecycle:
 * A new SagaContext is created for each saga execution — it is NOT a Spring bean.
 * It lives only for the duration of one SagaOrchestrator.execute() call. This is
 * analogous to a request-scoped object: one per saga invocation, discarded after.
 *
 * Thread safety:
 * SagaContext is not thread-safe. This is intentional — a saga executes its steps
 * sequentially on a single thread. Concurrent access to the same context would
 * indicate a design error.
 *
 * Why @Getter/@Setter and not records?
 * Records are immutable — fields cannot be set after construction. Context is a
 * mutable carrier by design: step 1 writes validatedProducts, step 2 reads and
 * updates it, step 3 reads it and writes createdOrder. Mutability is the entire point.
 */
@Getter
@Setter
public class SagaContext {

    /**
     * Products loaded and validated by step 1, reused by step 3.
     * Key: product UUID. Value: the full ProductT entity.
     *
     * Populated by: ValidateOrderItemsStep
     * Consumed by:  CreateOrderStep (for price/name snapshotting)
     */
    private Map<String, ProductT> validatedProducts;

    /**
     * The order created by step 3. Used by the service layer to build the
     * response, and by step 3's own compensation to know which order to cancel.
     *
     * Populated by: CreateOrderStep
     * Consumed by:  OrderService (to build the response) and CreateOrderStep.compensate()
     */
    private OrderT createdOrder;
}
```

`SagaContext` is not a Spring bean. It's a plain Java object created fresh for each saga execution. The reason is important: if it were a Spring singleton, all concurrent saga executions would share the same context instance, and you'd get race conditions where one customer's saga overwrites another's `validatedProducts`. One context per saga execution is the rule.

---

### 6.7 OrderPlacementSaga — Wiring the Steps

Now we assemble the steps into a complete saga. This class acts as a **factory for the step list** — it knows which steps to run, in which order, and wires the right dependencies into each step.

```java
package org.viators.orderprocessingsystem.saga.order;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.viators.orderprocessingsystem.order.OrderService;
import org.viators.orderprocessingsystem.order.OrderT;
import org.viators.orderprocessingsystem.order.dto.request.CreateOrderRequest;
import org.viators.orderprocessingsystem.product.ProductService;
import org.viators.orderprocessingsystem.saga.SagaContext;
import org.viators.orderprocessingsystem.saga.SagaOrchestrator;
import org.viators.orderprocessingsystem.saga.SagaStep;

import java.util.List;

/**
 * Orchestrates the order placement saga.
 *
 * Responsibilities:
 *   1. Defines which steps constitute the order placement saga and in what order.
 *   2. Wires the correct service dependencies into each step.
 *   3. Delegates execution to the generic SagaOrchestrator, then returns the result.
 *
 * Notice what this class does NOT do: no business logic, no repository access,
 * no entity manipulation. It is a pure coordinator.
 *
 * Why does this inject services, not repositories?
 * Each step delegates business operations to the appropriate service. The saga
 * wires steps together — and steps wire to services. Repositories are an
 * implementation detail of the service layer, not the saga layer.
 *
 * Why @Component and not @Service?
 * Sagas are infrastructure, not domain services. @Component signals
 * "general-purpose Spring-managed component," which is more accurate here.
 */
@Component
@RequiredArgsConstructor
public class OrderPlacementSaga {

    private final SagaOrchestrator orchestrator;
    private final ProductService productService;
    private final OrderService orderService;

    /**
     * Executes the order placement saga and returns the created order.
     *
     * Steps:
     *   1. ValidateOrderItemsStep — delegates to ProductService.validateAndLoad()
     *   2. ReserveStockStep       — delegates to ProductService.reduceStock() / restoreStock()
     *   3. CreateOrderStep        — delegates to OrderService.createPendingOrder() / cancelOrder()
     *
     * @param request      the order placement request from the customer
     * @param customerUuid the UUID of the authenticated customer
     * @return the successfully created OrderT entity
     * @throws Exception propagated from the failing step, after compensation
     */
    public OrderT execute(CreateOrderRequest request, String customerUuid) throws Exception {
        SagaContext context = new SagaContext();

        List<SagaStep> steps = List.of(
            new ValidateOrderItemsStep(request, productService, context),
            new ReserveStockStep(request, productService, context),
            new CreateOrderStep(request, customerUuid, orderService, context)
        );

        orchestrator.execute(steps);

        return context.getCreatedOrder();
    }
}
```

**Notice what `List.of()` communicates beyond just ordering.** The ordering here is the business logic: you cannot reserve stock before validating it, and you cannot create an order before stock is reserved. The fact that the order matters and is enforced structurally (not by comments) is good design.

---

### 6.8 Updating OrderService — The Entry Point

The `OrderService.placeOrder()` method becomes the thin entry point that delegates to the saga, then builds the response.

```java
/**
 * Places an order using the OrderPlacementSaga.
 *
 * This method has been refactored from a single @Transactional block to saga-based
 * orchestration. The business logic (validation, stock reservation, order creation)
 * now lives in discrete, compensatable steps.
 *
 * Transactional boundary note:
 * @Transactional is still present here, wrapping the entire saga execution. In Phase 3
 * (monolith), all steps hit the same database, so a single transaction boundary is still
 * possible and desirable — it gives us DB-level rollback as a safety net alongside the
 * saga's logical compensation.
 *
 * In Phase 4 (service extraction), this @Transactional will be removed from the saga call,
 * and each step will manage its own transaction independently (one transaction per
 * service/database). That's when the saga's compensation becomes the ONLY rollback mechanism.
 *
 * @param request      the order placement request
 * @param customerUuid the UUID of the authenticated customer
 * @return the response DTO for the created order
 */
@Transactional
public OrderDetailsResponse placeOrder(CreateOrderRequest request, String customerUuid) {
    try {
        OrderT createdOrder = orderPlacementSaga.execute(request, customerUuid);

        // Publish the order placed event via the existing event infrastructure
        applicationEventPublisher.publishEvent(
            new OrderPlacedEvent(
                OrderEvent.of(
                    "ORDER_PLACED",
                    createdOrder.getUuid(),
                    customerUuid,
                    resolveCustomerEmail(customerUuid),
                    createdOrder.getOrderState().name(),
                    createdOrder.getTotalAmount()
                )
            )
        );

        return OrderDetailsResponse.from(createdOrder);

    } catch (Exception e) {
        // The saga already logged the failure and ran compensation.
        // Wrap in a runtime exception so Spring's @Transactional triggers
        // a DB-level rollback as a backup safety measure.
        // The specific exception type determines the HTTP response code —
        // BusinessValidationException → 422, ResourceNotFoundException → 404, etc.
        if (e instanceof RuntimeException runtimeException) {
            throw runtimeException;
        }
        throw new RuntimeException("Order placement failed: " + e.getMessage(), e);
    }
}
```

**The `@Transactional` comment is worth reading carefully.** In Phase 3, keeping `@Transactional` on `placeOrder()` gives you a database-level safety net: if something goes wrong that the saga's compensation doesn't catch, the DB transaction rolls back. This is belt-and-suspenders safety. In Phase 4, when each step hits a different database, you remove the outer `@Transactional` and the saga compensation becomes the *only* mechanism. Planning this now means no surprises later.

---

## 7. How Everything Connects: The Full Execution Flow

Let's trace a complete order placement through the entire system.

### Happy Path — All Steps Succeed

```
POST /api/v1/orders
Authorization: Bearer <JWT>
Body: { items: [{productUuid: "abc", quantity: 2}] }
  |
  ▼
OrderController.placeOrder()
  → calls OrderService.placeOrder(request, customerUuid)
  |
  ▼
OrderService.placeOrder()  [@Transactional]
  → calls OrderPlacementSaga.execute(request, customerUuid)
  |
  ▼
OrderPlacementSaga.execute()
  → creates SagaContext (empty)
  → creates step list: [ValidateOrderItemsStep, ReserveStockStep, CreateOrderStep]
  → calls orchestrator.execute(steps)
  |
  ▼
SagaOrchestrator.execute()
  │
  ├─ Step 1: ValidateOrderItemsStep.execute()
  │     → calls ProductService.validateAndLoad(items)
  │     →   checks no duplicates ✅
  │     →   loads ProductT from DB ✅
  │     →   checks ACTIVE status ✅
  │     →   checks stock >= quantity ✅
  │     → stores returned products in SagaContext ✅
  │     → completedSteps = [ValidateOrderItemsStep]
  │
  ├─ Step 2: ReserveStockStep.execute()
  │     → calls ProductService.reduceStock() per item
  │     → records deductedQuantities = {abc: 2}
  │     → completedSteps = [ValidateOrderItemsStep, ReserveStockStep]
  │
  └─ Step 3: CreateOrderStep.execute()
        → calls OrderService.createPendingOrder(request, customerUuid, validatedProducts)
        →   loads customer from DB ✅
        →   builds line items with price snapshots ✅
        →   snapshots shipping address ✅
        →   saves OrderT to DB ✅
        → stores order in SagaContext ✅
        → completedSteps = [all 3 steps]

Back in OrderPlacementSaga.execute():
  → reads createdOrder from SagaContext
  → returns createdOrder to OrderService

Back in OrderService.placeOrder():
  → publishes OrderPlacedEvent → TransactionalEventPublisher → RabbitMQ (after commit)
  → builds and returns OrderDetailsResponse

@Transactional commits all DB changes atomically.

HTTP Response: 201 Created
{ "uuid": "xyz", "state": "PENDING", "totalAmount": "49.98", ... }
```

### Failure Path — Stock Reservation Fails

```
POST /api/v1/orders
Body: { items: [{productUuid: "abc", quantity: 999}] }  ← more than in stock
  |
  ▼
SagaOrchestrator.execute()
  │
  ├─ Step 1: ValidateOrderItemsStep.execute()
  │     → stock check: available=5, requested=999 → FAILS
  │     → throws BusinessValidationException("Insufficient stock...")
  │     → step was not added to completedSteps (exception thrown before add)
  │
  └─ CATCH: compensate(completedSteps = [])
        → no completed steps to compensate
        → re-throws BusinessValidationException

Back in OrderService.placeOrder():
  → exception is a RuntimeException, re-thrown as-is

@Transactional sees unchecked exception → DB rollback (nothing was written anyway)

GlobalExceptionHandler catches BusinessValidationException
HTTP Response: 422 Unprocessable Entity
{ "errorCode": "BUSINESS_VALIDATION_ERROR", "message": "Insufficient stock for product: abc..." }
```

### Failure Path — Order Creation Fails (After Stock Reserved)

```
POST /api/v1/orders
Body: { items: [{productUuid: "abc", quantity: 2}] }

Step 1: ValidateOrderItemsStep ✅  completedSteps = [T1]
Step 2: ReserveStockStep ✅        completedSteps = [T1, T2]  (stock deducted: abc=-2)
Step 3: CreateOrderStep ❌         (e.g., DB constraint violation on OrderT)
  |
  ▼ compensation begins
  │
  ├─ Compensate T2 (ReserveStockStep): restore abc +2  ✅
  └─ Compensate T1 (ValidateOrderItemsStep): no-op  ✅

re-throws the original exception from Step 3

@Transactional rollback:
  In monolith: DB-level rollback fires, undoing both the stock deduction AND
  the compensation (because all were in the same transaction).
  Wait — does that cause a problem? See the note below.
  |
HTTP Response: 500 (or appropriate error)
```

**Important nuance about `@Transactional` + Saga in Phase 3:**

In the monolith, everything is in one DB transaction. If Step 3 fails and compensation runs, and then `@Transactional` rolls back — it rolls back both the stock deduction AND the stock restoration, leaving stock at its original value. This is actually correct! The DB-level rollback effectively supersedes the saga's compensation. In Phase 3, they're redundant but not conflicting.

In Phase 4, there's no outer `@Transactional` — then the saga's compensation is the only mechanism, and it must work correctly on its own.

---

## 8. Failure Scenarios: What Actually Happens

Understanding failure paths is what separates engineers who understand distributed systems from those who only understand happy paths.

### Scenario 1: Validation Failure (Stock Insufficient)

| What happened | Stock in DB | Order in DB |
|---|---|---|
| T1 failed | Unchanged | None created |
| Compensation needed | None | None |
| Customer experience | 422 with specific message | Clean error |

### Scenario 2: Order Creation Failure (After Stock Reserved)

| What happened | Stock in DB | Order in DB |
|---|---|---|
| T2 succeeded, T3 failed | Was deducted, then restored by C2 | None created |
| Compensation needed | C2 restores stock | N/A |
| Customer experience | 500 (or business error) | Clean error |

### Scenario 3: Compensation Failure

| What happened | Stock in DB | Order in DB |
|---|---|---|
| T2 succeeded, T3 failed, C2 failed | INCONSISTENT (stock still deducted) | None created |
| Compensation needed | Manual ops intervention | None |
| Customer experience | Error response — ops alerted via logs |

This scenario is the "unhappy unhappy path" — it should be rare (compensation failure usually means the database is down, which is a systemic issue). The log entry from the orchestrator's `compensate()` method gives ops the full picture: which step failed, what entity was affected, and what the exception was.

### Scenario 4: Partial Stock Deduction (Bug in Step 2)

Imagine Step 2 processes items in order and crashes between deducting product A and product B:

```
deduct A ✅  (deductedQuantities = {A: 3})
deduct B ❌  (exception mid-loop)
```

The `deductedQuantities` map only contains `{A: 3}`. Compensation only restores A. B was never deducted, so restoring B would be wrong. The map gives you exactly-what-was-done compensation — not best-effort.

---

## 9. The Bigger Picture: Sagas in Phase 4 (Service Extraction)

When Phase 4 arrives and services are extracted, the saga pattern is already in place. Here's what changes and what stays the same.

### What Changes

Each step's `execute()` method changes from a JPA call to a service call (HTTP or event):

```java
// Phase 3 — delegates to ProductService (current)
@Override
public void execute() {
    long deducted = productService.reduceStock(item.productUuid(), item.quantity());
    deductedQuantities.put(item.productUuid(), deducted);
}

// Phase 4 — HTTP call to Inventory Service
@Override
public void execute() {
    inventoryServiceClient.reserveStock(
        new ReserveStockRequest(productUuid, quantity)
    );  // HTTP POST to http://inventory-service/api/v1/stock/reserve
}

// Phase 4 compensation — HTTP call to Inventory Service
@Override
public void compensate() {
    inventoryServiceClient.releaseStock(
        new ReleaseStockRequest(productUuid, quantity)
    );  // HTTP POST to http://inventory-service/api/v1/stock/release
}
```

### What Stays the Same

The `SagaStep` interface doesn't change. The `SagaOrchestrator` doesn't change. The `OrderPlacementSaga` step list doesn't change. The compensation logic for `CreateOrderStep` doesn't change. You change the implementations, not the structure.

### Saga State Persistence (Phase 4 Concern)

In Phase 3, saga state (which steps completed) is in-memory (`completedSteps` list). If the application crashes mid-saga, that state is lost. In Phase 3 this is acceptable — the database transaction rolls back everything.

In Phase 4, if the application crashes after T2 (stock reserved in Inventory Service) but before T3 (order created), the application restarts with no knowledge that stock was reserved. The compensation never runs. Stock is permanently deducted with no order to show for it.

The solution for Phase 4 is **persistent saga state**: persist the saga's execution status and completed steps to the database after each step. On restart, the system can detect incomplete sagas and resume or compensate them. This is a significant complexity increase — it's why it's intentionally deferred to Phase 4 rather than built now.

### The Outbox Pattern (Phase 4 Companion)

When services communicate via events (choreography elements in Phase 4), there's a dual-write problem: you write to your database AND publish an event. If the application crashes between these two, you have inconsistent state.

The **Outbox Pattern** solves this: instead of publishing directly to RabbitMQ, you write the event to an outbox table in the same database transaction as your business data. A separate relay process reads the outbox and publishes to RabbitMQ. This guarantees exactly-once event delivery. It pairs naturally with sagas. Revisit this when Phase 4 begins.

---

## 10. Pattern Checklist and Common Mistakes

### Implementation Checklist

| Concern | Requirement | Verified By |
|---|---|---|
| SagaStep is generic | No business-domain imports in `SagaStep` or `SagaOrchestrator` | Code review |
| Compensation order | Runs in reverse of execution order | Unit test: step order in orchestrator tests |
| Compensation continues on failure | `compensate()` catches its own exceptions | Code review of orchestrator |
| No failed step compensation | Failed step is NOT added to `completedSteps` | Code review: add happens after `execute()` |
| Context is not a Spring bean | `new SagaContext()` in `OrderPlacementSaga`, not `@Autowired` | Code review |
| Idempotent compensation | `deductedQuantities` map is per-execution, not shared | Code review |
| Original exception preserved | Orchestrator re-throws the execution exception | Integration test |

### Common Mistakes to Avoid

**1. Making SagaContext a Spring singleton**

If `SagaContext` is `@Component`, all concurrent saga executions share one instance. Customer A's validated products overwrite Customer B's halfway through B's saga. Always create a new context per execution.

**2. Adding the failed step to completedSteps before it succeeds**

```java
// WRONG — step is added before execute() is called
completedSteps.add(step);
step.execute();  // if this throws, we'd compensate a step that never ran

// CORRECT — step is added only after execute() returns normally
step.execute();
completedSteps.add(step);
```

This seems obvious but is easy to get backwards.

**3. Compensating in forward order**

```java
// WRONG
for (int i = 0; i < completedSteps.size(); i++) { ... }

// CORRECT
for (int i = completedSteps.size() - 1; i >= 0; i--) { ... }
```

Forward-order compensation is the most common saga implementation bug. Always reverse.

**4. Throwing from compensate()**

If `compensate()` throws, the orchestrator's catch block won't continue to the next compensation. You'll leave partial state everywhere. Always catch inside `compensate()` and log.

**5. Putting business logic in the orchestrator**

```java
// WRONG — orchestrator now knows about orders
public void execute(List<SagaStep> steps) {
    ...
    if (steps.get(2) instanceof CreateOrderStep) { ... }  // never
}

// CORRECT — orchestrator is blind to step types
public void execute(List<SagaStep> steps) {
    for (SagaStep step : steps) {
        step.execute();  // just calls the interface
    }
}
```

The orchestrator must be domain-agnostic. The day it knows about orders, it becomes a domain class and loses its generality.

**6. Forgetting that compensation is a business operation, not a rollback**

A DB rollback reverts state as if the operation never happened. Compensation is a new operation that leaves a trace. Design your compensation to be observable: log it, and consider whether it should create records (e.g., an "order cancelled by saga" audit event). Never try to hide that compensation happened.

**7. Building Phase 4 complexity now**

Persistent saga state, outbox pattern, and retry queues are necessary in Phase 4 when services are distributed. In Phase 3 (monolith), they add complexity without solving a real problem. Resist the urge to over-engineer. Add them when you need them.

---

## Project File Structure After Implementation

```
src/main/java/org/viators/orderprocessingsystem/
├── saga/                                          ← NEW PACKAGE (infrastructure)
│   ├── SagaStep.java
│   ├── SagaOrchestrator.java
│   ├── SagaContext.java
│   └── order/                                     ← Order-specific saga steps
│       ├── OrderPlacementSaga.java
│       ├── ValidateOrderItemsStep.java
│       ├── ReserveStockStep.java
│       └── CreateOrderStep.java
├── order/
│   ├── OrderController.java                       ← No changes
│   ├── OrderService.java                          ← placeOrder() updated to use saga
│   └── ...
└── ... (all other packages unchanged)
```

The footprint is intentionally small: one new package, one updated method in `OrderService`. The rest of the system (state machine, payment, notifications) is untouched.
