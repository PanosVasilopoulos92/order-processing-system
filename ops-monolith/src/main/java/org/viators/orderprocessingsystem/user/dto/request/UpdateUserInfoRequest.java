package org.viators.orderprocessingsystem.user.dto.request;

import org.viators.orderprocessingsystem.user.UserT;

import java.util.Optional;

public record UpdateUserInfoRequest(
    String firstname,
    String lastName,
    String email,
    Integer age,
    String phoneNumber,
    String shippingAddress
) {

    public void updateUser(UserT user) {
        Optional.ofNullable(firstname).ifPresent(user::setFirstName);
        Optional.ofNullable(lastName).ifPresent(user::setLastName);
        Optional.ofNullable(email).ifPresent(user::setEmail);
        Optional.ofNullable(age).ifPresent(user::setAge);
        Optional.ofNullable(phoneNumber).ifPresent(user::setPhoneNumber);
        Optional.ofNullable(shippingAddress).ifPresent(user::setShippingAddress);
    }

}
