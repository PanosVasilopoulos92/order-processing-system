package org.viators.orderprocessingsystem.notifications;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
public interface NotificationRepository extends JpaRepository<NotificationT, Long> {

    Optional<NotificationT> findByUuid(String uuid);

    Page<NotificationT> findAllByCustomerUuid(String customerUuid, Pageable pageable);

    long countByCustomerUuidAndIsReadFalse(String customerUuid);
}
