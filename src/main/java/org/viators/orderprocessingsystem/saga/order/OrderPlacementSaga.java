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

    private final SagaOrchestrator sagaOrchestrator;
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

        sagaOrchestrator.execute(steps);
        return context.getCreatedOrder();
    }
}
