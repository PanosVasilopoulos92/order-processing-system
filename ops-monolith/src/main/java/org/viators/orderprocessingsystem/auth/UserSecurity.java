package org.viators.orderprocessingsystem.auth;

import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.viators.orderprocessingsystem.user.UserT;

@Component(value = "userSecurity")
public class UserSecurity {

    public boolean isSelf(String userUuid) {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();

        if (authentication == null || !authentication.isAuthenticated()) {
            return false;
        }

        UserT user = (UserT) authentication.getPrincipal();
        if (user == null) return false;
        return user.getUuid().equals(userUuid);
    }
}
