package org.viators.orderprocessingsystem.saga.order;

import lombok.RequiredArgsConstructor;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import org.viators.orderprocessingsystem.common.enums.PaymentStateEnum;
import org.viators.orderprocessingsystem.messaging.event.OrderEvent;
import org.viators.orderprocessingsystem.messaging.event.OrderPlacedEvent;
import org.viators.orderprocessingsystem.order.OrderService;
import org.viators.orderprocessingsystem.order.OrderT;
import org.viators.orderprocessingsystem.order.dto.request.CreateOrderRequest;
import org.viators.orderprocessingsystem.order.dto.response.OrderDetailsResponse;
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
 *   4. Publishes the OrderPlacedEvent after successful execution.
 *
 * This class owns the full placeOrder flow (previously in OrderService) to avoid
 * a circular dependency: OrderService → OrderPlacementSaga → OrderService.
 * Now the dependency is one-way: OrderPlacementSaga → OrderService.
 *
 * Why @Component and not @Service?
 * Sagas are infrastructure, not domain services. @Component signals
 * "general-purpose Spring-managed component," which is more accurate here.
 */
@Component
@RequiredArgsConstructor
public class OrderPlacementSaga {

    private final SagaOrchestrator sagaOrchestrator;
    private final ProductService productService;
    private final OrderService orderService;
    private final ApplicationEventPublisher applicationEventPublisher;

    /**
     * Places an order using saga-based orchestration.
     *
     * Transactional boundary note:
     * In Phase 3 (monolith), all steps hit the same database, so a single transaction
     * boundary gives us DB-level rollback as a safety net alongside the saga's logical
     * compensation. In Phase 4 (service extraction), this @Transactional will be removed,
     * and each step will manage its own transaction independently.
     *
     * @param request      the order placement request from the customer
     * @param customerUuid the UUID of the authenticated customer
     * @return the response DTO for the created order
     */
    @Transactional
    public OrderDetailsResponse placeOrder(CreateOrderRequest request, String customerUuid) {
        try {
            OrderT createdOrder = execute(request, customerUuid);

            applicationEventPublisher.publishEvent(
                new OrderPlacedEvent(
                    OrderEvent.of(
                        "ORDER_PLACED",
                        createdOrder.getUuid(),
                        customerUuid,
                        createdOrder.getCustomer().getEmail(),
                        createdOrder.getOrderState().name(),
                        createdOrder.getTotalAmount()
                    )
                )
            );

            return OrderDetailsResponse.from(createdOrder, PaymentStateEnum.PENDING);

        } catch (Exception e) {
            if (e instanceof RuntimeException runtimeException) {
                throw runtimeException;
            }
            throw new RuntimeException("Order placement failed: " + e.getMessage(), e);
        }
    }

    private OrderT execute(CreateOrderRequest request, String customerUuid) throws Exception {
        SagaContext context = new SagaContext();

        List<SagaStep> steps = List.of(
            new ValidateOrderItemsStep(request, productService, context),
            new ReserveStockStep(request, productService, context),
            new CreateOrderStep(request, customerUuid, orderService, context)
        );

        sagaOrchestrator.execute(steps);
        return context.getCreatedOrder();
    }
}
