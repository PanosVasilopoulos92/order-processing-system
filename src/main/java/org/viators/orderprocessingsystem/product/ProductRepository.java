package org.viators.orderprocessingsystem.product;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface ProductRepository extends JpaRepository<ProductT, Long> {

    boolean existsByName(String name);
}
