package org.viators.orderprocessingsystem.order;

import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.support.ServletUriComponentsBuilder;
import org.viators.orderprocessingsystem.common.enums.OrderStateEnum;
import org.viators.orderprocessingsystem.order.dto.request.CreateOrderRequest;
import org.viators.orderprocessingsystem.order.dto.response.OrderDetailsResponse;

import java.net.URI;

@RestController
@RequestMapping("/api/v1/orders")
@RequiredArgsConstructor
public class OrderController {

    private final OrderService orderService;

    @PostMapping
    public ResponseEntity<OrderDetailsResponse> create(@AuthenticationPrincipal(expression = "uuid") String customerUuid,
                                                       @Valid @RequestBody CreateOrderRequest request) {
        OrderDetailsResponse response = orderService.create(customerUuid, request);
        URI location = ServletUriComponentsBuilder
            .fromCurrentRequest()
            .path("/{orderUuid}")
            .buildAndExpand(response.orderUuid())
            .toUri();

        return ResponseEntity.created(location).body(response);
    }

    @PutMapping("/{orderUuid}/cancel-order")
    public ResponseEntity<Void> cancelOrder(@AuthenticationPrincipal(expression = "uuid") String loggedInUserUuid,
                                            @PathVariable String orderUuid) {
        orderService.cancelOrder(loggedInUserUuid, orderUuid);
        return ResponseEntity.noContent().build();
    }

    @PreAuthorize("hasRole('ADMIN')")
    @PutMapping("/{orderUuid}/change-order-state")
    public ResponseEntity<Void> changeOrderState(@PathVariable String orderUuid,
                                                 @RequestParam OrderStateEnum orderState) {
        orderService.changeOrderState(orderUuid, orderState);
        return ResponseEntity.noContent().build();
    }

}
