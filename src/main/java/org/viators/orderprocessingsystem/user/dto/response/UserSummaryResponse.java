package org.viators.orderprocessingsystem.user.dto.response;

import org.viators.orderprocessingsystem.common.enums.StatusEnum;
import org.viators.orderprocessingsystem.user.UserT;

import java.time.Instant;

public record UserSummaryResponse(
    String username,
    String uuid,
    StatusEnum status
) {

    public static UserSummaryResponse from(UserT user) {
        return new UserSummaryResponse(
            user.getUsername(),
            user.getUuid(),
            user.getStatus()
        );
    }
}
