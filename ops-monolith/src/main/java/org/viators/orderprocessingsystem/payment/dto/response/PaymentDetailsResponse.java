package org.viators.orderprocessingsystem.payment.dto.response;

import org.viators.orderprocessingsystem.common.enums.PaymentMethodEnum;
import org.viators.orderprocessingsystem.common.enums.PaymentStateEnum;
import org.viators.orderprocessingsystem.common.enums.PaymentTypeEnum;
import org.viators.orderprocessingsystem.payment.PaymentT;

import java.math.BigDecimal;
import java.time.Instant;

public record PaymentDetailsResponse(
    String paymentUuid,
    String orderUuid,
    BigDecimal amount,
    PaymentMethodEnum paymentMethod,
    PaymentStateEnum paymentState,
    PaymentTypeEnum paymentType,
    Instant createdAt,
    String causeOfPaymentFailure
) {

    public static PaymentDetailsResponse from(PaymentT payment) {
        return new PaymentDetailsResponse(
            payment.getUuid(),
            payment.getOrder().getUuid(),
            payment.getAmount(),
            payment.getPaymentMethod(),
            payment.getPaymentState(),
            payment.getPaymentType(),
            payment.getCreatedAt(),
            payment.getFailureReason()
        );
    }

}
