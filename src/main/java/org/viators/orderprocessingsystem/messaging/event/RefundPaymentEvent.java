package org.viators.orderprocessingsystem.messaging.event;

public record RefundPaymentEvent(
    PaymentEvent paymentEvent,
    String rootingKey
) {
}
