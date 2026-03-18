package org.viators.orderprocessingsystem.order.dto.request;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;
import org.viators.orderprocessingsystem.orderitem.dto.request.CreateOrderItemRequest;

import java.util.List;

public record CreateOrderRequest(
    @Valid
    @NotEmpty(message = "At least one order item must be selected to proceed with the order")
    List<CreateOrderItemRequest> orderItemRequests
) {
}
