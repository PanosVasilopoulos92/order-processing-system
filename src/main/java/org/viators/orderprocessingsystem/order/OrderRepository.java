package org.viators.orderprocessingsystem.order;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
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
            join o.customer c
            left join o.orderItems oi
            where c.uuid = :customerUuid
            and o.uuid = :orderUuid
            """)
    Optional<OrderT> findByUuidAndCustomerWithOrderItems(String customerUuid, String orderUuid);

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
    Page<OrderSummaryResponse> findOrderSummariesByCustomerUuid(String customerUuid, Pageable pageable);

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
    Page<OrderSummaryResponse> findOrderSummariesByCustomerUuidAndOrderState(String customerUuid, OrderStateEnum orderState, Pageable pageable);
}
