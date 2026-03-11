package org.viators.orderprocessingsystem.messaging.listener;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionalEventListener;
import org.viators.orderprocessingsystem.config.RabbitMQConfig;
import org.viators.orderprocessingsystem.messaging.event.OrderPlacedEvent;
import org.viators.orderprocessingsystem.messaging.event.OrderStateChangedEvent;
import org.viators.orderprocessingsystem.messaging.event.PaymentProcessedEvent;
import org.viators.orderprocessingsystem.messaging.publisher.OrderEventPublisher;

@Component
@RequiredArgsConstructor
@Slf4j
public class TransactionalEventPublisher {

    private final OrderEventPublisher orderEventPublisher;

    @TransactionalEventListener
    public void handleOrderPlaced(OrderPlacedEvent event) {
        orderEventPublisher.publishOrderEvent(
            event.orderEvent(), RabbitMQConfig.ORDER_PLACED_KEY
        );
    }

    @TransactionalEventListener
    public void handleOrderStateChanged(OrderStateChangedEvent event) {
        orderEventPublisher.publishOrderEvent(
            event.orderEvent(), event.routingKey()
        );
    }

    public void handlePaymentProcessed(PaymentProcessedEvent event) {
        orderEventPublisher.publishPaymentEvent(
            event.paymentEvent(), event.routingKey()
        );
    }

}
