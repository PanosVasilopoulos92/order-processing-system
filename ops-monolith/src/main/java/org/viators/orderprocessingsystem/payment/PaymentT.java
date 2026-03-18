package org.viators.orderprocessingsystem.payment;

import jakarta.persistence.*;
import lombok.*;
import lombok.experimental.SuperBuilder;
import org.viators.orderprocessingsystem.common.BaseEntity;
import org.viators.orderprocessingsystem.common.enums.PaymentMethodEnum;
import org.viators.orderprocessingsystem.common.enums.PaymentStateEnum;
import org.viators.orderprocessingsystem.common.enums.PaymentTypeEnum;
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

    @Enumerated(EnumType.STRING)
    @Column(name = "payment_type", nullable = false)
    private PaymentTypeEnum paymentType;

    @Column(name = "reason_of_failure")
    private String failureReason;

    @ManyToOne
    @JoinColumn(name = "order_id", nullable = false, updatable = false)
    private OrderT order;

    @OneToOne
    @JoinColumn(name = "refunded_payment_id")
    private PaymentT refundPayment;

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof PaymentT that)) return false;
        return getUuid() != null && getUuid().equals(that.getUuid());
    }

    @Override
    public int hashCode() {
        return getClass().hashCode();
    }
}
