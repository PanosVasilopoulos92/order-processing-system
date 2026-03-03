package org.viators.orderprocessingsystem.auth.dto.response;

public record AuthenticationResponse(
        String token,
        String uuid,
        String username,
        String email,
        String role
) {
}
