package org.viators.orderprocessingsystem.order.dto.request;

import org.viators.orderprocessingsystem.orderitem.dto.request.CreateOrderItemRequest;

import java.util.Set;

public record CreateOrderRequest(
    Set<CreateOrderItemRequest> orderItemRequests
) {
}
