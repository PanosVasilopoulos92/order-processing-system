package org.viators.orderprocessingsystem.user;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.viators.orderprocessingsystem.common.enums.StatusEnum;
import org.viators.orderprocessingsystem.exceptions.BusinessValidationException;
import org.viators.orderprocessingsystem.exceptions.ResourceNotFoundException;
import org.viators.orderprocessingsystem.user.dto.request.ChangePasswordRequest;
import org.viators.orderprocessingsystem.user.dto.request.UpdateUserInfoRequest;
import org.viators.orderprocessingsystem.user.dto.response.UserDetailsResponse;
import org.viators.orderprocessingsystem.user.dto.response.UserSummaryResponse;

@Service
@RequiredArgsConstructor
@Slf4j
@Transactional(readOnly = true)
public class UserService {

    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;

    public UserT getActiveUser(String userUuid) {
        return userRepository.findByUuidAndStatus(userUuid, StatusEnum.ACTIVE)
            .orElseThrow(() -> new ResourceNotFoundException("User/Customer", "uuid", userUuid));
    }

    public UserDetailsResponse getUser(String userUuid) {
        UserT user = userRepository.findByUuid(userUuid)
            .orElseThrow(() -> new ResourceNotFoundException("User", "uuid", userUuid));

        return UserDetailsResponse.from(user);
    }

    public Page<UserSummaryResponse> getAllActiveUsers(Pageable pageable) {
        return userRepository.findAllByStatus(StatusEnum.ACTIVE, pageable)
            .map(UserSummaryResponse::from);
    }

    @Transactional
    public UserSummaryResponse update(String userUuid, UpdateUserInfoRequest request) {
        UserT user = userRepository.findByUuid(userUuid)
            .orElseThrow(() -> new ResourceNotFoundException("User", "uuid", userUuid));

        request.updateUser(user);
        return UserSummaryResponse.from(user);
    }

    @Transactional
    public void deactivateUser(String userUuid) {
        UserT user = userRepository.findByUuid(userUuid)
            .orElseThrow(() -> new ResourceNotFoundException("User", "uuid", userUuid));

        if (user.getStatus().equals(StatusEnum.INACTIVE)) {
            throw new BusinessValidationException("User is already deactivated");
        }

        user.setStatus(StatusEnum.INACTIVE);
    }

    @Transactional
    public void reActivateUser(String userUuid) {
        UserT user = userRepository.findByUuid(userUuid)
            .orElseThrow(() -> new ResourceNotFoundException("User", "uuid", userUuid));

        user.setStatus(StatusEnum.ACTIVE);
    }

    @Transactional
    public void changePassword(String userUuid, ChangePasswordRequest request) {
        UserT userT = userRepository.findByUuid(userUuid)
            .orElseThrow(() -> new ResourceNotFoundException("User", "uuid", userUuid));

        if (!passwordEncoder.matches(request.oldPassword(), userT.getPassword())) {
            throw new BusinessValidationException("Old password is incorrect");
        }

        if (passwordEncoder.matches(request.newPassword(), userT.getPassword())) {
            throw new BusinessValidationException("New password must be different from the current password");
        }

        if (!request.newPassword().equals(request.confirmNewPassword())) {
            throw new BusinessValidationException("Confirmation password does not match new password's value");
        }

        userT.setPassword(passwordEncoder.encode(request.newPassword()));
    }
}
