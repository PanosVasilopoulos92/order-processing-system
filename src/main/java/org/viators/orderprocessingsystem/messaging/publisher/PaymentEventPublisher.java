package org.viators.orderprocessingsystem.messaging.publisher;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.stereotype.Component;
import org.viators.orderprocessingsystem.config.RabbitMQConfig;
import org.viators.orderprocessingsystem.messaging.event.PaymentEvent;

@Component
@RequiredArgsConstructor
@Slf4j
public class PaymentEventPublisher {

    private final RabbitTemplate rabbitTemplate;

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
