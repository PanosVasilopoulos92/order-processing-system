package org.viators.orderprocessingsystem.config;

import org.springframework.amqp.core.*;
import org.springframework.amqp.support.converter.JacksonJsonMessageConverter;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class RabbitMQConfig {

    // Exchange
    public static final String ORDER_EXCHANGE = "order.exchange";

    // Queues
    public static final String NOTIFICATION_QUEUE = "order.notification.queue";

    // Routing Keys
    public static final String ORDER_PLACED_KEY = "order.placed";
    public static final String ORDER_CONFIRMED_KEY = "order.confirmed";
    public static final String ORDER_SHIPPED_KEY = "order.shipped";
    public static final String ORDER_DELIVERED_KEY = "order.delivered";
    public static final String ORDER_CANCELLED_KEY = "order.cancelled";
    public static final String PAYMENT_SUCCESS_KEY = "payment.success";
    public static final String PAYMENT_FAILED_KEY = "payment.failed";

    @Bean
    public TopicExchange orderExchange() {
        return new TopicExchange(ORDER_EXCHANGE);
    }

    @Bean
    public Queue notificationQueue() {
        return QueueBuilder.durable(NOTIFICATION_QUEUE).build();
    }

    // ── Bindings ────────────────────────────────────────
    // Each binding tells the exchange: "when you see a message
    // with this routing key pattern, put it in this queue"

    @Bean
    public Binding notificationOrderBinding(Queue notificationQueue, TopicExchange orderExchange) {
        // "order.*" matches order.placed, order.confirmed, etc.
        return BindingBuilder.bind(notificationQueue).to(orderExchange).with("order.*");
    }

    @Bean
    public Binding notificationPaymentBinding(Queue notificationQueue, TopicExchange orderExchange) {
        // "payment.*" matches payment.success, payment.failed
        return BindingBuilder.bind(notificationQueue).to(orderExchange).with("payment.*");
    }

    // ── Message Converter ───────────────────────────────
    // By default, Spring AMQP serializes messages using Java
    // serialization (binary). We want JSON instead because:
    // 1. It's human-readable (you can inspect messages in the management console)
    // 2. It's language-agnostic (future services in different languages can consume)
    // 3. It's safer (Java deserialization has security vulnerabilities)

    @Bean
    public JacksonJsonMessageConverter jsonMessageConverter() {
        return new JacksonJsonMessageConverter();
    }
}
