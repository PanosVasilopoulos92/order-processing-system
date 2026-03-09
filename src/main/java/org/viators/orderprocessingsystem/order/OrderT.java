package org.viators.orderprocessingsystem.order;

import jakarta.persistence.*;
import lombok.*;
import lombok.experimental.SuperBuilder;
import org.viators.orderprocessingsystem.common.BaseEntity;
import org.viators.orderprocessingsystem.common.enums.OrderStateEnum;
import org.viators.orderprocessingsystem.exceptions.BusinessValidationException;
import org.viators.orderprocessingsystem.orderitem.OrderItemT;
import org.viators.orderprocessingsystem.payment.PaymentT;
import org.viators.orderprocessingsystem.user.UserT;

import java.math.BigDecimal;
import java.util.HashSet;
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

    @Column(name = "total_amount")
    private BigDecimal totalAmount;

    @Enumerated(EnumType.STRING)
    @Column(name = "order_state")
    private OrderStateEnum orderState;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "customer_id", referencedColumnName = "id", nullable = false)
    private UserT customer;

    @OneToMany(mappedBy = "order", fetch = FetchType.LAZY, cascade = CascadeType.ALL, orphanRemoval = true)
    private Set<OrderItemT> orderItems = new HashSet<>();

    @OneToMany(mappedBy = "order", cascade = {CascadeType.PERSIST, CascadeType.MERGE})
    private Set<PaymentT> payments = new HashSet<>();

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

    public void addPayment(PaymentT payment) {
        if (payment != null) {
            this.payments.add(payment);
            payment.setOrder(this);
        }
    }

}
