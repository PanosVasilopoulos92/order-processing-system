package org.viators.orderprocessingsystem.saga.order;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.viators.orderprocessingsystem.order.OrderService;
import org.viators.orderprocessingsystem.order.dto.request.CreateOrderRequest;
import org.viators.orderprocessingsystem.product.ProductRepository;
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

    private final CreateOrderRequest createOrderRequest;
    private final ProductService productService;
    private final SagaContext sagaContext;

    /**
     * The split of responsibility is now explicit: ProductService owns what validation means,
     * ValidateOrderItemsStep owns when it runs and where the result goes.
     * @throws Exception
     */
    @Override
    public void execute() throws Exception {
        Map<String, ProductT> validatedProducts = productService.validateAndLoad(createOrderRequest.orderItemRequests());

        // Saga infrastructure concern: store for downstream steps
        sagaContext.setValidatedProducts(validatedProducts);
        log.debug("[ValidateOrderItemsStep] {} product(s) validated and loaded into context",
            validatedProducts.size());
    }

    // compensate() intentionally not overridden — default no-op is correct. This step is read-only
    // and has no side effects to undo.

    @Override
    public String name() {
        return "ValidateOrderItemsStep";
    }

}
