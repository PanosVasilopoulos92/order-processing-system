package org.viators.orderprocessingsystem.common.enums;

import lombok.Getter;

@Getter
public enum OrderStateEnum {
    PENDING,
    CONFIRMED,
    SHIPPED,
    DELIVERED,
    CANCELLED
}
