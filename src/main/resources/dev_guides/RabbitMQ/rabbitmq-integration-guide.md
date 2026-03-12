# RabbitMQ Integration Guide — Order Processing System

## Table of Contents

1. [Understanding Messaging — Why RabbitMQ?](#1-understanding-messaging--why-rabbitmq)
2. [RabbitMQ Core Concepts](#2-rabbitmq-core-concepts)
3. [Story 6.1 — RabbitMQ Setup & Event Infrastructure](#3-story-61--rabbitmq-setup--event-infrastructure)
4. [Story 6.2 — Order Event Publishing](#4-story-62--order-event-publishing)
5. [Story 7.1 — Notification Service](#5-story-71--notification-service)
6. [Story 7.2 — Email Notifications](#6-story-72--email-notifications)
7. [Testing & Verification](#7-testing--verification)
8. [Common Pitfalls](#8-common-pitfalls)

---

## 1. Understanding Messaging — Why RabbitMQ?

### The Problem in Your Current Codebase

Look at your `OrderService.cancelOrder()` method right now:

```java
@Transactional
public void cancelOrder(String loggedInUserUuid, String orderUuid) {
    OrderT order = getActiveOrder(orderUuid);
    validateEligibleCancellation(...);
    order.setOrderState(OrderStateEnum.CANCELLED);

    // OrderService directly calls OrderItemService
    Set<OrderItemT> orderItems = orderItemService.getAllOrderItemsForOrderWithProducts(orderUuid);
    orderItems.forEach(item -> item.getProduct().setStockQuantity(...));

    // OrderService directly calls PaymentService
    paymentService.refundOrderPayment(order);
}
```

This works, but notice the coupling. `OrderService` knows about `OrderItemService` (for stock restoration) and `PaymentService` (for refunds). If you wanted to add notification emails when an order is cancelled, you'd add yet another direct dependency:

```java
// More coupling — OrderService now also knows about notifications
notificationService.notifyCustomer(order, "Your order was cancelled");
```

Every new feature that reacts to order events requires modifying `OrderService`. This violates the Open/Closed Principle — the class is never "closed" for modification.

### The Solution: Event-Driven Communication

With messaging, `OrderService` doesn't call anyone. It publishes an event — "an order was cancelled" — and walks away. Anyone who cares about that event subscribes to it independently:

```
OrderService
    │
    ├── publishes "order.cancelled" event
    │
    ▼
RabbitMQ (message broker)
    │
    ├──► NotificationService (creates notification, sends email)
    ├──► AnalyticsService (updates dashboards)   ← future
    └──► AuditService (logs the action)          ← future
```

`OrderService` has zero knowledge of who's listening. You can add new consumers without touching order code.

### Synchronous vs Asynchronous — When to Use Each

Not everything should be async. Here's the rule of thumb:

| Use direct calls (synchronous) when... | Use messaging (async) when... |
|---|---|
| The caller NEEDS the result to continue | The caller doesn't need the result |
| Failure should roll back the whole operation | Failure shouldn't affect the main operation |
| Operations are in the same transaction boundary | Operations are independent concerns |

**In your project:**
- Stock reduction during order placement → synchronous (if it fails, the order must not be created)
- Payment validation before confirmation → synchronous (confirmation depends on payment status)
- Sending notification emails → asynchronous (email failure shouldn't fail the order)
- Creating notification records → asynchronous (the customer doesn't need to see the notification instantly)

---

## 2. RabbitMQ Core Concepts

Before writing code, understand how RabbitMQ routes messages. There are four concepts:

### Producer

The code that sends a message. In your project, `OrderService` and `PaymentService` are producers. They don't send messages directly to queues — they send them to an exchange.

### Exchange

A routing mechanism. The exchange receives messages from producers and routes them to queues based on rules. Think of it as a post office — it looks at the address (routing key) and puts the letter in the right mailbox (queue).

There are different types of exchanges:

| Type | Routing Logic | Use Case |
|---|---|---|
| **Direct** | Exact routing key match | One message → one specific queue |
| **Topic** | Pattern matching with wildcards | One message → multiple queues based on pattern |
| **Fanout** | Broadcasts to all bound queues | One message → every queue |

**We'll use a Topic Exchange** because it gives the most flexibility. A queue can listen to:
- `order.placed` — just order placements
- `order.*` — all order events
- `#` — everything

### Queue

Where messages wait to be consumed. A queue is bound to an exchange with a routing key pattern. Messages sit in the queue until a consumer processes them.

### Consumer

The code that reads from a queue and acts on the message. In your project, `NotificationService` is a consumer — it reads order events from the queue and creates notifications.

### How They Connect — The Full Flow

```
Producer (OrderService)
    │
    │  publishes message with routing key "order.placed"
    ▼
Topic Exchange ("order.exchange")
    │
    │  matches routing key against bindings
    │
    ├── binding: "order.*" ──► Queue: "order.notification.queue"
    │                              │
    │                              ▼
    │                         Consumer (NotificationListener)
    │                              │
    │                              └── creates NotificationT record
    │                                  sends email
    │
    └── binding: "order.placed" ──► Queue: "order.analytics.queue"  ← future
```

### Routing Key Patterns

Topic exchanges support wildcards:
- `*` matches exactly one word: `order.*` matches `order.placed`, `order.cancelled` but NOT `order.state.changed`
- `#` matches zero or more words: `order.#` matches `order.placed`, `order.state.changed`, and even `order`

---

## 3. Story 6.1 — RabbitMQ Setup & Event Infrastructure

### 3.1 Docker Setup

Create a `docker-compose.yml` at the project root:

```yaml
services:
  rabbitmq:
    image: rabbitmq:3-management
    container_name: ops-rabbitmq
    ports:
      - "5672:5672"      # AMQP protocol port (your app connects here)
      - "15672:15672"    # Management UI (you browse here)
    environment:
      RABBITMQ_DEFAULT_USER: ${RABBITMQ_USER:?error}
      RABBITMQ_DEFAULT_PASS: ${RABBITMQ_PASSWORD:?error}
    volumes:
      - rabbitmq_data:/var/lib/rabbitmq

volumes:
  rabbitmq_data:
```

Run it:
```bash
docker-compose up -d
```

Access the management console at `http://localhost:15672` (login with the credentials you set). This is where you'll verify exchanges, queues, and messages during development.

### 3.2 Maven Dependency

Add to your `pom.xml`:

```xml
<dependency>
    <groupId>org.springframework.boot</groupId>
    <artifactId>spring-boot-starter-amqp</artifactId>
</dependency>
```

This brings in Spring AMQP, which provides `RabbitTemplate` for publishing messages, `@RabbitListener` for consuming messages, and auto-configuration for connecting to RabbitMQ.

### 3.3 Application Configuration

Add to your `application.yaml`:

```yaml
spring:
  rabbitmq:
    host: ${RABBITMQ_HOST:localhost}
    port: ${RABBITMQ_PORT:5672}
    username: ${RABBITMQ_USER}
    password: ${RABBITMQ_PASSWORD}
```

Credentials come from environment variables, consistent with how you handle `JWT_SECRET_KEY`.

### 3.4 RabbitMQ Configuration Class

Create a new config class. This declares the exchange, queues, and bindings programmatically. When Spring starts, it creates these in RabbitMQ automatically.

```
config/
├── JpaAuditingConfig.java
├── JwtProperties.java
├── SecurityConfig.java
└── RabbitMQConfig.java          ← new
```

```java
package org.viators.orderprocessingsystem.config;

import org.springframework.amqp.core.*;
import org.springframework.amqp.support.converter.Jackson2JsonMessageConverter;
import org.springframework.amqp.support.converter.MessageConverter;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class RabbitMQConfig {

    // ── Exchange ────────────────────────────────────────
    public static final String ORDER_EXCHANGE = "order.exchange";

    // ── Queues ──────────────────────────────────────────
    public static final String NOTIFICATION_QUEUE = "order.notification.queue";

    // ── Routing Keys ────────────────────────────────────
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
        // durable: true means the queue survives broker restart
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
    public MessageConverter jsonMessageConverter() {
        return new Jackson2JsonMessageConverter();
    }
}
```

**Why a Topic Exchange and not Direct?**

With a Direct exchange, you'd need one binding per routing key per queue. The notification queue needs to receive `order.placed`, `order.confirmed`, `order.shipped`, etc. — that's 7 separate bindings.

With a Topic exchange, `order.*` and `payment.*` cover everything in two bindings. And when you add a new event like `order.refunded`, the notification queue automatically receives it without any config change.

### 3.5 Event DTOs

Create event classes that represent the data published to the exchange. These are NOT your entity classes — events carry only the data that consumers need.

```
messaging/
├── event/
│   ├── OrderEvent.java
│   └── PaymentEvent.java
```

```java
package org.viators.orderprocessingsystem.messaging.event;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

public record OrderEvent(
    String eventId,
    String eventType,
    Instant timestamp,
    String orderUuid,
    String customerUuid,
    String customerEmail,
    String orderState,
    BigDecimal totalAmount
) {
    public static OrderEvent of(String eventType, String orderUuid,
                                 String customerUuid, String customerEmail,
                                 String orderState, BigDecimal totalAmount) {
        return new OrderEvent(
            UUID.randomUUID().toString(),
            eventType,
            Instant.now(),
            orderUuid,
            customerUuid,
            customerEmail,
            orderState,
            totalAmount
        );
    }
}
```

```java
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
    public static PaymentEvent of(String eventType, String paymentUuid,
                                   String orderUuid, String customerUuid,
                                   String customerEmail, String paymentState,
                                   BigDecimal amount, String paymentMethod,
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
```

**Why include `customerEmail` in the event?**

The notification consumer needs the email to send notifications. Without it, the consumer would have to query the user service to get the email — which creates coupling (the very thing we're trying to eliminate). Events should carry enough data for consumers to act independently.

### 3.6 Verification

After implementing, start the application and check:

1. RabbitMQ management console (`http://localhost:15672`)
2. Navigate to Exchanges → `order.exchange` should exist as a Topic exchange
3. Navigate to Queues → `order.notification.queue` should exist
4. Click on the queue → Bindings tab shows `order.*` and `payment.*` bindings

---

## 4. Story 6.2 — Order Event Publishing

### 4.1 The Event Publisher

Create a dedicated component for publishing. This keeps RabbitMQ concerns out of your service classes.

```
messaging/
├── event/
│   ├── OrderEvent.java
│   ├── PaymentEvent.java
│   ├── OrderPlacedEvent.java
│   ├── OrderStateChangedEvent.java
│   └── PaymentProcessedEvent.java
└── publisher/
    └── OrderEventPublisher.java
```

```java
package org.viators.orderprocessingsystem.messaging.publisher;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.stereotype.Component;
import org.viators.orderprocessingsystem.config.RabbitMQConfig;
import org.viators.orderprocessingsystem.messaging.event.OrderEvent;
import org.viators.orderprocessingsystem.messaging.event.PaymentEvent;

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
            log.info("Published event [{}] for order [{}] with routing key [{}]",
                event.eventType(), event.orderUuid(), routingKey);
        } catch (Exception e) {
            // BR-031: Event publishing must not block the primary operation.
            // If RabbitMQ is down, log and continue — the order operation
            // should still succeed.
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
            log.info("Published event [{}] for payment [{}] with routing key [{}]",
                event.eventType(), event.paymentUuid(), routingKey);
        } catch (Exception e) {
            log.error("Failed to publish event [{}] for payment [{}]: {}",
                event.eventType(), event.paymentUuid(), e.getMessage(), e);
        }
    }
}
```

**Why try-catch instead of letting exceptions propagate?**

BR-031 says: "If the messaging system is temporarily unavailable, the order operation should still succeed." If we let the exception propagate, the `@Transactional` method would roll back — meaning the order wouldn't be saved either. The try-catch ensures the order succeeds even if RabbitMQ is down.

### 4.2 Publishing After Transaction Commits

There's a subtle but important problem. Consider this:

```java
@Transactional
public OrderDetailsResponse create(...) {
    // ... save order ...
    orderEventPublisher.publishOrderEvent(event, "order.placed");  // publishes NOW
    return response;
}
// transaction commits AFTER the method returns
```

The event is published *before* the transaction commits. If the transaction rolls back (due to a constraint violation, for example), the event has already been sent. Consumers would process an event for an order that doesn't exist.

**The solution: `@TransactionalEventListener`**

Spring's `@TransactionalEventListener` fires *after* the transaction commits. Here's how to use it:

**Step 1: Define Spring ApplicationEvents (internal triggers)**

These are Spring-internal events — NOT the RabbitMQ messages. Think of them as a bridge between "transaction committed" and "publish to RabbitMQ."

```java
package org.viators.orderprocessingsystem.messaging.event;

public record OrderPlacedEvent(OrderEvent orderEvent) {}
```

```java
package org.viators.orderprocessingsystem.messaging.event;

public record OrderStateChangedEvent(OrderEvent orderEvent, String routingKey) {}
```

```java
package org.viators.orderprocessingsystem.messaging.event;

public record PaymentProcessedEvent(PaymentEvent paymentEvent, String routingKey) {}
```

**Step 2: Create a listener that fires after commit**

```
messaging/
├── event/
│   ├── OrderEvent.java
│   ├── PaymentEvent.java
│   ├── OrderPlacedEvent.java
│   ├── OrderStateChangedEvent.java
│   └── PaymentProcessedEvent.java
├── publisher/
│   └── OrderEventPublisher.java
└── listener/
    └── TransactionalEventPublisher.java
```

```java
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

    @TransactionalEventListener
    public void handlePaymentProcessed(PaymentProcessedEvent event) {
        orderEventPublisher.publishPaymentEvent(
            event.paymentEvent(), event.routingKey()
        );
    }
}
```

**Step 3: Fire the Spring event from your service methods**

Inject `ApplicationEventPublisher` into `OrderService` and `PaymentService`:

```java
// In OrderService — add this field:
private final ApplicationEventPublisher applicationEventPublisher;
```

Then at the end of each transactional method, publish the internal event.

**Order creation — add at the end of `OrderService.create()`:**

```java
// After: order = orderRepository.save(order);
// Before: return OrderDetailsResponse.from(order, PaymentStateEnum.PENDING);

applicationEventPublisher.publishEvent(new OrderPlacedEvent(
    OrderEvent.of(
        "ORDER_PLACED",
        order.getUuid(),
        customer.getUuid(),
        customer.getEmail(),
        order.getOrderState().name(),
        order.getTotalAmount()
    )
));
```

**Order cancellation — add at the end of `OrderService.cancelOrder()`:**

```java
// After: paymentService.refundOrderPayment(order);

applicationEventPublisher.publishEvent(new OrderStateChangedEvent(
    OrderEvent.of(
        "ORDER_CANCELLED",
        order.getUuid(),
        order.getCustomer().getUuid(),
        order.getCustomer().getEmail(),
        OrderStateEnum.CANCELLED.name(),
        order.getTotalAmount()
    ),
    RabbitMQConfig.ORDER_CANCELLED_KEY
));
```

**Order state transitions — add at the end of `OrderService.changeOrderState()`, after the switch block:**

```java
// After the switch block completes, the order's state has been updated.
// Determine the routing key and publish:

String routingKey = switch (order.getOrderState()) {
    case CONFIRMED -> RabbitMQConfig.ORDER_CONFIRMED_KEY;
    case SHIPPED -> RabbitMQConfig.ORDER_SHIPPED_KEY;
    case DELIVERED -> RabbitMQConfig.ORDER_DELIVERED_KEY;
    default -> null;
};

if (routingKey != null) {
    applicationEventPublisher.publishEvent(new OrderStateChangedEvent(
        OrderEvent.of(
            "ORDER_" + order.getOrderState().name(),
            order.getUuid(),
            order.getCustomer().getUuid(),
            order.getCustomer().getEmail(),
            order.getOrderState().name(),
            order.getTotalAmount()
        ),
        routingKey
    ));
}
```

**Payment processing — add at the end of `PaymentService.create()`:**

```java
// In PaymentService — add this field:
private final ApplicationEventPublisher applicationEventPublisher;

// After: payment = paymentRepository.save(payment);
// Before: return PaymentDetailsResponse.from(payment);

String routingKey = PaymentStateEnum.SUCCESS.equals(payment.getPaymentState())
    ? RabbitMQConfig.PAYMENT_SUCCESS_KEY
    : RabbitMQConfig.PAYMENT_FAILED_KEY;

applicationEventPublisher.publishEvent(new PaymentProcessedEvent(
    PaymentEvent.of(
        "PAYMENT_" + payment.getPaymentState().name(),
        payment.getUuid(),
        order.getUuid(),
        order.getCustomer().getUuid(),
        order.getCustomer().getEmail(),
        payment.getPaymentState().name(),
        payment.getAmount(),
        payment.getPaymentMethod().name(),
        payment.getFailureReason()
    ),
    routingKey
));
```

### 4.3 The Full Event Flow

```
OrderService.create()
    │
    ├── saves order to DB
    ├── publishes OrderPlacedEvent (Spring internal event)
    │
    ▼ (transaction commits)
    │
TransactionalEventPublisher.handleOrderPlaced()
    │
    ├── calls OrderEventPublisher.publishOrderEvent()
    │
    ▼
RabbitTemplate.convertAndSend()
    │
    ├── serializes OrderEvent to JSON
    ├── sends to "order.exchange" with key "order.placed"
    │
    ▼
RabbitMQ
    │
    ├── matches "order.placed" against "order.*" binding
    ├── routes message to "order.notification.queue"
    │
    ▼
OrderNotificationListener (Story 7.1)
    │
    ├── deserializes JSON to OrderEvent
    ├── calls NotificationService.createFromOrderEvent()
    │
    ▼
NotificationService
    ├── creates NotificationT record in DB
    └── sends simulated email
```

---

## 5. Story 7.1 — Notification Service

### 5.1 Package Structure

The notification module should be fully independent — no imports from `order/`, `payment/`, `product/`, or `user/`. It only knows about events.

```
notification/
├── NotificationController.java
├── NotificationRepository.java
├── NotificationService.java
├── NotificationT.java
├── NotificationTypeEnum.java
├── dto/
│   └── response/
│       └── NotificationResponse.java
└── listener/
    └── OrderNotificationListener.java
```

### 5.2 Notification Type Enum

```java
package org.viators.orderprocessingsystem.notification;

public enum NotificationTypeEnum {
    ORDER_PLACED,
    ORDER_CONFIRMED,
    ORDER_SHIPPED,
    ORDER_DELIVERED,
    ORDER_CANCELLED,
    PAYMENT_SUCCESS,
    PAYMENT_FAILED
}
```

### 5.3 Notification Entity

```java
package org.viators.orderprocessingsystem.notification;

import jakarta.persistence.*;
import lombok.*;
import lombok.experimental.SuperBuilder;
import org.viators.orderprocessingsystem.common.BaseEntity;

@Entity
@Table(name = "notifications")
@Getter
@Setter
@ToString
@SuperBuilder
@NoArgsConstructor
@AllArgsConstructor
public class NotificationT extends BaseEntity {

    @Column(name = "customer_uuid", nullable = false)
    private String customerUuid;

    @Column(name = "customer_email")
    private String customerEmail;

    @Column(name = "title", nullable = false)
    private String title;

    @Column(name = "message", nullable = false, length = 1000)
    private String message;

    @Enumerated(EnumType.STRING)
    @Column(name = "notification_type", nullable = false)
    private NotificationTypeEnum notificationType;

    @Column(name = "is_read", nullable = false)
    @Builder.Default
    private Boolean isRead = false;

    @Column(name = "email_sent", nullable = false)
    @Builder.Default
    private Boolean emailSent = false;
}
```

**Notice:** No `@ManyToOne` to `UserT` or `OrderT`. The notification module stores `customerUuid` as a plain String — it doesn't need a JPA relationship to the user entity. This is the decoupling in action.

### 5.4 The Event Listener (Consumer)

This is where RabbitMQ messages are consumed. The `@RabbitListener` annotation tells Spring to connect to the specified queue and call this method whenever a message arrives.

```java
package org.viators.orderprocessingsystem.notification.listener;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.stereotype.Component;
import org.viators.orderprocessingsystem.config.RabbitMQConfig;
import org.viators.orderprocessingsystem.messaging.event.OrderEvent;
import org.viators.orderprocessingsystem.messaging.event.PaymentEvent;
import org.viators.orderprocessingsystem.notification.NotificationService;

@Component
@RequiredArgsConstructor
@Slf4j
public class OrderNotificationListener {

    private final NotificationService notificationService;

    @RabbitListener(queues = RabbitMQConfig.NOTIFICATION_QUEUE)
    public void handleOrderEvent(OrderEvent event) {
        log.info("Received order event: [{}] for order [{}]",
            event.eventType(), event.orderUuid());

        try {
            notificationService.createFromOrderEvent(event);
        } catch (Exception e) {
            log.error("Failed to process order event [{}]: {}",
                event.eventId(), e.getMessage(), e);
        }
    }

    @RabbitListener(queues = RabbitMQConfig.NOTIFICATION_QUEUE)
    public void handlePaymentEvent(PaymentEvent event) {
        log.info("Received payment event: [{}] for payment [{}]",
            event.eventType(), event.paymentUuid());

        try {
            notificationService.createFromPaymentEvent(event);
        } catch (Exception e) {
            log.error("Failed to process payment event [{}]: {}",
                event.eventId(), e.getMessage(), e);
        }
    }
}
```

**Important note on message type resolution:**

Both listeners read from the same queue. The `Jackson2JsonMessageConverter` adds a `__TypeId__` header to each message when publishing. On the consumer side, it uses this header to determine which Java class to deserialize into, and therefore which listener method to invoke. This works out of the box when producer and consumer are in the same application (same classpath).

If you run into deserialization issues, fall back to a single listener with manual type resolution:

```java
@RabbitListener(queues = RabbitMQConfig.NOTIFICATION_QUEUE)
public void handleEvent(org.springframework.amqp.core.Message message) {
    String typeId = message.getMessageProperties().getHeaders()
        .getOrDefault("__TypeId__", "").toString();

    if (typeId.contains("OrderEvent")) {
        OrderEvent event = objectMapper.readValue(message.getBody(), OrderEvent.class);
        notificationService.createFromOrderEvent(event);
    } else if (typeId.contains("PaymentEvent")) {
        PaymentEvent event = objectMapper.readValue(message.getBody(), PaymentEvent.class);
        notificationService.createFromPaymentEvent(event);
    }
}
```

Start with the dual-listener approach. Fall back to the single-listener approach only if needed.

### 5.5 Notification Service

```java
package org.viators.orderprocessingsystem.notification;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.viators.orderprocessingsystem.exceptions.ResourceNotFoundException;
import org.viators.orderprocessingsystem.messaging.event.OrderEvent;
import org.viators.orderprocessingsystem.messaging.event.PaymentEvent;

import java.math.BigDecimal;

@Service
@RequiredArgsConstructor
@Slf4j
@Transactional(readOnly = true)
public class NotificationService {

    private final NotificationRepository notificationRepository;

    @Transactional
    public void createFromOrderEvent(OrderEvent event) {
        NotificationTypeEnum type = NotificationTypeEnum.valueOf(event.eventType());
        String title = buildOrderTitle(type, event.orderUuid());
        String message = buildOrderMessage(type, event.orderUuid(), event.totalAmount());

        NotificationT notification = NotificationT.builder()
            .customerUuid(event.customerUuid())
            .customerEmail(event.customerEmail())
            .title(title)
            .message(message)
            .notificationType(type)
            .build();

        notificationRepository.save(notification);
        log.info("Created notification [{}] for customer [{}]", type, event.customerUuid());
    }

    @Transactional
    public void createFromPaymentEvent(PaymentEvent event) {
        NotificationTypeEnum type = NotificationTypeEnum.valueOf(event.eventType());
        String title = buildPaymentTitle(type, event.orderUuid());
        String message = buildPaymentMessage(type, event.orderUuid(),
            event.amount(), event.failureReason());

        NotificationT notification = NotificationT.builder()
            .customerUuid(event.customerUuid())
            .customerEmail(event.customerEmail())
            .title(title)
            .message(message)
            .notificationType(type)
            .build();

        notificationRepository.save(notification);
        log.info("Created notification [{}] for customer [{}]", type, event.customerUuid());
    }

    public Page<NotificationResponse> getNotifications(String customerUuid, Pageable pageable) {
        return notificationRepository.findAllByCustomerUuid(customerUuid, pageable)
            .map(NotificationResponse::from);
    }

    public long getUnreadCount(String customerUuid) {
        return notificationRepository.countByCustomerUuidAndIsReadFalse(customerUuid);
    }

    @Transactional
    public void markAsRead(String notificationUuid) {
        NotificationT notification = notificationRepository.findByUuid(notificationUuid)
            .orElseThrow(() -> new ResourceNotFoundException(
                "Notification", "uuid", notificationUuid));
        notification.setIsRead(true);
    }

    // ── Message Builders ──────────────────────────────────────

    private String buildOrderTitle(NotificationTypeEnum type, String orderUuid) {
        return switch (type) {
            case ORDER_PLACED -> "Order Confirmation - " + orderUuid;
            case ORDER_CONFIRMED -> "Order Confirmed - " + orderUuid;
            case ORDER_SHIPPED -> "Your Order Has Shipped - " + orderUuid;
            case ORDER_DELIVERED -> "Order Delivered - " + orderUuid;
            case ORDER_CANCELLED -> "Order Cancelled - " + orderUuid;
            default -> "Order Update - " + orderUuid;
        };
    }

    private String buildOrderMessage(NotificationTypeEnum type,
                                      String orderUuid, BigDecimal amount) {
        return switch (type) {
            case ORDER_PLACED ->
                "Your order %s has been placed successfully. Total: %s"
                    .formatted(orderUuid, amount);
            case ORDER_CONFIRMED ->
                "Your order %s has been confirmed and is being prepared."
                    .formatted(orderUuid);
            case ORDER_SHIPPED ->
                "Your order %s has been shipped. It's on the way!"
                    .formatted(orderUuid);
            case ORDER_DELIVERED ->
                "Your order %s has been delivered. Enjoy!"
                    .formatted(orderUuid);
            case ORDER_CANCELLED ->
                "Your order %s has been cancelled. A refund of %s has been initiated."
                    .formatted(orderUuid, amount);
            default ->
                "There's an update on your order %s."
                    .formatted(orderUuid);
        };
    }

    private String buildPaymentTitle(NotificationTypeEnum type, String orderUuid) {
        return switch (type) {
            case PAYMENT_SUCCESS -> "Payment Received - " + orderUuid;
            case PAYMENT_FAILED -> "Payment Failed - " + orderUuid;
            default -> "Payment Update - " + orderUuid;
        };
    }

    private String buildPaymentMessage(NotificationTypeEnum type, String orderUuid,
                                        BigDecimal amount, String failureReason) {
        return switch (type) {
            case PAYMENT_SUCCESS ->
                "Payment of %s received for order %s."
                    .formatted(amount, orderUuid);
            case PAYMENT_FAILED ->
                "Payment for order %s failed. Reason: %s"
                    .formatted(orderUuid,
                        failureReason != null ? failureReason : "Unknown");
            default ->
                "There's an update on your payment for order %s."
                    .formatted(orderUuid);
        };
    }
}
```

### 5.6 Notification Repository

```java
package org.viators.orderprocessingsystem.notification;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
public interface NotificationRepository extends JpaRepository<NotificationT, Long> {

    Page<NotificationT> findAllByCustomerUuid(String customerUuid, Pageable pageable);

    long countByCustomerUuidAndIsReadFalse(String customerUuid);

    Optional<NotificationT> findByUuid(String uuid);
}
```

### 5.7 Notification Response DTO

```java
package org.viators.orderprocessingsystem.notification;

import java.time.Instant;

public record NotificationResponse(
    String uuid,
    String title,
    String message,
    String notificationType,
    boolean isRead,
    Instant createdAt
) {
    public static NotificationResponse from(NotificationT notification) {
        return new NotificationResponse(
            notification.getUuid(),
            notification.getTitle(),
            notification.getMessage(),
            notification.getNotificationType().name(),
            notification.getIsRead(),
            notification.getCreatedAt()
        );
    }
}
```

### 5.8 Notification Controller

```java
package org.viators.orderprocessingsystem.notification;

import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.web.PageableDefault;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/notifications")
@RequiredArgsConstructor
public class NotificationController {

    private final NotificationService notificationService;

    @GetMapping
    public ResponseEntity<Page<NotificationResponse>> getNotifications(
        @AuthenticationPrincipal(expression = "uuid") String customerUuid,
        @PageableDefault(sort = "createdAt", direction = Sort.Direction.DESC)
        Pageable pageable) {
        return ResponseEntity.ok(
            notificationService.getNotifications(customerUuid, pageable));
    }

    @GetMapping("/unread-count")
    public ResponseEntity<Long> getUnreadCount(
        @AuthenticationPrincipal(expression = "uuid") String customerUuid) {
        return ResponseEntity.ok(
            notificationService.getUnreadCount(customerUuid));
    }

    @PutMapping("/{notificationUuid}/read")
    public ResponseEntity<Void> markAsRead(@PathVariable String notificationUuid) {
        notificationService.markAsRead(notificationUuid);
        return ResponseEntity.noContent().build();
    }
}
```

---

## 6. Story 7.2 — Email Notifications

### 6.1 Email Service Interface

Define an interface so the implementation can be swapped later (simulated now, real SMTP in the future).

```
notification/
├── email/
│   ├── EmailService.java          ← interface
│   └── SimulatedEmailService.java ← logs instead of sending
```

```java
package org.viators.orderprocessingsystem.notification.email;

public interface EmailService {
    void sendEmail(String to, String subject, String body);
}
```

### 6.2 Simulated Implementation

```java
package org.viators.orderprocessingsystem.notification.email;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

@Service
@Slf4j
public class SimulatedEmailService implements EmailService {

    @Override
    public void sendEmail(String to, String subject, String body) {
        log.info("""
            ══════════════════════════════════════════════
            SIMULATED EMAIL
            To:      {}
            Subject: {}
            Body:    {}
            ══════════════════════════════════════════════
            """, to, subject, body);
    }
}
```

When you're ready for real emails, create a `SmtpEmailService` that implements the same interface and uses `JavaMailSender`. Swap the active implementation via a `@Profile` or `@ConditionalOnProperty` annotation. The notification code doesn't change at all.

### 6.3 Integrating Email into the Notification Service

Add the `EmailService` dependency to `NotificationService` and send emails after creating each notification:

```java
// Add this field to NotificationService:
private final EmailService emailService;

// Add this private method:
private void sendEmailForNotification(NotificationT notification) {
    try {
        emailService.sendEmail(
            notification.getCustomerEmail(),
            notification.getTitle(),
            notification.getMessage()
        );
        notification.setEmailSent(true);
        log.info("Email sent for notification [{}]", notification.getUuid());
    } catch (Exception e) {
        // Email failure should NOT fail the notification creation.
        // The notification record exists — the email just wasn't sent.
        notification.setEmailSent(false);
        log.error("Failed to send email for notification [{}]: {}",
            notification.getUuid(), e.getMessage(), e);
    }
}
```

Call `sendEmailForNotification(notification)` after `notificationRepository.save(notification)` in both `createFromOrderEvent()` and `createFromPaymentEvent()`.

### 6.4 MailHog Setup (Optional — Real SMTP Testing)

If you want to see rendered emails in a browser during development, add MailHog to your `docker-compose.yml`:

```yaml
  mailhog:
    image: mailhog/mailhog
    container_name: ops-mailhog
    ports:
      - "1025:1025"    # SMTP server
      - "8025:8025"    # Web UI to view emails
```

And add to `application.yaml`:

```yaml
spring:
  mail:
    host: localhost
    port: 1025
```

Then replace `SimulatedEmailService` with a real implementation using `JavaMailSender`. View sent emails at `http://localhost:8025`.

---

## 7. Testing & Verification

### 7.1 Verify the Full Flow

1. Start RabbitMQ: `docker-compose up -d`
2. Start your application
3. Open RabbitMQ console: `http://localhost:15672`
4. Place an order via your API
5. Check RabbitMQ console: the `order.notification.queue` should show a message was received and consumed
6. Check application logs — you should see the full chain:
   ```
   Published event [ORDER_PLACED] for order [xxx] with routing key [order.placed]
   Received order event: [ORDER_PLACED] for order [xxx]
   Created notification [ORDER_PLACED] for customer [xxx]
   SIMULATED EMAIL To: customer@email.com Subject: Order Confirmation - xxx
   ```
7. Check the database: a row should exist in the `notifications` table

### 7.2 Test RabbitMQ Down Scenario

1. Stop RabbitMQ: `docker-compose stop rabbitmq`
2. Place an order via your API
3. Verify: the order should be created successfully (201 response), but you should see an error log: `Failed to publish event [ORDER_PLACED]...`
4. Start RabbitMQ again: `docker-compose start rabbitmq`
5. Note: the missed event is lost (no retry in Phase 2). This is acceptable per the acceptance criteria.

### 7.3 Test Each Event Type

| Action | Expected Routing Key | Expected Notification Title |
|---|---|---|
| Place order | `order.placed` | "Order Confirmation - {uuid}" |
| Submit successful payment | `payment.success` | "Payment Received - {uuid}" |
| Submit failed payment | `payment.failed` | "Payment Failed - {uuid}" |
| Admin confirms order | `order.confirmed` | "Order Confirmed - {uuid}" |
| Admin ships order | `order.shipped` | "Your Order Has Shipped - {uuid}" |
| Admin delivers order | `order.delivered` | "Order Delivered - {uuid}" |
| Cancel order | `order.cancelled` | "Order Cancelled - {uuid}" |

---

## 8. Common Pitfalls

### Pitfall 1: Event Published Before Transaction Commits

**Problem:** Publishing inside `@Transactional` means the event fires even if the transaction rolls back.

**Solution:** Use `@TransactionalEventListener` as shown in Story 6.2. The event only fires after successful commit.

### Pitfall 2: Consumer Exceptions Kill the Listener

**Problem:** If your `@RabbitListener` method throws an uncaught exception, Spring AMQP will retry the message (potentially infinitely with default config).

**Solution:** Always wrap listener logic in try-catch. In Phase 2, log and continue. In production, you'd use a dead-letter queue for retries.

### Pitfall 3: JSON Deserialization Failures

**Problem:** The consumer can't deserialize the message because the class structure changed.

**Solution:** Event DTOs should be stable. Don't rename fields. Add new fields as nullable. If you need breaking changes, version your events.

### Pitfall 4: Notification Module Importing Order Entities

**Problem:** If `NotificationService` imports `OrderT` or `UserT`, you've re-created the coupling you were trying to eliminate.

**Solution:** The notification module should ONLY know about event DTOs (`OrderEvent`, `PaymentEvent`). It stores customer information as plain strings, not JPA relationships.

### Pitfall 5: Circular Dependency via Events

**Problem:** `OrderService` publishes events → consumer calls back into `OrderService`.

**Solution:** Consumers should never call back into the producer. They act on the event data independently. If they need more data, the event should carry it.
