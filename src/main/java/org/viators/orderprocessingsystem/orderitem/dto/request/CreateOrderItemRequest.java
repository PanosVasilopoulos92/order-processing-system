package org.viators.orderprocessingsystem.orderitem.dto.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

public record CreateOrderItemRequest(
    @NotNull
    @Positive
    Long quantity,

    @NotBlank
    String productUuid
) {

}
