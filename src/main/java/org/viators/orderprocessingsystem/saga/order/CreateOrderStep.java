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
 * lifecycle defined in Story 3.2
 */
@RequiredArgsConstructor
@Slf4j
public class CreateOrderStep implements SagaStep {

    private final CreateOrderRequest request;
    private final String customerUuid;
    private final OrderService orderService;
    private final SagaContext sagaContext;

    @Override
    public void execute() throws Exception {
        OrderT order = orderService.createPendingOrder(request, customerUuid, sagaContext.getValidatedProducts());

        // Saga infrastructure concern: store for compensation and for the caller's response
        sagaContext.setCreatedOrder(order);
        log.info("[CreateOrderStep] Order created: {} (total: {})",
            order.getUuid(), order.getTotalAmount());
    }

    /**
     * Marks the created order as CANCELLED.
     * If execute() never succeeded, context.getCreatedOrder() is null — nothing to cancel.
     */
    @Override
    public void compensate() {
        OrderT order = sagaContext.getCreatedOrder();

        if (order == null) {
            log.debug("[CreateOrderStep] No order to compensate — execute() did not succeed");
            return;
        }

        try {
            orderService.cancelPendingOrder(order.getUuid());
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
