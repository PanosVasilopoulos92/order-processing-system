package org.viators.orderprocessingsystem.user.dto.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record ChangePasswordRequest(
    @NotBlank(message = "Old password is required")
    @Size(min = 8, max = 100, message = "Password can be between 8-100 characters long")
    String oldPassword,

    @NotBlank(message = "New password is required")
    @Size(min = 8, max = 100, message = "Password can be between 8-100 characters long")
    String newPassword,

    @NotBlank(message = "Password confirmation is required")
    @Size(min = 8, max = 100, message = "Password can be between 8-100 characters long")
    String confirmNewPassword
) {
}
