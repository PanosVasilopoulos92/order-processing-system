package org.viators.orderprocessingsystem.payment.dto.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import org.viators.orderprocessingsystem.common.enums.PaymentMethodEnum;

public record CreatePaymentRequest(
    @NotBlank(message = "Order uuid is required")
    String orderUuid,

    @NotNull(message = "Payment method is required")
    PaymentMethodEnum paymentMethod
) {
}
