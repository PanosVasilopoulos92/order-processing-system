package org.viators.orderprocessingsystem.common.enums;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

@Getter
@RequiredArgsConstructor
public enum StatusEnum {
    ACTIVE("1"),
    INACTIVE("0");

    private final String code;

    public StatusEnum getStatusEnum(String code) {
        return switch (code) {
            case "0" -> StatusEnum.INACTIVE;
            case "1" -> StatusEnum.ACTIVE;
            default -> throw new IllegalArgumentException("Code does not match to any value of this enum");
        };
    }
}
