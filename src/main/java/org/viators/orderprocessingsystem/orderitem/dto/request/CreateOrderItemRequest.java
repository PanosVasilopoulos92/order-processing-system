package org.viators.orderprocessingsystem.orderitem.dto.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import org.viators.orderprocessingsystem.orderitem.OrderItemT;

import java.math.BigDecimal;

public record CreateOrderItemRequest(
    @NotNull
    @Positive
    BigDecimal quantity,

    @NotBlank
    String productUuid
) {

    public OrderItemT toEntity() {
        return OrderItemT.builder()
            .quantity(quantity)
            .build();
    }
}
