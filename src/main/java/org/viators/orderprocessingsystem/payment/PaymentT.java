package org.viators.orderprocessingsystem.payment;

import jakarta.persistence.*;
import lombok.*;
import lombok.experimental.SuperBuilder;
import org.viators.orderprocessingsystem.common.BaseEntity;
import org.viators.orderprocessingsystem.common.enums.PaymentMethodEnum;
import org.viators.orderprocessingsystem.common.enums.PaymentStateEnum;
import org.viators.orderprocessingsystem.order.OrderT;

import java.math.BigDecimal;

@Entity
@Table(name = "payments")
@Getter
@Setter
@ToString
@SuperBuilder
@NoArgsConstructor
@AllArgsConstructor
public class PaymentT extends BaseEntity {

    @Column(name = "amount", nullable = false)
    private BigDecimal amount;

    @Enumerated(EnumType.STRING)
    @Column(name = "payment_state", nullable = false)
    private PaymentStateEnum paymentState;

    @Enumerated(EnumType.STRING)
    @Column(name = "payment_method", nullable = false)
    private PaymentMethodEnum paymentMethod;

    @Column(name = "reason_of_failure")
    private String failureReason;

    @ManyToOne
    @JoinColumn(name = "order_id", nullable = false, updatable = false)
    private OrderT order;

}
