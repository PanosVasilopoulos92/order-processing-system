package org.viators.orderprocessingsystem.messaging.event;

public record OrderPlacedEvent(
    OrderEvent orderEvent
) {
}
