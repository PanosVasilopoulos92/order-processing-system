package org.viators.orderprocessingsystem.notifications;

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
