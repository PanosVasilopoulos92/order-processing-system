package org.viators.orderprocessingsystem.auth;

import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.support.ServletUriComponentsBuilder;
import org.viators.orderprocessingsystem.auth.dto.request.LoginRequest;
import org.viators.orderprocessingsystem.auth.dto.request.RegisterUserRequest;
import org.viators.orderprocessingsystem.auth.dto.response.AuthenticationResponse;

import java.net.URI;

@RestController
@RequestMapping("/api/v1/auth")
@RequiredArgsConstructor
public class AuthenticationController {

    private final AuthService authService;

    @PostMapping("/register")
    public ResponseEntity<AuthenticationResponse> register(
        @Valid @RequestBody RegisterUserRequest request) {

        AuthenticationResponse response = authService.registerUser(request);
        URI location = ServletUriComponentsBuilder
            .fromCurrentRequest()
            .path("{/uuid}")
            .buildAndExpand(response.uuid())
            .toUri();

        return ResponseEntity.created(location).body(response);
    }

    @PostMapping("login")
    public ResponseEntity<AuthenticationResponse> login(
        @Valid @RequestBody LoginRequest request) {

        AuthenticationResponse response = authService.login(request);
        return ResponseEntity.ok(response);
    }
}
