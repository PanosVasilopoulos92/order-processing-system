package org.viators.orderprocessingsystem.payment;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.viators.orderprocessingsystem.common.enums.PaymentMethodEnum;
import org.viators.orderprocessingsystem.common.enums.PaymentStateEnum;
import org.viators.orderprocessingsystem.common.services.OwnershipAuthorizationService;
import org.viators.orderprocessingsystem.exceptions.BusinessValidationException;
import org.viators.orderprocessingsystem.order.OrderService;
import org.viators.orderprocessingsystem.order.OrderT;
import org.viators.orderprocessingsystem.payment.dto.request.CreatePaymentRequest;
import org.viators.orderprocessingsystem.payment.dto.response.PaymentDetailsResponse;

import java.math.BigDecimal;

@Service
@RequiredArgsConstructor
@Slf4j
@Transactional(readOnly = true)
public class PaymentService {

    private final PaymentRepository paymentRepository;
    private final OrderService orderService;
    private final OwnershipAuthorizationService ownershipAuthorizationService;

    @Transactional
    public PaymentDetailsResponse create(String customerUuid, CreatePaymentRequest request) {
        OrderT order = orderService.getOrderForPayment(request.orderUuid());

        ownershipAuthorizationService.verifyOwnership(customerUuid, order.getCustomer().getUuid());
        for (PaymentT payment : order.getPayments()) {
            if (payment.getPaymentState().equals(PaymentStateEnum.SUCCESS)) {
                throw new BusinessValidationException("For order with uuid: %s has already been payed.".formatted(order.getUuid()));
            }
        }

        PaymentT payment = new PaymentT();
        payment.setPaymentMethod(request.paymentMethod());
        payment.setAmount(order.getTotalAmount());

        // Todo:  currently we use a simulation to decide success or failure of payment
        if (simulateSuccessFailOfPayment(request.paymentMethod(), order.getTotalAmount())) {
            payment.setPaymentState(PaymentStateEnum.SUCCESS);
            order.setIsPaid(true);
        } else {
            payment.setPaymentState(PaymentStateEnum.FAILED);
            payment.setFailureReason("Payment failed because it was made through bank transfer and the amount was more than 500");
        }

        order.addPayment(payment);
        payment = paymentRepository.save(payment);

        return PaymentDetailsResponse.from(payment);
    }

    private boolean simulateSuccessFailOfPayment(PaymentMethodEnum paymentMethod, BigDecimal amount) {
        return switch (paymentMethod) {
            case PaymentMethodEnum.CREDIT_CARD, PaymentMethodEnum.DEBIT_CARD -> true;
            case PaymentMethodEnum.BANK_TRANSFER -> amount.compareTo(new BigDecimal("500")) < 0;
        };
    }
}
