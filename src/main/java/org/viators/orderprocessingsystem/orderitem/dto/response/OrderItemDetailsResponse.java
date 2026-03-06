package org.viators.orderprocessingsystem.orderitem.dto.response;

import org.viators.orderprocessingsystem.common.enums.StatusEnum;
import org.viators.orderprocessingsystem.orderitem.OrderItemT;

import java.math.BigDecimal;
import java.time.Instant;

public record OrderItemDetailsResponse(
    String uuid,
    String productName,
    BigDecimal quantity,
    BigDecimal productPrice,
    String productUuid,
    String orderUuid,
    StatusEnum status,
    Instant createdAt
) {

    public static OrderItemDetailsResponse from(OrderItemT orderItem) {
        return new OrderItemDetailsResponse(
            orderItem.getUuid(),
            orderItem.getProductName(),
            orderItem.getQuantity(),
            orderItem.getProductPrice(),
            orderItem.getProduct().getUuid(),
            orderItem.getOrder().getUuid(),
            orderItem.getStatus(),
            orderItem.getCreatedAt()
        );
    }
}
