package org.viators.orderprocessingsystem.user;

import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.web.PageableDefault;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;
import org.viators.orderprocessingsystem.user.dto.request.ChangePasswordRequest;
import org.viators.orderprocessingsystem.user.dto.request.UpdateUserInfoRequest;
import org.viators.orderprocessingsystem.user.dto.response.UserDetailsResponse;
import org.viators.orderprocessingsystem.user.dto.response.UserSummaryResponse;

@RestController
@RequestMapping("/api/v1/users")
@RequiredArgsConstructor
public class UserController {

    private final UserService userService;

    @PreAuthorize("hasRole('ADMIN')")
    @GetMapping
    public ResponseEntity<Page<UserSummaryResponse>> getAllActiveUsers(
        @PageableDefault(size = 12, sort = "createdAt", direction = Sort.Direction.DESC) Pageable pageable) {

        return ResponseEntity.ok(userService.getAllActiveUsers(pageable));
    }

    @PreAuthorize("@userSecurity.isSelf(#userUuid) or hasRole('ADMIN')")
    @GetMapping("/{userUuid}")
    public ResponseEntity<UserDetailsResponse> getUser(@PathVariable String userUuid) {
        return ResponseEntity.ok(userService.getUser(userUuid));
    }

    @PreAuthorize("@userSecurity.isSelf(#userUuid)")
    @PutMapping("/{userUuid}")
    public ResponseEntity<UserSummaryResponse> update(@PathVariable String userUuid,
                                                      @Valid @RequestBody UpdateUserInfoRequest request) {
        return ResponseEntity.ok(userService.update(userUuid, request));
    }

    @PreAuthorize("@userSecurity.isSelf(#userUuid) or hasRole('ADMIN')")
    @DeleteMapping("/{userUuid}")
    public ResponseEntity<Void> deactivateUser(@PathVariable String userUuid) {
        userService.deactivateUser(userUuid);
        return ResponseEntity.noContent().build();
    }

    @PreAuthorize("hasRole('ADMIN')")
    @PutMapping("/{userUuid}/re-activate")
    public ResponseEntity<Void> reActivateUser(@PathVariable String userUuid) {
        userService.reActivateUser(userUuid);
        return ResponseEntity.noContent().build();
    }

    @PreAuthorize("@userSecurity.isSelf(#userUuid)")
    @PatchMapping("/{userUuid}")
    public ResponseEntity<Void> changePassword(@PathVariable String userUuid,
                                               @Valid @RequestBody ChangePasswordRequest request) {
        userService.changePassword(userUuid, request);
        return ResponseEntity.noContent().build();
    }
}
