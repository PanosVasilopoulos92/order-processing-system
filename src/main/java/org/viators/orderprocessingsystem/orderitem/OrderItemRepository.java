package org.viators.orderprocessingsystem.orderitem;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Collection;
import java.util.List;
import java.util.Set;

@Repository
public interface OrderItemRepository extends JpaRepository<OrderItemT, Long> {

    Set<OrderItemT> findAllByUuidIn(Collection<String> uuids);
}
