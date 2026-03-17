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
    private Map<String, ProductT> validatedProducts;
    private OrderT createdOrder;
}
