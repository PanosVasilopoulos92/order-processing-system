package org.viators.orderprocessingsystem.notifications;

import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.web.PageableDefault;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import org.viators.orderprocessingsystem.notifications.dto.response.NotificationResponse;
import org.viators.orderprocessingsystem.user.UserT;

@RestController
@RequestMapping("/api/v1/notifications")
@RequiredArgsConstructor
public class NotificationController {

    private final NotificationService notificationService;

    @GetMapping("/{notificationUuid}")
    public ResponseEntity<NotificationResponse> getNotification(
            @AuthenticationPrincipal UserT principal,
            @PathVariable String notificationUuid) {
        return ResponseEntity.ok(notificationService.getNotification(principal, notificationUuid));
    }

    @GetMapping
    public ResponseEntity<Page<NotificationResponse>> getAllNotifications(
            @AuthenticationPrincipal UserT principal,
            @RequestParam(required = false) String customerUuid,
            @PageableDefault(size = 20, sort = "createdAt", direction = Sort.Direction.DESC) Pageable pageable) {
        String targetUuid = (principal.isAdminUser() && customerUuid != null) ? customerUuid : principal.getUuid();
        return ResponseEntity.ok(notificationService.getAllNotificationsForCustomer(targetUuid, pageable));
    }

    @PutMapping("/{notificationUuid}/read")
    public ResponseEntity<Void> markAsRead(
            @AuthenticationPrincipal UserT principal,
            @PathVariable String notificationUuid) {
        notificationService.markAsRead(principal, notificationUuid);
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/unread-count")
    public ResponseEntity<Long> getUnreadCount(
            @AuthenticationPrincipal UserT principal,
            @RequestParam(required = false) String customerUuid) {
        String targetUuid = (principal.isAdminUser() && customerUuid != null) ? customerUuid : principal.getUuid();
        return ResponseEntity.ok(notificationService.numberOfUnreadMessages(targetUuid));
    }
}
