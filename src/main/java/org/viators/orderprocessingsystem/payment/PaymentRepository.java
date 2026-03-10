package org.viators.orderprocessingsystem.payment;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Set;

@Repository
public interface PaymentRepository extends JpaRepository<PaymentT, Long> {

    Set<PaymentT> findAllByOrder_Uuid(String orderUuid);
}
