package org.viators.orderprocessingsystem.payment;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Set;

@Service
@RequiredArgsConstructor
@Slf4j
@Transactional(readOnly = true)
public class PaymentQueryService {

    private final PaymentRepository paymentRepository;

    public Set<PaymentT> getAllPaymentsForOrder(String orderUuid) {
        return paymentRepository.findAllByOrder_Uuid(orderUuid);
    }
}
