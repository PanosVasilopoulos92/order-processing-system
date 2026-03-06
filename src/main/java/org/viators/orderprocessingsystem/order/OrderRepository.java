package org.viators.orderprocessingsystem.order;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;
import org.viators.orderprocessingsystem.common.enums.StatusEnum;

import java.util.Optional;

@Repository
public interface OrderRepository extends JpaRepository<OrderT, Long> {

    Optional<OrderT> findByUuidAndStatus(String uuid, StatusEnum status);

    Optional<OrderT> findByUuidAndCustomer_Uuid(String customerUuid, String orderUuid);

    Page<OrderT> findAllByCustomer_Uuid(String customerUuid, Pageable pageable);
}
