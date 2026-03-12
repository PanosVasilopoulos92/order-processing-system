package org.viators.orderprocessingsystem.notifications;

import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.web.PageableDefault;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.viators.orderprocessingsystem.notifications.dto.response.NotificationResponse;

@RestController
@RequestMapping("/api/v1/notifications")
@RequiredArgsConstructor
public class NotificationController {

    private final NotificationService notificationService;

    @GetMapping("/{notificationUuid}")
    public ResponseEntity<NotificationResponse> getNotification(@PathVariable String notificationUuid) {
        return ResponseEntity.ok(notificationService.getNotification(notificationUuid));
    }

    @GetMapping
    public ResponseEntity<Page<NotificationResponse>> getAllNotificationsForCustomer(@RequestParam String customerUuid,
                                                                                     @PageableDefault(size = 20, sort = "createdAt", direction = Sort.Direction.DESC)
                                                                                     Pageable pageable) {
        Page<NotificationResponse> response = notificationService.getAllNotificationsForCustomer(customerUuid, pageable);
        return ResponseEntity.ok(response);
    }

    @PutMapping("/{notificationUuid}/read")
    public ResponseEntity<Void> markAsRead(@PathVariable String notificationUuid) {
        notificationService.markAsRead(notificationUuid);
        return ResponseEntity.noContent().build();
    }
}
