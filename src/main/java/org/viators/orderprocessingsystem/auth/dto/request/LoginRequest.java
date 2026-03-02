package org.viators.orderprocessingsystem.auth.dto.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record LoginRequest(
        @NotBlank(message = "Username is required")
        @Size(min = 3, max = 50, message = "Username must be between 3-50 characters long")
        String username,

        @NotBlank(message = "Password is required")
        @Size(min = 8, max = 100, message = "Password can be between 8-100 characters long")
        String password
) {
}
