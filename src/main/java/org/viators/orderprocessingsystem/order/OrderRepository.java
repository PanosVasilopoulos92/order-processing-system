package org.viators.orderprocessingsystem.order;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;
import org.viators.orderprocessingsystem.common.enums.StatusEnum;

import java.util.Optional;

@Repository
public interface OrderRepository extends JpaRepository<OrderT, Long> {

    Optional<OrderT> findByUuidAndStatus(String uuid, StatusEnum status);

}
