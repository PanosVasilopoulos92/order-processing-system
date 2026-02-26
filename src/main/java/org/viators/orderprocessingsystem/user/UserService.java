package org.viators.orderprocessingsystem.user;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.viators.orderprocessingsystem.common.enums.StatusEnum;

@Service
@RequiredArgsConstructor
@Slf4j
@Transactional(readOnly = true)
public class UserService {

    private final UserRepository userRepository;

    public UserT getActiveUser(String userUuid) {
        return userRepository.findByUuidAndStatus(userUuid, StatusEnum.ACTIVE)
                .orElseThrow(() -> new IllegalArgumentException("No such user found"));
    }
}
