package org.viators.orderprocessingsystem.messaging.publisher;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.stereotype.Component;
import org.viators.orderprocessingsystem.config.RabbitMQConfig;
import org.viators.orderprocessingsystem.messaging.event.OrderEvent;
import org.viators.orderprocessingsystem.messaging.event.PaymentEvent;

/**
 * Create a dedicated component for publishing. This keeps RabbitMQ concerns out of service classes.
 * BR-031: Event publishing must not block the primary operation.
 * If RabbitMQ is down, log and continue — the order operation
 * should still succeed.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class OrderEventPublisher {

    private final RabbitTemplate rabbitTemplate;

    public void publishOrderEvent(OrderEvent event, String routingKey) {
        try {
            rabbitTemplate.convertAndSend(
                RabbitMQConfig.ORDER_EXCHANGE,
                routingKey,
                event
            );
        } catch (Exception e) {
            log.error("Failed to publish event [{}] for order [{}]: {}",
                event.eventType(), event.orderUuid(), e.getMessage(), e);
        }
    }

    public void publishPaymentEvent(PaymentEvent event, String routingKey) {
        try {
            rabbitTemplate.convertAndSend(
                RabbitMQConfig.ORDER_EXCHANGE,
                routingKey,
                event
            );
        } catch (Exception e) {
            log.error("Failed to publish event [{}] for payment [{}]: {}",
                event.eventType(), event.paymentUuid(), e.getMessage(), e);
        }
    }
}
