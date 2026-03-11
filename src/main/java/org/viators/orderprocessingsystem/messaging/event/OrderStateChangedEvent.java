package org.viators.orderprocessingsystem.messaging.event;

public record OrderStateChangedEvent(OrderEvent orderEvent, String routingKey) {
}
