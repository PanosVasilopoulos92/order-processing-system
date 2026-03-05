package org.viators.orderprocessingsystem.order;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.viators.orderprocessingsystem.common.enums.OrderStatusEnum;
import org.viators.orderprocessingsystem.common.enums.StatusEnum;
import org.viators.orderprocessingsystem.exceptions.BusinessValidationException;
import org.viators.orderprocessingsystem.exceptions.ResourceNotFoundException;
import org.viators.orderprocessingsystem.order.dto.request.CreateOrderRequest;
import org.viators.orderprocessingsystem.order.dto.response.OrderDetailsResponse;
import org.viators.orderprocessingsystem.orderitem.OrderItemService;
import org.viators.orderprocessingsystem.orderitem.OrderItemT;
import org.viators.orderprocessingsystem.product.ProductService;
import org.viators.orderprocessingsystem.product.ProductT;
import org.viators.orderprocessingsystem.user.UserService;
import org.viators.orderprocessingsystem.user.UserT;

import java.math.BigDecimal;
import java.util.Set;
import java.util.stream.Collectors;

@Service
@Slf4j
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class OrderService {

    private final OrderRepository orderRepository;
    private final OrderItemService orderItemService;
    private final UserService userService;
    private final ProductService productService;

    public OrderT getActiveOrder(String orderUuid) {
        return orderRepository.findByUuidAndStatus(orderUuid, StatusEnum.ACTIVE)
            .orElseThrow(() -> new ResourceNotFoundException("Order", "uuid", orderUuid));
    }

    @Transactional
    public OrderDetailsResponse create(String loggedInCustomer, CreateOrderRequest request) {
        UserT customer = userService.getActiveUser(loggedInCustomer);
        Set<OrderItemT> orderItems = orderItemService.getOrderItemsForUuids(request.orderItemUuids());

        Set<ProductT> productsInvolved = validateOrderAndReturnAffectedOrderItems(orderItems);

        OrderT order = new OrderT();
        order.setCustomer(customer);
        order.setShippingAddress(customer.getShippingAddress());
        orderItems.forEach(order::addOrderItem);
        BigDecimal totalAmount = orderItems.stream()
            .map(item -> item.getProductPrice().multiply(item.getQuantity()))
            .reduce(BigDecimal.ZERO, BigDecimal::add);

        order.setTotalAmount(totalAmount);
        order.setOrderStatus(OrderStatusEnum.PENDING);
        order = orderRepository.save(order);

        for (ProductT product : productsInvolved) {
            BigDecimal quantity = orderItems.stream()
                .filter(orderItem -> product.getUuid().equals(orderItem.getUuid()))
                .findFirst()
                .map(OrderItemT::getQuantity)
                .get();

            product.setStockQuantity(product.getStockQuantity().subtract(quantity));
        }

        return OrderDetailsResponse.from(order);
    }

    private Set<ProductT> validateOrderAndReturnAffectedOrderItems(Set<OrderItemT> orderItems) {

        if (orderItems.isEmpty()) {
            throw new BusinessValidationException("An order must have at least one order item");
        }

        Set<String> productUuids = orderItems.stream()
            .map(OrderItemT::getProduct)
            .map(ProductT::getUuid)
            .collect(Collectors.toSet());

        Set<ProductT> productsInvolved = productService.getProductsInSet(productUuids);
        productsInvolved.stream()
            .filter(product -> StatusEnum.INACTIVE.equals(product.getStatus()))
            .findAny()
            .orElseThrow(() -> new BusinessValidationException("There is an inactive product inside this order"));

        return productsInvolved;
    }


    public void changeStockQuantityAfterOrder(Set<ProductT> productsInvolved, Set<OrderItemT> orderItems) {

    }
}
