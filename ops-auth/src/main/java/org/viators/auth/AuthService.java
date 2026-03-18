package org.viators.auth;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.viators.auth.dto.request.LoginRequest;
import org.viators.auth.dto.request.RegisterUserRequest;
import org.viators.auth.dto.response.AuthenticationResponse;
import org.viators.auth.user.UserRepository;
import org.viators.auth.user.UserT;
import org.viators.common.exceptions.DuplicateResourceException;
import org.viators.common.exceptions.InvalidCredentialsException;

import java.util.Map;

@Service
@RequiredArgsConstructor
@Slf4j
@Transactional(readOnly = true)
public class AuthService {

    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;
    private final JwtService jwtService;
    private final AuthenticationManager authenticationManager;

    @Transactional
    public AuthenticationResponse registerUser(RegisterUserRequest request) {
        if (userRepository.existsByUsername(request.username())) {
            throw new DuplicateResourceException("User", "username", request.username());
        }

        if (userRepository.existsByEmail(request.email())) {
            throw new DuplicateResourceException("User", "email", request.email());
        }

        UserT user = request.toEntity();
        user.setPassword(passwordEncoder.encode(request.password()));

        user = userRepository.save(user);
        log.info("New user registered: {} (uuid: {})", user.getUsername(), user.getUuid());

        // uuid claim is REQUIRED — the gateway extracts it to build
        // the X-User-UUID header for downstream services.
        String token = jwtService.generateToken(
            Map.of(
                "role", user.getUserRole().name(),
                "uuid", user.getUuid()
            ),
            user
        );

        return buildAuthResponse(user, token);
    }

    public AuthenticationResponse login(LoginRequest request) {
        try {
            authenticationManager.authenticate(
                new UsernamePasswordAuthenticationToken(
                    request.username(),
                    request.password()
                )
            );
        } catch (BadCredentialsException e) {
            throw new InvalidCredentialsException();
        }

        UserT user = userRepository.findByUsername(request.username())
            .orElseThrow(InvalidCredentialsException::new);

        String token = jwtService.generateToken(
            Map.of(
                "role", user.getUserRole().name(),
                "uuid", user.getUuid()
            ),
            user
        );

        log.info("User logged in: {}", user.getUsername());
        return buildAuthResponse(user, token);
    }

    private AuthenticationResponse buildAuthResponse(UserT user, String token) {
        return new AuthenticationResponse(
            token,
            user.getUuid(),
            user.getUsername(),
            user.getEmail(),
            user.getUserRole().name()
        );
    }
}