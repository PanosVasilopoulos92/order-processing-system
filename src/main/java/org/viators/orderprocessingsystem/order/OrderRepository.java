package org.viators.orderprocessingsystem.order;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;
import org.viators.orderprocessingsystem.common.enums.OrderStateEnum;
import org.viators.orderprocessingsystem.common.enums.StatusEnum;
import org.viators.orderprocessingsystem.order.dto.response.OrderSummaryResponse;

import java.util.Optional;

@Repository
public interface OrderRepository extends JpaRepository<OrderT, Long> {

    Optional<OrderT> findByUuidAndStatus(String uuid, StatusEnum status);

    @Query("""
            select o from OrderT o
            join fetch o.customer c
            left join fetch o.orderItems oi
            where c.uuid = :customerUuid
            and o.uuid = :orderUuid
            """)
    Optional<OrderT> findByUuidAndCustomerWithOrderItemsAndCustomer(@Param("customerUuid") String customerUuid,
                                                                    @Param("orderUuid") String orderUuid);

    @Query(value = """
            select new org.viators.orderprocessingsystem.order.dto.response.OrderSummaryResponse(
                o.uuid, o.orderState, o.totalAmount, size(o.orderItems), o.createdAt
            ) from OrderT o
            where o.customer.uuid = :customerUuid
            group by o.uuid, o.orderState, o.totalAmount, o.createdAt
            """,
            countQuery = """
            select count(o) from OrderT o
            where o.customer.uuid = :customerUuid
            """)
    Page<OrderSummaryResponse> findOrderSummariesByCustomerUuid(@Param("customerUuid") String customerUuid,
                                                                Pageable pageable);

    @Query(value = """
            select new org.viators.orderprocessingsystem.order.dto.response.OrderSummaryResponse(
                o.uuid, o.orderState, o.totalAmount, size(o.orderItems) , o.createdAt
            ) from OrderT o
            where o.customer.uuid = :customerUuid and o.orderState = :orderState
            group by o.uuid, o.orderState, o.totalAmount, o.createdAt
            """,
            countQuery = """
            select count(o) from OrderT o
            where o.customer.uuid = :customerUuid and o.orderState = :orderState
            """)
    Page<OrderSummaryResponse> findOrderSummariesByCustomerUuidAndOrderState(@Param("customerUuid") String customerUuid,
                                                                             @Param("orderState") OrderStateEnum orderState,
                                                                             Pageable pageable);
}
