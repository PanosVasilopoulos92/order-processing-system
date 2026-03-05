package org.viators.orderprocessingsystem.order.dto.response;

import org.viators.orderprocessingsystem.common.enums.StatusEnum;
import org.viators.orderprocessingsystem.order.OrderT;
import org.viators.orderprocessingsystem.orderitem.dto.response.OrderItemSummaryResponse;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

public record OrderDetailsResponse(
    String orderUuid,
    StatusEnum status,
    Instant createdAt,
    String customerAddress,
    BigDecimal totalAmount,
    Set<OrderItemSummaryResponse> orderItems,
    String customerUuid
) {

    public static OrderDetailsResponse from(OrderT order) {
        return new OrderDetailsResponse(
            order.getUuid(),
            order.getStatus(),
            order.getCreatedAt(),
            order.getShippingAddress(),
            order.getTotalAmount(),
            order.getOrderItems().stream()
                .filter(Objects::nonNull)
                .map(OrderItemSummaryResponse::from)
                .collect(Collectors.toSet()),
            order.getCustomer().getUuid()
        );
    }

}
