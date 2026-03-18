package org.viators.orderprocessingsystem.payment;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;
import org.viators.orderprocessingsystem.common.enums.PaymentStateEnum;

import java.util.Optional;
import java.util.Set;

@Repository
public interface PaymentRepository extends JpaRepository<PaymentT, Long> {

    Set<PaymentT> findAllByOrder_Uuid(String orderUuid);

    Page<PaymentT> findAllByOrder_Uuid(String orderUuid, Pageable pageable);

    Optional<PaymentT> findByOrder_UuidAndPaymentState(String orderUuid, PaymentStateEnum paymentState);
}
