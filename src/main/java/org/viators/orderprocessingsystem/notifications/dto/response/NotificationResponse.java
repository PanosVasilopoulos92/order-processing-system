package org.viators.orderprocessingsystem.notifications.dto.response;

import org.viators.orderprocessingsystem.notifications.NotificationT;

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
