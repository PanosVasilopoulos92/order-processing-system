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
    private final SagaContext sagaContext;

    private final Map<String, Long> deductedQuantities = new HashMap<>();

    /**
     * Tracks how much was actually deducted per product UUID.
     * Used by compensate() to restore exactly what this execution changed.
     *
     * Why not use the request quantities directly in compensate()?
     * If deduction partially succeeded (e.g., 2 of 3 products deducted before
     * a failure), the request still lists all 3. This map records only what
     * actually completed, making compensation precisely correct.
     */
    @Override
    public void execute() throws Exception {
        for (var item : request.orderItemRequests()) {
            long deductedQuantity = productService.reduceStock(item.productUuid(), item.quantity());
            deductedQuantities.put(item.productUuid(), deductedQuantity);
            log.debug("[ReserveStockStep] Reserved {} unit(s) of product {}",
                deductedQuantity, item.productUuid());
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

        for (var entry : deductedQuantities.entrySet()) {
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
