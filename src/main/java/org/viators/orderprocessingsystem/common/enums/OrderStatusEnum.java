package org.viators.orderprocessingsystem.common.enums;

import lombok.Getter;

@Getter
public enum OrderStatusEnum {
    PENDING,
    CONFIRMED,
    SHIPPED,
    DELIVERED,
    CANCELLED
}
