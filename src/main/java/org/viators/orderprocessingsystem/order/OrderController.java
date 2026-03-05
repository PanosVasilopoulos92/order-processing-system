package org.viators.orderprocessingsystem.order;

import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.support.ServletUriComponentsBuilder;
import org.viators.orderprocessingsystem.order.dto.request.CreateOrderRequest;
import org.viators.orderprocessingsystem.order.dto.response.OrderDetailsResponse;
import org.viators.orderprocessingsystem.user.UserT;

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


}
