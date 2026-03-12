package org.viators.orderprocessingsystem.notifications.listener;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.rabbit.annotation.RabbitHandler;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.stereotype.Component;
import org.viators.orderprocessingsystem.config.RabbitMQConfig;
import org.viators.orderprocessingsystem.messaging.event.OrderEvent;
import org.viators.orderprocessingsystem.messaging.event.PaymentEvent;
import org.viators.orderprocessingsystem.notifications.NotificationService;

@Component
@RequiredArgsConstructor
@Slf4j
@RabbitListener(queues = RabbitMQConfig.NOTIFICATION_QUEUE)
public class OrderNotificationListener {

    private final NotificationService notificationService;

    @RabbitHandler
    public void handleOrderEvent(OrderEvent event) {
        try {
            notificationService.createFromOrderEvent(event);
        } catch (Exception e) {
            log.error("Failed to process order event [{}]: {}",
                event.eventId(), e.getMessage(), e);
        }
    }

    @RabbitHandler
    public void handlePaymentEvent(PaymentEvent event) {
        try {
            notificationService.createFromPaymentEvent(event);
        } catch (Exception e) {
            log.error("Failed to process payment event [{}]: {}",
                event.eventId(), e.getMessage(), e);
        }
    }
}
