package org.viators.orderprocessingsystem.user;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;
import org.viators.orderprocessingsystem.common.enums.StatusEnum;

import java.util.Optional;

@Repository
public interface UserRepository extends JpaRepository<UserT, Long> {

    Optional<UserT> findByUuidAndStatus(String userUuid, StatusEnum status);

    Optional<UserT> findByUsername(String username);

    boolean existsByUsername(String username);

    boolean existsByEmail(String email);
}
