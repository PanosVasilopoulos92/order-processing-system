package org.viators.orderprocessingsystem.notifications;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.viators.orderprocessingsystem.exceptions.ResourceNotFoundException;
import org.viators.orderprocessingsystem.messaging.event.OrderEvent;
import org.viators.orderprocessingsystem.messaging.event.PaymentEvent;
import org.viators.orderprocessingsystem.notifications.dto.response.NotificationResponse;

import java.math.BigDecimal;

@Service
@RequiredArgsConstructor
@Slf4j
@Transactional(readOnly = true)
public class NotificationService {

    private final NotificationRepository notificationRepository;
    private final EmailService emailService;

    public NotificationResponse getNotification(String notificationUuid) {
        NotificationT notification = notificationRepository.findByUuid(notificationUuid)
            .orElseThrow(() -> new ResourceNotFoundException("Notification", "uuid", notificationUuid));

        return NotificationResponse.from(notification);
    }

    public Page<NotificationResponse> getAllNotificationsForCustomer(String customerUuid, Pageable pageable) {
        return notificationRepository.findAllByCustomerUuid(customerUuid, pageable)
            .map(NotificationResponse::from);
    }

    public Long numberOfUnreadMessages(String customerUuid) {
        return notificationRepository.countByCustomerUuidAndIsReadFalse(customerUuid);
    }

    @Transactional
    public void markAsRead(String notificationUuid) {
        NotificationT notification = notificationRepository.findByUuid(notificationUuid)
            .orElseThrow(() -> new ResourceNotFoundException("Notification", "uuid", notificationUuid));

        notification.setIsRead(true);
    }

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

        if (event.customerEmail() != null) {
            boolean sent = emailService.sendNotificationEmail(event.customerEmail(), title, message);
            notification.setEmailSent(sent);
        }
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

        if (event.customerEmail() != null) {
            boolean sent = emailService.sendNotificationEmail(event.customerEmail(), title, message);
            notification.setEmailSent(sent);
        }
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

    private String buildOrderMessage(NotificationTypeEnum type, String orderUuid, BigDecimal amount) {
        return switch (type) {
            case ORDER_PLACED -> "Your order %s has been placed successfully. Total: %s"
                .formatted(orderUuid, amount);
            case ORDER_CONFIRMED -> "Your order %s has been confirmed and is being prepared."
                .formatted(orderUuid);
            case ORDER_SHIPPED -> "Your order %s has been shipped. It's on the way!"
                .formatted(orderUuid);
            case ORDER_DELIVERED -> "Your order %s has been delivered. Enjoy!"
                .formatted(orderUuid);
            case ORDER_CANCELLED -> "Your order %s has been cancelled. A refund of %s has been initiated."
                .formatted(orderUuid, amount);
            default -> "There's an update on your order %s."
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
