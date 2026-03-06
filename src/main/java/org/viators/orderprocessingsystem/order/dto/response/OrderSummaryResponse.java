package org.viators.orderprocessingsystem.order.dto.response;

import org.viators.orderprocessingsystem.common.enums.OrderStateEnum;
import org.viators.orderprocessingsystem.order.OrderT;

import java.math.BigDecimal;
import java.time.Instant;

public record OrderSummaryResponse(
    String orderUuid,
    OrderStateEnum orderState,
    BigDecimal totalAmount,
    Integer numberOfItems,
    Instant createdAt
) {

    public static OrderSummaryResponse from(OrderT order, Integer numberOfItems) {
        return new OrderSummaryResponse(
            order.getUuid(),
            order.getOrderState(),
            order.getTotalAmount(),
            numberOfItems,
            order.getCreatedAt()
        );
    }

}

