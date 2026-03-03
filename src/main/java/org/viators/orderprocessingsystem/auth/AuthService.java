package org.viators.orderprocessingsystem.auth;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.viators.orderprocessingsystem.auth.dto.request.LoginRequest;
import org.viators.orderprocessingsystem.auth.dto.request.RegisterUserRequest;
import org.viators.orderprocessingsystem.auth.dto.response.AuthenticationResponse;
import org.viators.orderprocessingsystem.exceptions.DuplicateResourceException;
import org.viators.orderprocessingsystem.exceptions.InvalidCredentialsException;
import org.viators.orderprocessingsystem.user.UserRepository;
import org.viators.orderprocessingsystem.user.UserT;

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

        String token = jwtService.generateToken(
            Map.of("role", user.getUserRole().name()),
            user
        );

        return buildAuthResponse(user, token);
    }

    /**
     * Authenticates a user and returns a JWT token.
     *
     * <p><strong>Flow:</strong>
     * <ol>
     *   <li>Delegate credential verification to Spring Security's
     *       {@link AuthenticationManager}</li>
     *   <li>If credentials are valid, load the user</li>
     *   <li>Generate and return a JWT token</li>
     * </ol>
     *
     * <p><strong>Why use AuthenticationManager instead of manual checking?</strong>
     * The AuthenticationManager is Spring Security's standard entry point for
     * authentication. It delegates to our configured {@code DaoAuthenticationProvider},
     * which handles:
     * <ul>
     *   <li>Loading UserDetails via UserDetailsService</li>
     *   <li>Password comparison via PasswordEncoder</li>
     *   <li>Account status checks (isEnabled, isAccountNonLocked, etc.)</li>
     * </ul>
     * If we did this manually, we'd bypass these built-in checks and risk
     * missing edge cases (like disabled accounts).
     *
     * @param request the login request with credentials
     * @return authentication response with JWT token
     * @throws InvalidCredentialsException if authentication fails
     */
    public AuthenticationResponse login(LoginRequest request) {
        try {
            // This single line does ALL the heavy lifting:
            // 1. Calls CustomUserDetailsService.loadUserByUsername()
            // 2. Calls BCryptPasswordEncoder.matches(raw, hashed)
            // 3. Checks isEnabled(), isAccountNonLocked(), etc.
            // 4. Throws BadCredentialsException if anything fails
            authenticationManager.authenticate(
                    new UsernamePasswordAuthenticationToken(
                            request.username(),
                            request.password()
                    )
            );
        } catch (BadCredentialsException e) {
            throw new InvalidCredentialsException();
        }

        // If we reach here, authentication was successful.
        // Load the full user to include in the response.
        UserT user = userRepository.findByUsername(request.username())
                .orElseThrow(InvalidCredentialsException::new);

        String token = jwtService.generateToken(
                Map.of("role", user.getUserRole().name()),
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
