package org.viators.orderprocessingsystem.orderitem.dto.response;

import org.viators.orderprocessingsystem.common.enums.StatusEnum;
import org.viators.orderprocessingsystem.orderitem.OrderItemT;

import java.math.BigDecimal;
import java.time.Instant;

public record OrderItemSummaryResponse (
    String uuid,
    StatusEnum status,
    Instant createdAt,
    BigDecimal productPrice
) {

    public static OrderItemSummaryResponse from(OrderItemT orderItem) {

        if (orderItem == null) {
            return null;
        }

        return new OrderItemSummaryResponse(
            orderItem.getUuid(),
            orderItem.getStatus(),
            orderItem.getCreatedAt(),
            orderItem.getProductPrice()
        );
    }

}
