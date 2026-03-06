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
import org.viators.orderprocessingsystem.orderitem.dto.request.CreateOrderItemRequest;
import org.viators.orderprocessingsystem.product.ProductService;
import org.viators.orderprocessingsystem.product.ProductT;
import org.viators.orderprocessingsystem.user.UserService;
import org.viators.orderprocessingsystem.user.UserT;

import java.math.BigDecimal;
import java.util.HashSet;
import java.util.Set;
import java.util.stream.Collectors;

@Service
@Slf4j
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class OrderService {

    private final OrderRepository orderRepository;
    private final UserService userService;
    private final ProductService productService;

    public OrderT getActiveOrder(String orderUuid) {
        return orderRepository.findByUuidAndStatus(orderUuid, StatusEnum.ACTIVE)
            .orElseThrow(() -> new ResourceNotFoundException("Order", "uuid", orderUuid));
    }

    @Transactional
    public OrderDetailsResponse create(String loggedInCustomer, CreateOrderRequest request) {

        // Early guard
        Set<String> productUuids = request.orderItemRequests().stream()
            .map(CreateOrderItemRequest::productUuid)
            .collect(Collectors.toSet());

        if (productUuids.size() != request.orderItemRequests().size()) {
            throw new BusinessValidationException("Duplicate products are not allowed in the same order");
        }

        UserT customer = userService.getActiveUser(loggedInCustomer);

        Set<ProductT> productsInvolved = productService.getProductsInSet(productUuids);
        validateOrderAndReturnAffectedOrderItems(productsInvolved);

        OrderT order = new OrderT();
        order.setCustomer(customer);
        order.setShippingAddress(customer.getShippingAddress());

        orderRepository.save(order);

        Set<OrderItemT> orderItems = new HashSet<>();
        for (CreateOrderItemRequest orderItemRequest : request.orderItemRequests()) {
            OrderItemT orderItem = new OrderItemT();
            ProductT product = productService.getActiveProduct(orderItemRequest.productUuid());
            orderItem.setQuantity(orderItemRequest.quantity());
            orderItem.setProductPrice(product.getPrice());
            orderItem.setProductName(product.getName());
            orderItem.setProduct(product);
            orderItem.setOrder(order);
            orderItems.add(orderItem);
            order.addOrderItem(orderItem);

            product.setStockQuantity(product.getStockQuantity() - orderItem.getQuantity());
            if (product.getStockQuantity() < 0) {
                throw new BusinessValidationException(("Stock quantity for product with uuid: %s is insufficient. Available stock is %d")
                    .formatted(product.getUuid(), product.getStockQuantity() + orderItem.getQuantity()));
            }
        }

        BigDecimal totalAmount = orderItems.stream()
            .map(orderItemT -> orderItemT.getProductPrice().multiply(BigDecimal.valueOf(orderItemT.getQuantity())))
            .reduce(BigDecimal.ZERO, BigDecimal::add);

        order.setOrderStatus(OrderStatusEnum.PENDING);
        order.setTotalAmount(totalAmount);
        order = orderRepository.save(order);

        return OrderDetailsResponse.from(order);
    }

    private void validateOrderAndReturnAffectedOrderItems(Set<ProductT> productsInvolved) {

        if (productsInvolved.isEmpty()) {
            throw new BusinessValidationException("An order must have at least one order item");
        }

        boolean hasInactiveProduct = productsInvolved.stream()
            .anyMatch(product -> StatusEnum.INACTIVE.equals(product.getStatus()));

        if (hasInactiveProduct) {
            throw new BusinessValidationException("There is an inactive product inside this order");
        }
    }

}
