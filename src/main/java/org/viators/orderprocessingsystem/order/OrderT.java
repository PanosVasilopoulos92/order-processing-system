package org.viators.orderprocessingsystem.order;

import jakarta.persistence.*;
import lombok.*;
import lombok.experimental.SuperBuilder;
import org.viators.orderprocessingsystem.common.BaseEntity;
import org.viators.orderprocessingsystem.common.enums.OrderStatusEnum;
import org.viators.orderprocessingsystem.exceptions.BusinessValidationException;
import org.viators.orderprocessingsystem.orderitem.OrderItemT;
import org.viators.orderprocessingsystem.user.UserT;

import java.math.BigDecimal;
import java.util.Set;

@Entity
@Table(name = "orders")
@Getter
@Setter
@ToString
@SuperBuilder
@NoArgsConstructor
@AllArgsConstructor
public class OrderT extends BaseEntity {

    @Column(name = "customer_address", nullable = false)
    private String shippingAddress;

    @Column(name = "total_amount", nullable = false)
    private BigDecimal totalAmount;

    @OneToMany(mappedBy = "order", fetch = FetchType.LAZY)
    private Set<OrderItemT> orderItems;

    @Enumerated(EnumType.STRING)
    @Column(name = "order_status", nullable = false)
    private OrderStatusEnum orderStatus;

    @ManyToOne
    @JoinColumn(name = "customer_id", referencedColumnName = "id", nullable = false)
    private UserT customer;

    // Helper methods
    public void addOrderItem(OrderItemT orderItem) {
        if (orderItem != null) {
            if (this.orderItems.contains(orderItem)) {
                throw new BusinessValidationException("Order Item already exist in this order");
            }
            this.orderItems.add(orderItem);
            orderItem.setOrder(this);
        }
    }

}
