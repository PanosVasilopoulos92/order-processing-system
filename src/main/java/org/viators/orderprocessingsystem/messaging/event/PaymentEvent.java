package org.viators.orderprocessingsystem.messaging.event;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

public record PaymentEvent(
    String eventId,
    String eventType,
    Instant timestamp,
    String paymentUuid,
    String orderUuid,
    String customerUuid,
    String customerEmail,
    String paymentState,
    BigDecimal amount,
    String paymentMethod,
    String failureReason
) {

    public static PaymentEvent of(String eventType, String paymentUuid, String orderUuid, String customerUuid,
                                  String customerEmail, String paymentState, BigDecimal amount, String paymentMethod,
                                  String failureReason) {
        return new PaymentEvent(
            UUID.randomUUID().toString(),
            eventType,
            Instant.now(),
            paymentUuid,
            orderUuid,
            customerUuid,
            customerEmail,
            paymentState,
            amount,
            paymentMethod,
            failureReason
        );
    }
}
