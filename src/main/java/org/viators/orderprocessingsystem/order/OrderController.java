package org.viators.orderprocessingsystem.order;

import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.web.PageableDefault;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.support.ServletUriComponentsBuilder;
import org.viators.orderprocessingsystem.common.enums.OrderStateEnum;
import org.viators.orderprocessingsystem.order.dto.request.CreateOrderRequest;
import org.viators.orderprocessingsystem.order.dto.response.OrderDetailsResponse;
import org.viators.orderprocessingsystem.order.dto.response.OrderSummaryResponse;

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


    @PreAuthorize("@userSecurity.isSelf(#userUuid) or hasRole('ADMIN')")
    @GetMapping("/history")
    public ResponseEntity<Page<OrderSummaryResponse>> getOrdersHistoryPlacedByCustomer(@AuthenticationPrincipal(expression = "uuid") String userUuid,
                                                                                       @RequestParam(required = false) OrderStateEnum orderState,
                                                                                       @PageableDefault(sort = "createdAt", direction = Sort.Direction.DESC)
                                                                                       Pageable pageable) {
        Page<OrderSummaryResponse> response = orderService.getOrdersHistoryPlacedByCustomer(userUuid, orderState, pageable);
        return ResponseEntity.ok(response);
    }

    @PreAuthorize("@userSecurity.isSelf(#userUuid) or hasRole('ADMIN')")
    @GetMapping("/{orderUuid}/details")
    public ResponseEntity<OrderDetailsResponse> getOrderDetails(@AuthenticationPrincipal(expression = "uuid") String userUuid,
                                                                @PathVariable String orderUuid) {
        OrderDetailsResponse response = orderService.getOrderDetails(userUuid, orderUuid);
        return ResponseEntity.ok(response);
    }
}
