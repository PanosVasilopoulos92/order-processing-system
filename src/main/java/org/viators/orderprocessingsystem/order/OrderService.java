package org.viators.orderprocessingsystem.order;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.viators.orderprocessingsystem.common.enums.OrderStateEnum;
import org.viators.orderprocessingsystem.common.enums.PaymentStateEnum;
import org.viators.orderprocessingsystem.common.enums.StatusEnum;
import org.viators.orderprocessingsystem.common.services.OwnershipAuthorizationService;
import org.viators.orderprocessingsystem.config.RabbitMQConfig;
import org.viators.orderprocessingsystem.exceptions.BusinessValidationException;
import org.viators.orderprocessingsystem.exceptions.ResourceNotFoundException;
import org.viators.orderprocessingsystem.messaging.event.OrderEvent;
import org.viators.orderprocessingsystem.messaging.event.OrderPlacedEvent;
import org.viators.orderprocessingsystem.messaging.event.OrderStateChangedEvent;
import org.viators.orderprocessingsystem.order.dto.request.CreateOrderRequest;
import org.viators.orderprocessingsystem.order.dto.response.OrderDetailsResponse;
import org.viators.orderprocessingsystem.order.dto.response.OrderSummaryResponse;
import org.viators.orderprocessingsystem.orderitem.OrderItemService;
import org.viators.orderprocessingsystem.orderitem.OrderItemT;
import org.viators.orderprocessingsystem.payment.PaymentQueryService;
import org.viators.orderprocessingsystem.payment.PaymentService;
import org.viators.orderprocessingsystem.payment.PaymentT;
import org.viators.orderprocessingsystem.product.ProductService;
import org.viators.orderprocessingsystem.product.ProductT;
import org.viators.orderprocessingsystem.saga.order.OrderPlacementSaga;
import org.viators.orderprocessingsystem.user.UserService;
import org.viators.orderprocessingsystem.user.UserT;

import java.math.BigDecimal;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;


