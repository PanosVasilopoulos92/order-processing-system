package org.viators.orderprocessingsystem.product;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;
import org.viators.orderprocessingsystem.common.enums.StatusEnum;

import java.util.List;
import java.util.Optional;

@Repository
public interface ProductRepository extends JpaRepository<ProductT, Long> {

    Optional<ProductT> findByUuidAndStatus(String uuid, StatusEnum status);

    Optional<ProductT> findByUuid(String uuid);

    boolean existsByName(String name);

    List<ProductT> findAllByNameContains(String nameText);

    Page<ProductT> findAllActive(StatusEnum status, Pageable pageable);

}
