package org.viators.auth.dto.response;

public record AuthenticationResponse(
        String token,
        String uuid,
        String username,
        String email,
        String role
) {
}
