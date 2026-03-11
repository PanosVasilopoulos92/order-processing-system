package org.viators.orderprocessingsystem.messaging.event;

public record PaymentProcessedEvent(PaymentEvent paymentEvent, String routingKey) {
}
