package org.viators.orderprocessingsystem.order.dto.request;

import java.util.Set;

public record CreateOrderRequest(
    Set<String> orderItemUuids
) {
}
