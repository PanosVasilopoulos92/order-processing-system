package org.viators.orderprocessingsystem.messaging.event;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

public record OrderEvent(
    String eventId,
    String eventType,
    Instant timestamp,
    String orderUuid,
    String customerUuid,
    String customerEmail,
    String orderState,
    BigDecimal totalAmount
) {

    public static OrderEvent of(String eventType, String orderUuid, String customerUuid,
                                String customerEmail, String orderState, BigDecimal totalAmount) {
        return new OrderEvent(
            UUID.randomUUID().toString(),
            eventType,
            Instant.now(),
            orderUuid,
            customerUuid,
            customerEmail,
            orderState,
            totalAmount
        );
    }
}
