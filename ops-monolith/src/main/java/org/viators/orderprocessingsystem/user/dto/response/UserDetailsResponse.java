package org.viators.orderprocessingsystem.user.dto.response;

import org.viators.orderprocessingsystem.common.enums.StatusEnum;
import org.viators.orderprocessingsystem.user.UserT;

import java.time.Instant;

public record UserDetailsResponse(
    String username,
    String uuid,
    Instant createdAt,
    StatusEnum status,
    String firstname,
    String lastName,
    String email,
    Integer age,
    String phoneNumber,
    String shippingAddress
) {

    public static UserDetailsResponse from(UserT user) {
        return new UserDetailsResponse(
            user.getUsername(),
            user.getUuid(),
            user.getCreatedAt(),
            user.getStatus(),
            user.getFirstName(),
            user.getLastName(),
            user.getEmail(),
            user.getAge(),
            user.getPhoneNumber(),
            user.getShippingAddress()
        );
    }
}
