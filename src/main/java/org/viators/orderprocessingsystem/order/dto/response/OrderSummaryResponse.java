package org.viators.orderprocessingsystem.order.dto.response;

import org.viators.orderprocessingsystem.common.enums.OrderStateEnum;

import java.math.BigDecimal;
import java.time.Instant;

public record OrderSummaryResponse(
    String orderUuid,
    OrderStateEnum orderState,
    BigDecimal totalAmount,
    Integer numberOfItems,
    Instant createdAt,
    boolean isPaid
) {
}

