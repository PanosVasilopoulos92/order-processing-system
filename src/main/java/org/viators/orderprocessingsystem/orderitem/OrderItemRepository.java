package org.viators.orderprocessingsystem.orderitem;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;

import java.util.Set;

@Repository
public interface OrderItemRepository extends JpaRepository<OrderItemT, Long> {

    @Query("""
           select oi from OrderItemT oi
           join oi.order o
           join fetch oi.product
           where o.uuid = :orderUuid
           """)
    Set<OrderItemT> findAllOrderItemsForOrderWithProducts(String orderUuid);

    Integer countAllByOrder_Uuid(String orderUuid);
}
