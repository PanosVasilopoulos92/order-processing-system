package org.viators.orderprocessingsystem.auth;

import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;

/**
 * Security utility for ownership checks in @PreAuthorize expressions.
 * Updated to work with GatewayPrincipal (from gateway headers).
 */
@Component(value = "userSecurity")
public class UserSecurity {

    public boolean isSelf(String userUuid) {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();

        if (authentication == null || !authentication.isAuthenticated()) {
            return false;
        }

        Object principal = authentication.getPrincipal();

        if (principal instanceof GatewayPrincipal gp) {
            return gp.getUuid().equals(userUuid);
        }

        return false;
    }
}