package org.viators.orderprocessingsystem.common.services;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.viators.orderprocessingsystem.exceptions.AccessDeniedException;

@Service
@Slf4j
public class OwnershipAuthorizationService {

    public void verifyOwnership(String loggedInUser, String resourceOwner) {
        if (loggedInUser != null && !loggedInUser.equals(resourceOwner)) {
            log.warn("User with uuid: {} tried to access a resource that belongs to another user", loggedInUser);
            throw new AccessDeniedException("Resource does not belong to logged in user");
        }
    }
}