@Service
@Slf4j
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class OrderService {

    private final OrderRepository orderRepository;
    private final UserService userService;
    private final OrderItemService orderItemService;
    private final PaymentQueryService paymentQueryService;
    private final PaymentService paymentService;
    private final OwnershipAuthorizationService ownershipAuthorizationService;
    private final OrderPlacementSaga orderPlacementSaga;
    private final ProductService productService;

    // Event publishing
    private final ApplicationEventPublisher applicationEventPublisher;

    public OrderT getActiveOrder(String orderUuid) {
        return orderRepository.findByUuidAndStatus(orderUuid, StatusEnum.ACTIVE)
            .orElseThrow(() -> new ResourceNotFoundException("Order", "uuid", orderUuid));
    }

    public OrderDetailsResponse getOrderDetails(String customerUuid, String orderUuid) {

        OrderT order = orderRepository.findByUuidAndCustomerWithOrderItemsAndCustomer(customerUuid, orderUuid)
            .orElseThrow(() -> new ResourceNotFoundException("Order", "uuid", orderUuid));

        if (!order.getCustomer().isAdminUser()) {
            ownershipAuthorizationService.verifyOwnership(customerUuid, order.getUuid());
        }

        Set<PaymentT> payments = paymentQueryService.getAllPaymentsForOrder(orderUuid);

        PaymentStateEnum paymentState = payments.stream()
            .map(PaymentT::getPaymentState)
            .filter(PaymentStateEnum.SUCCESS::equals)
            .findFirst()
            .orElse(payments.isEmpty() ? PaymentStateEnum.PENDING : PaymentStateEnum.FAILED);

        return OrderDetailsResponse.from(order, paymentState);
    }

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
            // The saga already logged the failure and ran compensation.
            // The specific exception type determines the HTTP response code —
            // BusinessValidationException → 422, ResourceNotFoundException → 404, etc.
            if (e instanceof RuntimeException runtimeException) {
                throw runtimeException;
            }
            throw new RuntimeException("Order placement failed: " + e.getMessage(), e);
        }
    }

    @Transactional
    public void cancelOrder(String loggedInUserUuid, String orderUuid) {

        OrderT order = getActiveOrder(orderUuid);

        validateEligibleCancellation(loggedInUserUuid, order.getCustomer().getUuid(), order.getOrderState());
        order.setOrderState(OrderStateEnum.CANCELLED);

        Set<OrderItemT> orderItems = orderItemService.getAllOrderItemsForOrderWithProducts(orderUuid);

        orderItems.forEach(orderItemT ->
            productService.restoreStock(orderItemT.getProduct().getUuid(), orderItemT.getQuantity())
        );

        paymentService.refundOrderPayment(order);

        applicationEventPublisher.publishEvent(new OrderStateChangedEvent(
            OrderEvent.of(
                "ORDER_CANCELLED",
                    order.getUuid(),
                order.getCustomer().getUuid(),
                order.getCustomer().getEmail(),
                OrderStateEnum.CANCELLED.name(),
                order.getTotalAmount()
            ),
            RabbitMQConfig.ORDER_CANCELLED_KEY
        ));
    }

    public void validateEligibleCancellation(String loggedInUserUuid, String customerOwningOrderUuid, OrderStateEnum orderState) {

        UserT loggedInUser = userService.getActiveUser(loggedInUserUuid);
        boolean isAdminUser = loggedInUser.isAdminUser();

        if (!loggedInUserUuid.equals(customerOwningOrderUuid) && !isAdminUser) {
            throw new BusinessValidationException("You cannot delete an order that belongs to another customer unless you have admin rights.");
        }

        if (!OrderStateEnum.PENDING.equals(orderState) && !OrderStateEnum.CONFIRMED.equals(orderState)) {
            throw new BusinessValidationException("Only orders in state %s and %s can be cancelled."
                .formatted(OrderStateEnum.PENDING, OrderStateEnum.CONFIRMED));
        }
    }

    @Transactional
    public void changeOrderState(String orderUuid, OrderStateEnum orderState) {

        OrderT order = orderRepository.findOrderWithPayments(orderUuid, StatusEnum.ACTIVE)
            .orElseThrow(() -> new ResourceNotFoundException("Order", "uuid", orderUuid));

        switch (orderState) {
            case CONFIRMED -> handlePendingToConfirmedState(order);
            case SHIPPED -> handleConfirmedToShippedState(order);
            case DELIVERED -> handleShippedToDeliveredState(order);
            default ->
                throw new BusinessValidationException("Order state: %s is not a valid state".formatted(orderState));
        }

        // Publish event section
        String routingKey = switch (order.getOrderState()) {
            case CONFIRMED -> RabbitMQConfig.ORDER_CONFIRMED_KEY;
            case SHIPPED -> RabbitMQConfig.ORDER_SHIPPED_KEY;
            case DELIVERED -> RabbitMQConfig.ORDER_DELIVERED_KEY;
            default -> null;
        };

        if (routingKey != null) {
            applicationEventPublisher.publishEvent(new OrderStateChangedEvent(
                OrderEvent.of(
                    "ORDER_" + order.getOrderState().name(),
                    order.getUuid(),
                    order.getCustomer().getUuid(),
                    order.getCustomer().getEmail(),
                    order.getOrderState().name(),
                    order.getTotalAmount()
                ), routingKey
            ));
        }
    }

    private void handlePendingToConfirmedState(OrderT order) {

        if (OrderStateEnum.PENDING.equals(order.getOrderState())) {
            if (!verifyPaymentWasSuccessful(order)) {
                throw new BusinessValidationException("Order cannot proceed to next state because there was no payment found for it");
            }
            order.setOrderState(OrderStateEnum.CONFIRMED);
        } else {
            throw new BusinessValidationException("Order from state: %s can transition only to state: %s"
                .formatted(OrderStateEnum.PENDING, OrderStateEnum.CONFIRMED));
        }
    }

    private void handleConfirmedToShippedState(OrderT order) {

        if (OrderStateEnum.CONFIRMED.equals(order.getOrderState())) {
            order.setOrderState(OrderStateEnum.SHIPPED);
        } else {
            throw new BusinessValidationException("Order from state: %s can transition only to state: %s"
                .formatted(OrderStateEnum.CONFIRMED, OrderStateEnum.SHIPPED));
        }
    }

    private void handleShippedToDeliveredState(OrderT order) {

        if (OrderStateEnum.SHIPPED.equals(order.getOrderState())) {
            order.setOrderState(OrderStateEnum.DELIVERED);
        } else {
            throw new BusinessValidationException("Order from state: %s can transition only to state: %s"
                .formatted(OrderStateEnum.SHIPPED, OrderStateEnum.DELIVERED));
        }
    }

    private boolean verifyPaymentWasSuccessful(OrderT order) {

        return order.getPayments().stream()
            .map(PaymentT::getPaymentState)
            .anyMatch(PaymentStateEnum.SUCCESS::equals);
    }

    public Page<OrderSummaryResponse> getOrdersHistoryPlacedByCustomer(String customerUuid, OrderStateEnum orderState, Pageable pageable) {

        return orderState != null
            ? orderRepository.findOrderSummariesByCustomerUuidAndOrderState(customerUuid, orderState, pageable)
            : orderRepository.findOrderSummariesByCustomerUuid(customerUuid, pageable);
    }


    // Methods used by Saga pattern -------------------------

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
    public OrderT createPendingOrder(CreateOrderRequest request, String customerUuid,
                                     Map<String, ProductT> validatedProducts) {
        UserT customer = userService.getActiveUser(customerUuid);

        Set<OrderItemT> orderItems = new HashSet<>();
        BigDecimal orderTotalAmount = BigDecimal.ZERO;

        for (var item : request.orderItemRequests()) {
            ProductT product = validatedProducts.get(item.productUuid());

            BigDecimal itemTotal = product.getPrice()
                .multiply(BigDecimal.valueOf(item.quantity()));

            OrderItemT orderItem = new OrderItemT();
            orderItem.setQuantity(item.quantity());
            orderItem.setProductPrice(product.getPrice());
            orderItem.setProductName(product.getName());
            orderItem.setProduct(product);

            orderItems.add(orderItem);
            orderTotalAmount = orderTotalAmount.add(itemTotal);
        }

        OrderT order = OrderT.builder()
            .customer(customer)
            .shippingAddress(customer.getShippingAddress())  // snapshot (BR-023)
            .orderState(OrderStateEnum.PENDING)
            .totalAmount(orderTotalAmount)
            .isPaid(false)
            .orderItems(orderItems)
            .build();

        orderItems.forEach(order::addOrderItem);
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
    public void cancelPendingOrder(String orderUuid) {
        orderRepository.findByUuidAndStatus(orderUuid, StatusEnum.ACTIVE)
            .ifPresent(order -> {
                order.setOrderState(OrderStateEnum.CANCELLED);
                orderRepository.save(order);
            });
    }

}
