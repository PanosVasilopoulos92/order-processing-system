package org.viators.orderprocessingsystem.auth.dto.request;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.viators.orderprocessingsystem.user.UserT;

public record RegisterUserRequest(
        @NotBlank(message = "Username is required")
        @Size(min = 3, max = 50, message = "Username must be between 3 and 50 characters")
        String username,

        @NotBlank(message = "Email is required")
        @Email(message = "Email must be a valid email address")
        String email,

        @NotBlank(message = "Password is required")
        @Size(min = 8, max = 100, message = "Password must be between 8 and 100 characters")
        String password,

        String firstName,

        String lastName,

        Integer age
) {

    public UserT toEntity() {
        return UserT.builder()
            .username(username)
            .email(email)
            .password(password)
            .firstName(firstName)
            .lastName(lastName)
            .age(age)
            .build();
    }
}
